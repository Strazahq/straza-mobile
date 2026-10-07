package dev.straza.approver.mock

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReviewModeTest {

    private val started = mutableListOf<MockStrazad>()
    private val client: HttpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        started.forEach { it.close() }
    }

    private fun reviewMock(token: String = "REVIEW-CODE"): MockStrazad =
        MockStrazad(
            enrollToken = token,
            publicUrl = "https://demo.example",
            plainHttp = true,
            reviewPage = true,
        ).start(0).also { started.add(it) }

    @Test
    fun `qr behind a proxy advertises the public url and omits the pin`() {
        val mock = reviewMock()
        val payload = json.parseToJsonElement(mock.qrPayload()).jsonObject
        assertEquals("https://demo.example", payload["servers"]!!.jsonArray[0].jsonPrimitive.content)
        assertFalse("pin" in payload)
    }

    @Test
    fun `dev default still pins its own certificate`() {
        val mock = MockStrazad().start(0).also { started.add(it) }
        val payload = json.parseToJsonElement(mock.qrPayload()).jsonObject
        assertEquals(mock.spkiPin, payload["pin"]!!.jsonPrimitive.content)
        assertEquals("https://localhost:${mock.port}", payload["servers"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `review page serves instructions and the qr png, get only`() {
        val mock = reviewMock(token = "STRAZA-TEST-TOKEN")
        val base = "http://127.0.0.1:${mock.port}"

        val page = get("$base/review")
        assertEquals(200, page.statusCode())
        val html = page.body().decodeToString()
        assertTrue("STRAZA-TEST-TOKEN" in html)
        assertTrue("https://demo.example" in html)

        val png = get("$base/review/qr.png")
        assertEquals(200, png.statusCode())
        assertEquals("image/png", png.headers().firstValue("Content-Type").get())
        assertEquals(0x89.toByte(), png.body()[0]) // PNG signature

        val post = client.send(
            HttpRequest.newBuilder(URI.create("$base/review"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(405, post.statusCode())
    }

    @Test
    fun `without the review flag the page does not exist`() {
        val mock = MockStrazad(plainHttp = true).start(0).also { started.add(it) }
        assertEquals(404, get("http://127.0.0.1:${mock.port}/review").statusCode())
    }

    @Test
    fun `dev controls do not exist on the review deployment`() {
        val mock = reviewMock()
        assertEquals(404, post("http://127.0.0.1:${mock.port}/mock/outage?status=503&seconds=60", "").statusCode())
        assertEquals(404, post("http://127.0.0.1:${mock.port}/mock/expire-tokens", "").statusCode())
        assertEquals(404, post("http://127.0.0.1:${mock.port}/mock/token-ttl?seconds=60", "").statusCode())
    }

    @Test
    fun `dev controls declare and lift an outage and expire tokens`() {
        val mock = MockStrazad(enrollToken = "REVIEW-CODE", plainHttp = true, devControls = true)
            .start(0).also { started.add(it) }
        val base = "http://127.0.0.1:${mock.port}"
        val (token, _) = enroll(mock)
        assertEquals(200, get("$base/v1/approver/pending", bearer = token).statusCode())

        assertEquals(200, post("$base/mock/outage?status=503&seconds=60", "").statusCode())
        val down = get("$base/v1/approver/pending", bearer = token)
        assertEquals(503, down.statusCode())
        assertEquals("20", down.headers().firstValue("Retry-After").orElse(null))
        assertEquals("service_unavailable", json.parseToJsonElement(down.body().decodeToString()).jsonObject["code"]!!.jsonPrimitive.content)
        // An outage covers the whole API, enroll included.
        assertEquals(503, post("$base/v1/approver/enroll", "{}").statusCode())
        // The controls stay reachable during the outage, so it can be lifted early.
        assertEquals(200, post("$base/mock/outage?seconds=0", "").statusCode())
        assertEquals(200, get("$base/v1/approver/pending", bearer = token).statusCode())

        assertEquals(200, post("$base/mock/expire-tokens", "").statusCode())
        val expired = get("$base/v1/approver/pending", bearer = token)
        assertEquals(401, expired.statusCode())
        assertEquals("token_expired", json.parseToJsonElement(expired.body().decodeToString()).jsonObject["code"]!!.jsonPrimitive.content)

        assertEquals(400, post("$base/mock/token-ttl?seconds=0", "").statusCode())
        assertEquals(200, post("$base/mock/token-ttl?seconds=120", "").statusCode())
        assertEquals(
            "120",
            json.parseToJsonElement(get("$base/mock/token-ttl").body().decodeToString()).jsonObject["token_ttl_seconds"]!!.jsonPrimitive.content,
        )
        val (_, _) = enroll(mock) // enrollment still works with the new lifetime
    }

    @Test
    fun `tick prunes the expired and keeps one hold and one ticket live`() {
        val mock = reviewMock()
        mock.seedRequest(tool = "old:gone", ttlSeconds = -5)

        mock.reviewTick()

        val (bearer, _) = enroll(mock)
        val rows = pendingRows(mock, bearer)
        assertEquals(2, rows.size)
        assertTrue(rows.none { it["summary"]!!.jsonObject["tool_name"]!!.jsonPrimitive.content == "gone" })
        assertEquals(1, rows.count { it["class"]?.jsonPrimitive?.content == "ticket" })
    }

    @Test
    fun `a decided hold is replaced by the next tick`() {
        val mock = reviewMock()
        mock.reviewTick()
        val (bearer, keyPair) = enroll(mock)

        val hold = pendingRows(mock, bearer).first { it["class"]?.jsonPrimitive?.content != "ticket" }
        val id = hold["id"]!!.jsonPrimitive.content
        val challenge = hold["challenge"]!!.jsonPrimitive.content
        val ts = System.currentTimeMillis() / 1000
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update("$id\napprove\n$challenge\n$ts".toByteArray(Charsets.UTF_8))
            sign()
        }
        val decide = post(
            "http://127.0.0.1:${mock.port}/v1/approver/decide",
            """{"request_id":"$id","verdict":"approve","challenge":"$challenge","ts":$ts,""" +
                """"signature":"${Base64.getEncoder().encodeToString(signature)}"}""",
            bearer,
        )
        assertEquals(200, decide.statusCode())

        mock.reviewTick()

        val after = pendingRows(mock, bearer)
        assertTrue(after.none { it["id"]!!.jsonPrimitive.content == id })
        assertEquals(1, after.count { it["class"]?.jsonPrimitive?.content != "ticket" })
        assertEquals(1, after.count { it["class"]?.jsonPrimitive?.content == "ticket" })
    }

    @Test
    fun `oversized bodies are refused`() {
        val mock = reviewMock()
        val response = post("http://127.0.0.1:${mock.port}/v1/approver/enroll", "x".repeat(70 * 1024))
        assertEquals(413, response.statusCode())
    }

    private fun get(url: String, bearer: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(url)).GET()
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun post(url: String, body: String, bearer: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun enroll(mock: MockStrazad): Pair<String, KeyPair> {
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val body = """{"enroll_token":"REVIEW-CODE","device":{"name":"test","platform":"android",""" +
            """"key_alg":"ecdsa-p256","public_key":"${Base64.getEncoder().encodeToString(keyPair.public.encoded)}",""" +
            """"key_security_level":"software","attestation":{"kind":"none"}}}"""
        val response = post("http://127.0.0.1:${mock.port}/v1/approver/enroll", body)
        assertEquals(201, response.statusCode())
        val token = json.parseToJsonElement(response.body()).jsonObject["device_token"]!!.jsonPrimitive.content
        return token to keyPair
    }

    private fun pendingRows(mock: MockStrazad, bearer: String): List<JsonObject> {
        val response = get("http://127.0.0.1:${mock.port}/v1/approver/pending", bearer)
        assertEquals(200, response.statusCode())
        return json.parseToJsonElement(response.body().decodeToString()).jsonArray.map { it.jsonObject }
    }
}

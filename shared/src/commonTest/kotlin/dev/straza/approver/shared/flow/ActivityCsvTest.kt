package dev.straza.approver.shared.flow

import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.net.ResolvedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityCsvTest {

    private fun row(
        id: String = "apr_1",
        requester: String = "nova",
        tool: String = "aws:delete_bucket",
        rule: String = "rule:s3.delete",
        state: ResolvedState = ResolvedState.APPROVED,
        decidedBy: String? = "kim",
        decidedAt: Long? = 1_600_000_000L,
        justification: String = "agent says: cleanup",
    ) = ResolvedRequest(id, requester, tool, rule, state, decidedBy, decidedAt, justification)

    @Test
    fun header_is_first_line_and_names_the_reason_unverified() {
        val csv = ActivityCsv.export("Acme Prod", emptyList())
        val header = csv.lineSequence().first()
        assertEquals(
            "deployment,decided_at_utc,state,tool,requester,rule_id,decided_by,reason (unverified agent claim)",
            header,
        )
        // An empty feed exports the header and nothing else.
        assertEquals("$header\r\n", csv)
    }

    @Test
    fun a_row_lays_out_every_column_in_order() {
        val csv = ActivityCsv.export("Acme Prod", listOf(row()))
        val line = csv.lineSequence().drop(1).first()
        assertEquals(
            "Acme Prod,2020-09-13T12:26:40Z,approved,aws:delete_bucket,nova,rule:s3.delete,kim,agent says: cleanup",
            line,
        )
    }

    @Test
    fun null_deployment_decider_and_time_render_empty_cells() {
        val csv = ActivityCsv.export(
            null,
            listOf(row(state = ResolvedState.EXPIRED, decidedBy = null, decidedAt = null, justification = "")),
        )
        val line = csv.lineSequence().drop(1).first()
        // deployment, decided_at, decided_by, reason all blank; expired has no decider.
        assertEquals(",,expired,aws:delete_bucket,nova,rule:s3.delete,,", line)
    }

    @Test
    fun fields_with_commas_quotes_or_newlines_are_rfc4180_quoted() {
        // Asserts on the whole string: the reason field embeds a newline, so
        // splitting into lines would break it mid-field.
        val csv = ActivityCsv.export(
            "Acme, Inc",
            listOf(row(justification = "he said \"go\"\nthen left", requester = "no,body")),
        )
        assertTrue(csv.contains("\"Acme, Inc\","), "comma field quoted: $csv")
        assertTrue(csv.contains(",\"no,body\","), "comma field quoted: $csv")
        // Internal quotes are doubled and the newline stays inside the quoted field.
        assertTrue(csv.contains("\"he said \"\"go\"\"\nthen left\"\r\n"), "quote+newline field: $csv")
    }

    @Test
    fun export_all_tags_each_row_with_its_own_deployment() {
        val csv = ActivityCsv.exportAll(
            listOf(
                "Acme Prod" to listOf(row(id = "a1", tool = "aws:delete_bucket")),
                "Fin Sandbox" to listOf(row(id = "f1", tool = "okta:suspend_user", decidedBy = "dana")),
            ),
        )
        assertEquals(3, csv.lineSequence().count { it.isNotBlank() }) // header + 2 rows
        assertTrue(csv.contains("Acme Prod,2020-09-13T12:26:40Z,approved,aws:delete_bucket,"), csv)
        assertTrue(csv.contains("Fin Sandbox,2020-09-13T12:26:40Z,approved,okta:suspend_user,nova,rule:s3.delete,dana,"), csv)
    }

    @Test
    fun iso_utc_is_pure_and_correct_at_known_points() {
        assertEquals("1970-01-01T00:00:00Z", ActivityCsv.isoUtc(0L))
        assertEquals("2020-09-13T12:26:40Z", ActivityCsv.isoUtc(1_600_000_000L))
        // A leap day, to exercise the civil-from-days month/day math.
        assertEquals("2024-02-29T23:59:59Z", ActivityCsv.isoUtc(1_709_251_199L))
    }
}

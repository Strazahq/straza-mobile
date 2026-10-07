package dev.straza.approver

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.content.Context
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The enrollment viewfinder: a CameraX preview plus a ZXing decode of each
 * frame. It hands the decoded string over once and validates nothing: a
 * scanned payload is untrusted input and meets the same parser as a pasted one
 * ([dev.straza.approver.shared.protocol.EnrollmentQr]).
 *
 * Frames are analysed in memory and are not written or logged: the payload
 * carries a one-time enroll token.
 */
@Composable
fun QrScanner(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnDecoded by rememberUpdatedState(onDecoded)

    /**
     * A code in frame decodes on every frame. Only the first result may escape:
     * each would start its own enrollment, which creates a hardware key and
     * spends the one-time token. Atomic because the analyzer runs on its own
     * thread.
     */
    val consumed = remember { AtomicBoolean(false) }

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE (TextureView), not the default SurfaceView: this window
            // carries FLAG_SECURE, and a secure SurfaceView has rendered black
            // on some devices. A TextureView composites into the view hierarchy.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    // QR only: the console shows a QR, and every other
                    // symbology would only add attack surface.
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    // A console in a forced-dark browser shows the QR
                    // light-on-dark, and ZXing tries the inverted image only
                    // when asked (TRY_HARDER does not cover it). Costs one
                    // extra decode pass per frame.
                    DecodeHintType.ALSO_INVERTED to true,
                ),
            )
        }
    }

    // Held so the camera can be released on dispose without waiting on the
    // provider future again. Not read during composition, so writing it
    // recomposes nothing.
    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(Unit) {
        val provider = awaitCameraProvider(context).also { providerHolder.value = it }

        val preview = Preview.Builder().build()
            .apply { surfaceProvider = previewView.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            // Decoding is slower than the frame rate, so drop frames instead of
            // queueing them.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // The default analysis stream is about 640x480, too few pixels per
            // module for a small on-screen QR. 720p roughly doubles them.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { image ->
                    image.use {
                        if (consumed.get()) return@use
                        val text = decodeQr(reader, it) ?: return@use
                        if (consumed.compareAndSet(false, true)) {
                            previewView.post { latestOnDecoded(text) }
                        }
                    }
                }
            }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // Release the camera when this leaves composition, so the in-use
            // indicator does not stay on with no viewfinder visible.
            providerHolder.value?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
}

/**
 * Awaits CameraX's provider without a `concurrent-futures-ktx` dependency.
 * The listener fires on the main executor, which is also where CameraX
 * expects binding to happen.
 */
private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

/**
 * Decodes one frame, or returns null. Reads only the Y (luminance) plane.
 * `rowStride` is passed as the data width because camera buffers are commonly
 * padded, and treating padding as pixels shears the image. Frames are not
 * rotated: the decoder locates finder patterns in any orientation.
 */
private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)

    return try {
        val source = PlanarYUVLuminanceSource(
            data,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
    } catch (_: Exception) {
        // NotFoundException on every frame without a code, which is most of
        // them, plus the occasional short or padded buffer.
        null
    } finally {
        reader.reset()
    }
}

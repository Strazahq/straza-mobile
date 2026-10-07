package dev.straza.approver.shared.host

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVCaptureConnection
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS viewfinder for enrollment. The decoded payload is untrusted camera
 * input: it goes to [onDecoded] (the controller's `enroll`), which runs it
 * through the same fail-closed parser as a typed code.
 *
 * Uses AVFoundation's QR decoder (AVCaptureMetadataOutput). The delegate
 * fires for every frame while a code is in view, so delivery is limited to
 * one payload per composition.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun IosQrScanner(onDecoded: (String) -> Unit) {
    val session = remember { AVCaptureSession() }
    val delegate = remember { QrDelegate(onDecoded) }

    UIKitView(
        factory = {
            val view = ScannerPreviewView(session)
            val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
            if (input != null && session.canAddInput(input)) {
                session.addInput(input)
                val output = AVCaptureMetadataOutput()
                if (session.canAddOutput(output)) {
                    session.addOutput(output)
                    // Delegate callbacks on main: the payload goes straight
                    // into controller state, which is main-confined.
                    output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
                    // Set only after the output joins the session; the
                    // available types are empty before that.
                    output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
                }
                // startRunning blocks while the camera starts, so it runs off main.
                dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                    session.startRunning()
                }
            }
            view
        },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(Unit) {
        onDispose {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                session.stopRunning()
            }
        }
    }
}

/** UIView hosting the preview layer; layoutSubviews keeps the layer sized. */
@OptIn(ExperimentalForeignApi::class)
private class ScannerPreviewView(session: AVCaptureSession) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val preview = AVCaptureVideoPreviewLayer(session = session).also {
        it.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.addSublayer(it)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        preview.frame = bounds
    }
}

private class QrDelegate(
    private val onDecoded: (String) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    private var delivered = false

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        if (delivered) return
        val payload = didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstOrNull { it.type == AVMetadataObjectTypeQRCode }
            ?.stringValue ?: return
        delivered = true
        onDecoded(payload)
    }
}

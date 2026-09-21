@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.arachne.atak

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/** Scans one private invitation and hands it to the existing authenticated join flow. */
class InvitationScannerActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {
    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private var camera: Camera? = null
    private var surfaceReady = false
    private val decoding = AtomicBoolean()
    private var lastAttempt = 0L
    private var previewWidth = 0
    private var previewHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArachneStyle.initializeActivity(this)
        status = ArachneComponents.note(this, "Point the camera at an Arachne invitation QR code.").apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        preview = object : SurfaceView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val size = fitCameraPreview(MeasureSpec.getSize(widthMeasureSpec),
                    MeasureSpec.getSize(heightMeasureSpec), previewWidth, previewHeight)
                setMeasuredDimension(size.first, size.second)
            }
        }.apply { holder.addCallback(this@InvitationScannerActivity) }
        val viewport = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        val header = ArachneComponents.stack(this, ArachneStyle.GAP,
            ArachneComponents.label(this, "Scan invitation", ArachneStyle.Type.TITLE), status).apply {
                val inset = ArachneStyle.dp(this, ArachneStyle.INSET)
                setPadding(inset, inset, inset, inset)
            }
        val footer = ArachneComponents.stack(this, ArachneStyle.GAP,
            ArachneComponents.action(this, "Cancel") { finish() }).apply {
                val inset = ArachneStyle.dp(this, ArachneStyle.INSET)
                setPadding(inset, ArachneStyle.dp(this, 8), inset, ArachneStyle.dp(this, 8))
            }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ArachneStyle.background(this)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, -2))
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; openCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; closeCamera() }
    override fun onResume() { super.onResume(); openCamera() }
    override fun onPause() { closeCamera(); super.onPause() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != CAMERA_REQUEST) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else status.text = "Camera access is required to scan an invitation."
    }

    private fun openCamera() {
        if (!surfaceReady || camera != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        var opened: Camera? = null
        runCatching {
            val cameraId = backCamera()
            opened = Camera.open(cameraId)
            opened!!.also { ready ->
                val orientation = displayOrientation(cameraId)
                ready.setDisplayOrientation(orientation)
                ready.parameters = ready.parameters.apply {
                    if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in supportedFocusModes)
                        focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
                val size = ready.parameters.previewSize
                previewWidth = if (orientation % 180 == 0) size.width else size.height
                previewHeight = if (orientation % 180 == 0) size.height else size.width
                preview.requestLayout()
                ready.setPreviewDisplay(preview.holder)
                ready.setPreviewCallback(this)
                ready.startPreview()
                camera = ready
            }
        }.onFailure {
            opened?.release()
            camera = null
            status.text = "The camera could not start. Close another camera app and try again."
        }
    }

    override fun onPreviewFrame(data: ByteArray, source: Camera) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAttempt < 200) return
        if (!decoding.compareAndSet(false, true)) return
        lastAttempt = now
        val size = source.parameters.previewSize
        Thread({
            val value = decodeInvitationQr(data, size.width, size.height)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (value != null && runCatching { WorkspaceInvitation.decode(value) }.isSuccess) {
                    source.setPreviewCallback(null)
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value), this, InvitationActivity::class.java)
                        .putExtra(ArachneStyle.EXTRA_APPEARANCE, ArachneStyle.appearance.name)
                        .putExtra(ArachneStyle.EXTRA_LARGER_TEXT, ArachneStyle.largerText))
                    finish()
                } else {
                    if (value != null) status.text = "That is not an Arachne invitation. Keep scanning."
                    decoding.set(false)
                }
            }
        }, "arachne-qr-scan").start()
    }

    private fun closeCamera() {
        camera?.setPreviewCallback(null)
        camera?.stopPreview()
        camera?.release()
        camera = null
        decoding.set(false)
    }

    private fun backCamera(): Int = (0 until Camera.getNumberOfCameras()).firstOrNull { id ->
        Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }.facing == Camera.CameraInfo.CAMERA_FACING_BACK
    } ?: 0

    private fun displayOrientation(cameraId: Int): Int {
        val rotation = when (windowManager.defaultDisplay.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val info = Camera.CameraInfo().also { Camera.getCameraInfo(cameraId, it) }
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
            (360 - (info.orientation + rotation) % 360) % 360
        else (info.orientation - rotation + 360) % 360
    }

    companion object { private const val CAMERA_REQUEST = 1 }
}

/** Fit the rotated camera buffer inside the viewport without stretching or cropping a QR. */
internal fun fitCameraPreview(width: Int, height: Int, bufferWidth: Int, bufferHeight: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return width to height
    val scale = minOf(width.toDouble() / bufferWidth, height.toDouble() / bufferHeight)
    return (bufferWidth * scale).toInt().coerceAtLeast(1) to
        (bufferHeight * scale).toInt().coerceAtLeast(1)
}

internal fun decodeInvitationQr(data: ByteArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || data.size < width * height) return null
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    return runCatching { MultiFormatReader().apply { setHints(hints) }
        .decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
}

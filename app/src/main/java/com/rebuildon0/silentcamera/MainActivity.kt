package com.rebuildon0.silentcamera

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Camera screen. Uses CameraX (Camera2) and never plays MediaActionSound,
 * so photo capture and video start/stop make no sound.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    /** Frame ratio as width:height in portrait. FULL fills the screen. */
    private enum class Ratio(val label: String, val w: Int, val h: Int) {
        R3_4("3:4", 3, 4),
        R9_16("9:16", 9, 16),
        R1_1("1:1", 1, 1),
        FULL("フル", 0, 0),
    }

    private val photoRatios = listOf(Ratio.R3_4, Ratio.R9_16, Ratio.R1_1, Ratio.FULL)
    private val videoRatios = listOf(Ratio.R9_16, Ratio.R1_1, Ratio.FULL)

    private lateinit var previewView: PreviewView
    private lateinit var captureFlash: View
    private lateinit var flashButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var ratioButton: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var timerText: TextView
    private lateinit var zoomBar: LinearLayout
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var thumbnail: ImageView
    private lateinit var switchButton: ImageButton
    private lateinit var snapButton: ImageButton

    private lateinit var controller: LifecycleCameraController
    private lateinit var prefs: SharedPreferences
    private lateinit var gestureDetector: GestureDetector

    private var mode = Mode.PHOTO
    private var recording: Recording? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var snapWhileRecordingSupported = true
    private var multiTouch = false
    private var sensorWide: Boolean? = null

    private var zoomPresets: List<Float> = emptyList()
    private var zoomRange: Pair<Float, Float>? = null
    private var zoomAnimator: ValueAnimator? = null

    private val accent = Color.parseColor("#FFD54F")

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                setMicEnabled(false)
                Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureFlash = findViewById(R.id.captureFlash)
        flashButton = findViewById(R.id.flashButton)
        micButton = findViewById(R.id.micButton)
        ratioButton = findViewById(R.id.ratioButton)
        settingsButton = findViewById(R.id.settingsButton)
        timerText = findViewById(R.id.timerText)
        zoomBar = findViewById(R.id.zoomBar)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        shutterButton = findViewById(R.id.shutterButton)
        thumbnail = findViewById(R.id.thumbnail)
        switchButton = findViewById(R.id.switchButton)
        snapButton = findViewById(R.id.snapButton)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        controller = LifecycleCameraController(this)
        previewView.controller = controller
        controller.zoomState.observe(this) { updateZoomUi(it) }

        shutterButton.setOnClickListener {
            if (mode == Mode.PHOTO) takePhoto(shutterButton) else toggleRecording()
        }
        snapButton.setOnClickListener { takePhoto(snapButton) }
        modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        modeVideo.setOnClickListener { setMode(Mode.VIDEO) }
        flashButton.setOnClickListener { cycleFlash() }
        micButton.setOnClickListener { setMicEnabled(!isMicEnabled()) }
        ratioButton.setOnClickListener { cycleRatio() }
        switchButton.setOnClickListener { switchCamera() }
        thumbnail.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // Samsung-style swipes on the preview: left/right changes mode, up/down flips camera.
        // Returning false keeps CameraController's pinch-to-zoom and tap-to-focus working.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || multiTouch) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val minDistance = 80 * resources.displayMetrics.density
                if (abs(dx) > abs(dy) && abs(dx) > minDistance) {
                    setMode(if (dx < 0) Mode.VIDEO else Mode.PHOTO)
                    return true
                }
                if (abs(dy) > abs(dx) && abs(dy) > minDistance) {
                    switchCamera()
                    return true
                }
                return false
            }
        })
        previewView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
            if (event.pointerCount > 1) multiTouch = true
            gestureDetector.onTouchEvent(event)
            false
        }

        applyMode()
        updateMicButton()

        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        refreshThumbnail()
    }

    override fun onStop() {
        recording?.stop()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyRatio()
    }

    // Volume keys act as the shutter, like the Samsung camera's default.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0 && shutterButton.isEnabled) shutterButton.performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startCamera() {
        try {
            controller.bindToLifecycle(this)
        } catch (e: RuntimeException) {
            showError(R.string.camera_failed, e.message)
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showError(messageRes: Int, detail: String?) {
        Toast.makeText(this, getString(messageRes, detail ?: ""), Toast.LENGTH_LONG).show()
    }

    // ---- Photo ----

    private fun takePhoto(trigger: View) {
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaRepo.newValues("jpg", "image/jpeg"),
        ).build()

        trigger.isEnabled = false
        try {
            controller.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        trigger.isEnabled = true
                        refreshThumbnail()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        trigger.isEnabled = true
                        showError(R.string.capture_failed, exception.message)
                    }
                },
            )
            showCaptureFeedback()
        } catch (e: IllegalStateException) {
            // Camera not ready yet (e.g. tapped right after launch).
            trigger.isEnabled = true
            showError(R.string.capture_failed, e.message)
        }
    }

    /** Visual-only capture feedback (no sound). */
    private fun showCaptureFeedback() {
        captureFlash.animate().cancel()
        captureFlash.alpha = 0.6f
        captureFlash.animate().alpha(0f).setDuration(150).start()
    }

    // ---- Video ----

    // Audio is enabled only after RECORD_AUDIO is confirmed granted.
    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        recording?.let {
            it.stop()
            return
        }

        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(MediaRepo.newValues("mp4", "video/mp4")).build()

        val audio = if (isMicEnabled() && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            AudioConfig.create(true)
        } else {
            AudioConfig.AUDIO_DISABLED
        }

        shutterButton.isEnabled = false
        recording = try {
            startRecording(output, audio)
        } catch (e: IllegalStateException) {
            // Camera not ready yet (e.g. tapped right after launch).
            shutterButton.isEnabled = true
            showError(R.string.record_failed, e.message)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(output: MediaStoreOutputOptions, audio: AudioConfig): Recording =
        controller.startRecording(output, audio, ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    shutterButton.isEnabled = true
                    setRecordingUi(true)
                }
                is VideoRecordEvent.Status -> {
                    val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    timerText.text = "%02d:%02d".format(seconds / 60, seconds % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    shutterButton.isEnabled = true
                    setRecordingUi(false)
                    if (event.hasError() && event.outputResults.outputUri == android.net.Uri.EMPTY) {
                        showError(R.string.record_failed, event.cause?.message ?: event.error.toString())
                    }
                    refreshThumbnail()
                }
            }
        }

    private fun setRecordingUi(isRecording: Boolean) {
        shutterButton.setBackgroundResource(
            if (isRecording) R.drawable.shutter_recording else R.drawable.shutter_video
        )
        timerText.text = "00:00"
        timerText.visibility = if (isRecording) View.VISIBLE else View.GONE
        val idle = if (isRecording) View.INVISIBLE else View.VISIBLE
        switchButton.visibility = if (isRecording) View.GONE else View.VISIBLE
        snapButton.visibility = if (isRecording && snapWhileRecordingSupported) View.VISIBLE else View.GONE
        thumbnail.visibility = idle
        settingsButton.visibility = idle
        ratioButton.visibility = idle
        micButton.visibility = idle
        modePhoto.visibility = idle
        modeVideo.visibility = idle
        if (isRecording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- Mode ----

    private fun setMode(newMode: Mode) {
        if (recording != null || newMode == mode) return
        mode = newMode
        applyMode()
    }

    private fun applyMode() {
        val photo = mode == Mode.PHOTO
        // The flash stays off in video mode so a snapshot while recording never fires it.
        controller.imageCaptureFlashMode = if (photo) flashMode else ImageCapture.FLASH_MODE_OFF
        modePhoto.setTextColor(if (photo) accent else Color.WHITE)
        modeVideo.setTextColor(if (photo) Color.WHITE else accent)
        shutterButton.setBackgroundResource(if (photo) R.drawable.shutter_photo else R.drawable.shutter_video)
        flashButton.visibility = if (photo) View.VISIBLE else View.GONE
        micButton.visibility = if (photo) View.GONE else View.VISIBLE
        applyRatio()
        configureUseCases()
    }

    /** Video mode also binds ImageCapture so photos can be taken while recording. */
    private fun configureUseCases() {
        if (mode == Mode.PHOTO) {
            setUseCases(CameraController.IMAGE_CAPTURE)
            return
        }
        snapWhileRecordingSupported =
            setUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
        if (!snapWhileRecordingSupported) setUseCases(CameraController.VIDEO_CAPTURE)
    }

    private fun setUseCases(useCases: Int): Boolean = try {
        controller.setEnabledUseCases(useCases)
        true
    } catch (e: RuntimeException) {
        false
    }

    // ---- Aspect ratio ----

    private fun currentRatio(): Ratio {
        val options = if (mode == Mode.PHOTO) photoRatios else videoRatios
        val saved = prefs.getString(ratioKey(), null)
        return options.firstOrNull { it.name == saved } ?: options.first()
    }

    private fun ratioKey() = if (mode == Mode.PHOTO) "ratio_photo" else "ratio_video"

    private fun cycleRatio() {
        if (recording != null) return
        val options = if (mode == Mode.PHOTO) photoRatios else videoRatios
        val next = options[(options.indexOf(currentRatio()) + 1) % options.size]
        prefs.edit().putString(ratioKey(), next.name).apply()
        applyRatio()
    }

    /**
     * Sizes the preview to the chosen ratio. CameraController crops captured
     * photos and videos to the preview's viewport, so output matches the frame.
     */
    private fun applyRatio() {
        val ratio = currentRatio()
        ratioButton.text = ratio.label

        val config = resources.configuration
        val landscape = config.screenWidthDp > config.screenHeightDp
        val lp = previewView.layoutParams as ConstraintLayout.LayoutParams
        lp.dimensionRatio = when {
            ratio == Ratio.FULL -> null
            landscape -> "${ratio.h}:${ratio.w}"
            else -> "${ratio.w}:${ratio.h}"
        }
        lp.verticalBias = if (ratio == Ratio.R3_4 || ratio == Ratio.R1_1) 0.4f else 0.5f
        lp.horizontalBias = 0.5f
        previewView.layoutParams = lp

        val longSide = max(config.screenWidthDp, config.screenHeightDp).toFloat()
        val shortSide = min(config.screenWidthDp, config.screenHeightDp).coerceAtLeast(1).toFloat()
        val wide = when (ratio) {
            Ratio.R9_16 -> true
            Ratio.FULL -> longSide / shortSide > 1.55f
            else -> mode == Mode.VIDEO
        }
        // Changing selectors rebinds the camera (and would stop a recording), so only do it when needed.
        if (wide == sensorWide || recording != null) return
        sensorWide = wide
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
            .build()
        try {
            controller.previewResolutionSelector = selector
            controller.imageCaptureResolutionSelector = selector
        } catch (e: RuntimeException) {
            showError(R.string.camera_failed, e.message)
        }
    }

    // ---- Flash / camera / mic ----

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        controller.imageCaptureFlashMode = flashMode
        flashButton.setImageResource(
            when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                else -> R.drawable.ic_flash_off
            }
        )
    }

    private fun switchCamera() {
        if (recording != null) return
        val next = if (controller.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        if (runCatching { controller.hasCamera(next) }.getOrDefault(false)) {
            try {
                controller.cameraSelector = next
            } catch (e: RuntimeException) {
                showError(R.string.camera_failed, e.message)
            }
        }
    }

    private fun isMicEnabled() = prefs.getBoolean("mic", true)

    private fun setMicEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("mic", enabled).apply()
        updateMicButton()
        if (enabled && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (mode == Mode.VIDEO) {
            Toast.makeText(this, if (enabled) R.string.mic_on else R.string.mic_off, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMicButton() {
        micButton.setImageResource(if (isMicEnabled()) R.drawable.ic_mic else R.drawable.ic_mic_off)
    }

    // ---- Zoom ----

    /** Preset buttons like the Samsung camera (e.g. 0.6 / 1 / 2 / 3), limited to the camera's range. */
    private fun updateZoomUi(state: ZoomState) {
        val range = state.minZoomRatio to state.maxZoomRatio
        if (range != zoomRange) {
            zoomRange = range
            rebuildZoomPresets(state.minZoomRatio, state.maxZoomRatio)
        }
        val current = state.zoomRatio
        val active = zoomPresets.indexOfLast { it <= current + 0.01f }.coerceAtLeast(0)
        for (i in 0 until zoomBar.childCount) {
            val button = zoomBar.getChildAt(i) as TextView
            val selected = i == active
            button.text = formatZoom(if (selected) current else zoomPresets[i], selected)
            button.setTextColor(if (selected) accent else Color.WHITE)
        }
    }

    private fun rebuildZoomPresets(minZoom: Float, maxZoom: Float) {
        val presets = mutableListOf<Float>()
        if (minZoom < 0.95f) presets += minZoom
        presets += 1f
        for (z in listOf(2f, 3f)) if (z <= maxZoom + 0.01f) presets += z
        zoomPresets = presets

        zoomBar.removeAllViews()
        if (presets.size < 2) return
        val size = (40 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (preset in presets) {
            val button = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setBackgroundResource(R.drawable.round_button_bg)
                setOnClickListener { animateZoom(preset) }
            }
            zoomBar.addView(button, LinearLayout.LayoutParams(size, size).apply {
                marginStart = margin
                marginEnd = margin
            })
        }
    }

    private fun formatZoom(zoom: Float, withUnit: Boolean): String {
        val rounded = (zoom * 10).roundToInt() / 10f
        val text = if (rounded % 1f == 0f) rounded.toInt().toString()
        else if (rounded < 1f) ".${(rounded * 10).roundToInt()}"
        else rounded.toString()
        return if (withUnit) "${text}x" else text
    }

    private fun animateZoom(target: Float) {
        val from = controller.zoomState.value?.zoomRatio ?: 1f
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = 250
            addUpdateListener { controller.setZoomRatio(it.animatedValue as Float) }
            start()
        }
    }

    // ---- Thumbnail ----

    private fun refreshThumbnail() {
        val size = (56 * resources.displayMetrics.density).toInt()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                MediaRepo.loadAll(this@MainActivity).firstOrNull()
                    ?.let { MediaRepo.thumbnail(this@MainActivity, it.uri, size) }
            }
            if (bitmap != null) {
                thumbnail.setPadding(0, 0, 0, 0)
                thumbnail.imageTintList = null
                thumbnail.setImageBitmap(bitmap)
            } else {
                val pad = (12 * resources.displayMetrics.density).toInt()
                thumbnail.setPadding(pad, pad, pad, pad)
                thumbnail.setImageResource(R.drawable.ic_photo_library)
                thumbnail.imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
        }
    }
}

package com.rebuildon0.silentcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera screen. Uses CameraX (Camera2) and never plays MediaActionSound,
 * so photo capture and video start/stop make no sound.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    private lateinit var previewView: PreviewView
    private lateinit var captureFlash: View
    private lateinit var flashButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var timerText: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var shutterButton: ImageButton
    private lateinit var thumbnail: ImageView
    private lateinit var switchButton: ImageButton

    private lateinit var controller: LifecycleCameraController
    private lateinit var prefs: SharedPreferences

    private var mode = Mode.PHOTO
    private var recording: Recording? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureFlash = findViewById(R.id.captureFlash)
        flashButton = findViewById(R.id.flashButton)
        micButton = findViewById(R.id.micButton)
        settingsButton = findViewById(R.id.settingsButton)
        timerText = findViewById(R.id.timerText)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        shutterButton = findViewById(R.id.shutterButton)
        thumbnail = findViewById(R.id.thumbnail)
        switchButton = findViewById(R.id.switchButton)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        controller = LifecycleCameraController(this)
        controller.imageCaptureFlashMode = flashMode
        previewView.controller = controller

        shutterButton.setOnClickListener {
            if (mode == Mode.PHOTO) takePhoto() else toggleRecording()
        }
        modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        modeVideo.setOnClickListener { setMode(Mode.VIDEO) }
        flashButton.setOnClickListener { cycleFlash() }
        micButton.setOnClickListener { setMicEnabled(!isMicEnabled()) }
        switchButton.setOnClickListener { switchCamera() }
        thumbnail.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

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

    private fun startCamera() {
        controller.bindToLifecycle(this)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun takePhoto() {
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaRepo.newValues("jpg", "image/jpeg"),
        ).build()

        shutterButton.isEnabled = false
        showCaptureFeedback()
        controller.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    shutterButton.isEnabled = true
                    refreshThumbnail()
                }

                override fun onError(exception: ImageCaptureException) {
                    shutterButton.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.capture_failed, exception.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }

    /** Visual-only capture feedback (no sound). */
    private fun showCaptureFeedback() {
        captureFlash.animate().cancel()
        captureFlash.alpha = 0.6f
        captureFlash.animate().alpha(0f).setDuration(150).start()
    }

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
        recording = controller.startRecording(output, audio, ContextCompat.getMainExecutor(this)) { event ->
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
                        Toast.makeText(
                            this,
                            getString(R.string.record_failed, event.cause?.message ?: event.error.toString()),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    refreshThumbnail()
                }
            }
        }
    }

    private fun setRecordingUi(isRecording: Boolean) {
        shutterButton.setBackgroundResource(
            if (isRecording) R.drawable.shutter_recording else R.drawable.shutter_video
        )
        timerText.text = "00:00"
        timerText.visibility = if (isRecording) View.VISIBLE else View.GONE
        val controlsVisibility = if (isRecording) View.INVISIBLE else View.VISIBLE
        switchButton.visibility = controlsVisibility
        thumbnail.visibility = controlsVisibility
        settingsButton.visibility = controlsVisibility
        modePhoto.visibility = controlsVisibility
        modeVideo.visibility = controlsVisibility
        micButton.visibility = if (isRecording) View.INVISIBLE else View.VISIBLE
        if (isRecording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setMode(newMode: Mode) {
        if (recording != null || newMode == mode) return
        mode = newMode
        applyMode()
    }

    private fun applyMode() {
        val photo = mode == Mode.PHOTO
        controller.setEnabledUseCases(
            if (photo) CameraController.IMAGE_CAPTURE else CameraController.VIDEO_CAPTURE
        )
        val accent = Color.parseColor("#FFD54F")
        modePhoto.setTextColor(if (photo) accent else Color.WHITE)
        modeVideo.setTextColor(if (photo) Color.WHITE else accent)
        shutterButton.setBackgroundResource(if (photo) R.drawable.shutter_photo else R.drawable.shutter_video)
        flashButton.visibility = if (photo) View.VISIBLE else View.GONE
        micButton.visibility = if (photo) View.GONE else View.VISIBLE
    }

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
            controller.cameraSelector = next
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
                thumbnail.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
        }
    }
}

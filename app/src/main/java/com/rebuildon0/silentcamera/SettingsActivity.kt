package com.rebuildon0.silentcamera

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var updateButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView

    private var latest: Updater.Release? = null
    private var waitingForInstallPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        updateButton = findViewById(R.id.updateButton)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)

        updateButton.setOnClickListener {
            val release = latest
            if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
                downloadAndInstall(release)
            } else {
                checkForUpdate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForInstallPermission && Updater.canInstall(this)) {
            waitingForInstallPermission = false
            latest?.let { downloadAndInstall(it) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun checkForUpdate() {
        updateButton.isEnabled = false
        statusText.setText(R.string.update_checking)
        lifecycleScope.launch {
            try {
                val release = Updater.fetchLatest()
                latest = release
                if (release.versionCode > BuildConfig.VERSION_CODE) {
                    val notes = if (release.notes.isBlank()) "" else "\n\n${release.notes}"
                    statusText.text = getString(R.string.update_available, release.versionName) + notes
                    updateButton.setText(R.string.update_install)
                } else {
                    statusText.setText(R.string.update_latest)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusText.text = getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                updateButton.isEnabled = true
            }
        }
    }

    private fun downloadAndInstall(release: Updater.Release) {
        if (!Updater.canInstall(this)) {
            waitingForInstallPermission = true
            statusText.setText(R.string.update_allow_install)
            Updater.openInstallPermissionSettings(this)
            return
        }
        updateButton.isEnabled = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        statusText.text = getString(R.string.update_downloading, 0)
        lifecycleScope.launch {
            try {
                val apk = Updater.download(this@SettingsActivity, release) { percent ->
                    progress.progress = percent
                    statusText.text = getString(R.string.update_downloading, percent)
                }
                Updater.install(this@SettingsActivity, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusText.text = getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                progress.visibility = View.GONE
                updateButton.isEnabled = true
            }
        }
    }
}

package com.rebuildon0.silentcamera

import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Confirms and deletes media. Items the app no longer owns (e.g. after a
 * reinstall) fall back to the system delete request. Must be created before
 * the activity is started, because it registers an activity result.
 */
class MediaDeleter(
    private val activity: AppCompatActivity,
    private val onDeleted: () -> Unit,
) {
    private val systemRequest =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            onDeleted()
        }

    fun confirmAndDelete(items: List<MediaItem>) {
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(activity)
            .setMessage(activity.getString(R.string.delete_confirm, items.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> delete(items) }
            .show()
    }

    private fun delete(items: List<MediaItem>) {
        activity.lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) { MediaRepo.delete(activity, items) }
            if (failed.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sender = MediaStore.createDeleteRequest(
                    activity.contentResolver,
                    failed.map { it.uri },
                ).intentSender
                systemRequest.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                if (failed.isNotEmpty()) {
                    android.widget.Toast.makeText(activity, R.string.delete_failed, android.widget.Toast.LENGTH_LONG).show()
                }
                onDeleted()
            }
        }
    }
}

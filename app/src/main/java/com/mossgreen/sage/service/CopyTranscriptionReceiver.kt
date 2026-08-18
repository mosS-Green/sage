package com.mossgreen.sage.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Handles the "Copy" action button tap on transcription notifications.
 */
class CopyTranscriptionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY = "com.mossgreen.sage.COPY_TRANSCRIPTION"
        const val EXTRA_TEXT = "transcription_text"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

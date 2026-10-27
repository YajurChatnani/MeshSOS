package com.example.meshsos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.meshsos.MeshSosApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SosActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == SosNotificationHelper.ACTION_ACKNOWLEDGE_ALERT) {
            val messageId = intent.getStringExtra(SosNotificationHelper.EXTRA_MESSAGE_ID) ?: return
            Log.d("SosActionReceiver", "Acknowledging emergency alert from notification action: messageId=$messageId")

            val app = context.applicationContext as? MeshSosApplication ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.repository.acknowledgeMessage(messageId)
                    SosNotificationHelper.dismissNotification(context, messageId)
                } catch (e: Exception) {
                    Log.e("SosActionReceiver", "Failed to acknowledge message: ${e.message}")
                }
            }
        }
    }
}


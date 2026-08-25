package com.example.messenger.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.messenger.MainActivity
import com.example.messenger.data.local.PreferencesManager

/**
 * На Android 8+ звук и вибрация уведомления жёстко привязаны к NotificationChannel
 * в момент его первого создания — простое изменение свойств канала после этого
 * системой игнорируется. Поэтому единственный способ применить новый звук/вибрацию —
 * удалить канал и создать заново (applyChannelSettings ниже).
 */
class NotificationHelper(
    private val context: Context,
    private val preferences: PreferencesManager
) {

    companion object {
        const val CHANNEL_ID = "messages"
        private const val CHANNEL_NAME = "Сообщения"
    }

    fun applyChannelSettings(soundUri: Uri?, vibrationEnabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.deleteNotificationChannel(CHANNEL_ID)

        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            if (soundUri != null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            enableVibration(vibrationEnabled)
            if (vibrationEnabled) vibrationPattern = longArrayOf(0, 250, 250, 250)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Локальное уведомление о новом сообщении — только пока приложение свёрнуто
     * (см. ChatViewModel) и чат не замьючен (см. PreferencesManager.mutedChatIds).
     */
    suspend fun showMessageNotification(chatId: String, title: String, body: String) {
        if (preferences.isChatMuted(chatId)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(chatId.hashCode(), notification)
    }
}

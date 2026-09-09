package it.paolo.scadenze

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Appuntamento"
        val kind = intent.getIntExtra("kind", 2)
        val appointmentMillis = intent.getLongExtra("appointmentMillis", 0L)
        val appointmentId = intent.getStringExtra("appointmentId") ?: ""

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Promemoria appuntamenti",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avvisi per le scadenze in arrivo"
            enableLights(true)
            lightColor = BRAND_COLOR
        }
        manager.createNotificationChannel(channel)

        val timeText = if (appointmentMillis > 0) {
            SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(appointmentMillis))
        } else null

        val headline = if (kind == 1) "Domani: $title" else "Tra un'ora: $title"
        val detail = when {
            kind == 1 && timeText != null -> "Domani alle $timeText"
            kind == 1 -> "In programma domani"
            timeText != null -> "Oggi alle $timeText"
            else -> "Tra un'ora"
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            appointmentId.hashCode() + kind,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(headline)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        manager.notify(appointmentId.hashCode() + kind, notification)
    }

    companion object {
        private const val CHANNEL_ID = "appuntamenti"
        private val BRAND_COLOR = Color.parseColor("#3F51B5")
    }
}

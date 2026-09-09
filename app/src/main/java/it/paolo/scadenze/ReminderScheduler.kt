package it.paolo.scadenze

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object ReminderScheduler {

    private const val DAY_BEFORE = 1
    private const val HOUR_BEFORE = 2

    fun schedule(
        context: Context,
        appointment: Appointment,
        dayBeforeEnabled: Boolean = true,
        hourBeforeEnabled: Boolean = true
    ) {
        cancel(context, appointment.id)

        val whenAppointment = appointment.appointmentMillis
        val dayBefore = whenAppointment - 24 * 60 * 60 * 1000L
        val hourBefore = whenAppointment - 60 * 60 * 1000L

        if (dayBeforeEnabled && dayBefore > System.currentTimeMillis()) {
            setAlarm(context, appointment, dayBefore, DAY_BEFORE)
        }
        if (hourBeforeEnabled && hourBefore > System.currentTimeMillis()) {
            setAlarm(context, appointment, hourBefore, HOUR_BEFORE)
        }
    }

    fun cancel(context: Context, id: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        for (kind in intArrayOf(DAY_BEFORE, HOUR_BEFORE)) {
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra("appointmentId", id)
                .putExtra("kind", kind)
            val pending = PendingIntent.getBroadcast(
                context,
                id.hashCode() + kind,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pending)
        }
    }

    private fun setAlarm(
        context: Context,
        appointment: Appointment,
        triggerAt: Long,
        kind: Int
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("appointmentId", appointment.id)
            putExtra("title", appointment.title)
            putExtra("appointmentMillis", appointment.appointmentMillis)
            putExtra("kind", kind)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            appointment.id.hashCode() + kind,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pending
        )
    }
}

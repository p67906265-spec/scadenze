package it.paolo.scadenze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("appointments")
            .whereEqualTo("userId", uid)
            .get()
            .addOnSuccessListener { result ->
                for (doc in result.documents) {
                    val appointment = Appointment(
                        id = doc.id,
                        userId = doc.getString("userId") ?: "",
                        title = doc.getString("title") ?: "",
                        dateMillis = doc.getLong("dateMillis") ?: 0L,
                        timeMillis = doc.getLong("timeMillis") ?: 0L
                    )
                    if (appointment.appointmentMillis > System.currentTimeMillis()) {
                        ReminderScheduler.schedule(context, appointment)
                    }
                }
            }
    }
}

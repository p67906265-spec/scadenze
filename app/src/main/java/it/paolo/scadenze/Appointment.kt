package it.paolo.scadenze

data class Appointment(
    val id: String = "",
    val userId: String = "",
    val title: String = "",
    val dateMillis: Long = 0L,
    val timeMillis: Long = 0L,
    val categoryName: String = "",
    val categoryColor: Long = 0L
) {
    val appointmentMillis: Long
        get() {
            val d = java.util.Calendar.getInstance().apply { timeInMillis = dateMillis }
            val t = java.util.Calendar.getInstance().apply { timeInMillis = timeMillis }
            return java.util.Calendar.getInstance().apply {
                set(
                    d.get(java.util.Calendar.YEAR),
                    d.get(java.util.Calendar.MONTH),
                    d.get(java.util.Calendar.DAY_OF_MONTH),
                    t.get(java.util.Calendar.HOUR_OF_DAY),
                    t.get(java.util.Calendar.MINUTE),
                    0
                )
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
}

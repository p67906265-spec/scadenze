@file:OptIn(ExperimentalMaterial3Api::class)

package it.paolo.scadenze

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        setContent {
            var currentUser by remember { mutableStateOf(auth.currentUser) }
            var themeMode by remember { mutableStateOf("system") }

            DisposableEffect(Unit) {
                val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                    currentUser = firebaseAuth.currentUser
                }
                auth.addAuthStateListener(listener)
                onDispose { auth.removeAuthStateListener(listener) }
            }

            LaunchedEffect(currentUser) {
                if (currentUser == null) themeMode = "system"
            }

            val systemDark = isSystemInDarkThemeCompat()
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            val dynamicScheme = if (Build.VERSION.SDK_INT >= 31) {
                if (darkTheme) dynamicDarkColorSchemeCompat(this) else dynamicLightColorSchemeCompat(this)
            } else null
            val colorScheme = dynamicScheme ?: if (darkTheme) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (currentUser == null) {
                        LoginScreen(auth = auth, onSignedIn = { })
                    } else {
                        ScadenzeApp(
                            db = db,
                            auth = auth,
                            user = currentUser!!,
                            themeMode = themeMode,
                            onThemeModeChanged = { themeMode = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun isSystemInDarkThemeCompat(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

@Composable
private fun dynamicDarkColorSchemeCompat(context: android.content.Context): ColorScheme? =
    try {
        dynamicDarkColorScheme(context)
    } catch (e: Throwable) {
        null
    }

@Composable
private fun dynamicLightColorSchemeCompat(context: android.content.Context): ColorScheme? =
    try {
        dynamicLightColorScheme(context)
    } catch (e: Throwable) {
        null
    }

private data class Category(val name: String, val color: Long, val notify: Boolean = true)

@Composable
private fun ScadenzeApp(
    db: FirebaseFirestore,
    auth: FirebaseAuth,
    user: FirebaseUser,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit
) {
    val context = LocalContext.current
    var appointments by remember { mutableStateOf<List<Appointment>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Appointment?>(null) }
    var pendingDelete by remember { mutableStateOf<Appointment?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var dayBeforeEnabled by remember { mutableStateOf(true) }
    var hourBeforeEnabled by remember { mutableStateOf(true) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }

    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    var canScheduleExact by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 31 || alarmManager?.canScheduleExactAlarms() == true
        )
    }

    // Ricontrolla il permesso "allarmi esatti" ogni volta che la schermata torna in primo piano
    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                canScheduleExact = Build.VERSION.SDK_INT < 31 || alarmManager?.canScheduleExactAlarms() == true
            }
        }
        val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // Preferenze sugli avvisi, categorie e tema: sincronizzate su Firestore così valgono su tutti i dispositivi
    DisposableEffect(user.uid) {
        val registration = db.collection("settings").document(user.uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    dayBeforeEnabled = snapshot.getBoolean("dayBefore") ?: true
                    hourBeforeEnabled = snapshot.getBoolean("hourBefore") ?: true
                    onThemeModeChanged(snapshot.getString("themeMode") ?: "system")
                    val rawCategories = snapshot.get("categories") as? List<*>
                    categories = rawCategories?.mapNotNull { item ->
                        val map = item as? Map<*, *> ?: return@mapNotNull null
                        val name = map["name"] as? String ?: return@mapNotNull null
                        val color = (map["color"] as? Number)?.toLong() ?: return@mapNotNull null
                        val notify = map["notify"] as? Boolean ?: true
                        Category(name, color, notify)
                    } ?: emptyList()
                }
            }
        onDispose { registration.remove() }
    }

    DisposableEffect(user.uid) {
        // Tiene traccia degli id già visti per poter cancellare gli allarmi locali
        // quando un appuntamento viene eliminato (anche da un altro dispositivo).
        var knownIds = emptySet<String>()

        val registration = db.collection("appointments")
            .whereEqualTo("userId", user.uid)
            .addSnapshotListener { snapshot, exception ->
                if (exception != null) {
                    loadError = "Impossibile caricare i dati: ${exception.localizedMessage}"
                    return@addSnapshotListener
                }
                loadError = null

                val updated = snapshot?.documents?.map { d ->
                    Appointment(
                        id = d.id,
                        userId = d.getString("userId") ?: "",
                        title = d.getString("title") ?: "",
                        dateMillis = d.getLong("dateMillis") ?: 0L,
                        timeMillis = d.getLong("timeMillis") ?: 0L,
                        categoryName = d.getString("categoryName") ?: "",
                        categoryColor = d.getLong("categoryColor") ?: 0L
                    )
                }?.sortedBy { it.appointmentMillis } ?: emptyList()

                val newIds = updated.map { it.id }.toSet()
                val removedIds = knownIds - newIds
                removedIds.forEach { removedId ->
                    ReminderScheduler.cancel(context, removedId)
                }
                knownIds = newIds

                appointments = updated
            }

        onDispose { registration.remove() }
    }

    // Riprogramma tutti gli avvisi quando cambia la lista, le preferenze o le categorie silenziate
    LaunchedEffect(appointments, dayBeforeEnabled, hourBeforeEnabled, categories) {
        appointments.forEach { appointment ->
            val categoryMuted = appointment.categoryColor != 0L &&
                categories.firstOrNull { it.name == appointment.categoryName }?.notify == false
            if (categoryMuted) {
                ReminderScheduler.cancel(context, appointment.id)
            } else if (appointment.appointmentMillis > System.currentTimeMillis()) {
                ReminderScheduler.schedule(context, appointment, dayBeforeEnabled, hourBeforeEnabled)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                count = appointments.size,
                syncing = syncing,
                onSync = {
                    syncing = true
                    db.collection("appointments")
                        .whereEqualTo("userId", user.uid)
                        .get(Source.SERVER)
                        .addOnSuccessListener { snapshot ->
                            syncing = false
                            syncMessage = "Sincronizzazione avvenuta. Trovati ${snapshot.size()} appuntamenti per questo account sul server."
                        }
                        .addOnFailureListener { e ->
                            syncing = false
                            syncMessage = "Sincronizzazione non riuscita: ${e.localizedMessage}"
                        }
                },
                onSettings = { showSettings = true },
                onLogout = { auth.signOut() }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nuova", fontWeight = FontWeight.SemiBold) },
                shape = RoundedCornerShape(20.dp)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            user.email?.let { email ->
                Text(
                    "Account: $email",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 0.dp)
                )
            }
            AnimatedVisibility(visible = !canScheduleExact) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Per ricevere gli avvisi puntuali, consenti a Scadenze di programmare allarmi esatti.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Apri impostazioni")
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = loadError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        loadError ?: "",
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (appointments.isEmpty() && loadError == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(76.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.DateRange,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Nessun appuntamento",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tocca \"Nuova\" per aggiungerne uno",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(appointments, key = { it.id }) { appointment ->
                        AppointmentCard(
                            appointment = appointment,
                            onClick = { editing = appointment },
                            onDelete = { pendingDelete = appointment }
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AppointmentDialog(
            initial = null,
            categories = categories,
            onDismiss = { showAdd = false },
            onSave = { title, date, time, categoryName, categoryColor ->
                val data = hashMapOf(
                    "userId" to user.uid,
                    "title" to title,
                    "dateMillis" to date,
                    "timeMillis" to time,
                    "appointmentMillis" to combineDateTime(date, time),
                    "categoryName" to categoryName,
                    "categoryColor" to categoryColor
                )
                // Chiude subito il pannello: la scrittura prosegue in background
                // e la lista si aggiorna da sola grazie al listener Firestore.
                showAdd = false
                db.collection("appointments").add(data).addOnFailureListener { e ->
                    saveError = "Salvataggio non riuscito: ${e.localizedMessage}"
                }
            }
        )
    }

    editing?.let { appointment ->
        AppointmentDialog(
            initial = appointment,
            categories = categories,
            onDismiss = { editing = null },
            onSave = { title, date, time, categoryName, categoryColor ->
                val data = hashMapOf(
                    "userId" to user.uid,
                    "title" to title,
                    "dateMillis" to date,
                    "timeMillis" to time,
                    "appointmentMillis" to combineDateTime(date, time),
                    "categoryName" to categoryName,
                    "categoryColor" to categoryColor
                )
                editing = null
                db.collection("appointments").document(appointment.id).set(data)
                    .addOnFailureListener { e ->
                        saveError = "Modifica non riuscita: ${e.localizedMessage}"
                    }
            }
        )
    }

    pendingDelete?.let { appointment ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Eliminare l'appuntamento?") },
            text = { Text("\"${appointment.title}\" verrà eliminato definitivamente.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        ReminderScheduler.cancel(context, appointment.id)
                        db.collection("appointments").document(appointment.id).delete()
                            .addOnFailureListener { e ->
                                saveError = "Eliminazione non riuscita: ${e.localizedMessage}"
                            }
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Annulla") }
            }
        )
    }

    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Si è verificato un problema") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { saveError = null }) { Text("OK") }
            }
        )
    }

    syncMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { syncMessage = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Sincronizzazione") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { syncMessage = null }) { Text("OK") }
            }
        )
    }

    if (showSettings) {
        val saveSettings: (Boolean, Boolean, List<Category>, String) -> Unit = { day, hour, cats, mode ->
            val data = mapOf(
                "dayBefore" to day,
                "hourBefore" to hour,
                "categories" to cats.map { mapOf("name" to it.name, "color" to it.color, "notify" to it.notify) },
                "themeMode" to mode
            )
            db.collection("settings").document(user.uid).set(data)
                .addOnFailureListener { e ->
                    saveError = "Impostazione non salvata: ${e.localizedMessage}"
                }
        }
        SettingsDialog(
            dayBeforeEnabled = dayBeforeEnabled,
            hourBeforeEnabled = hourBeforeEnabled,
            categories = categories,
            themeMode = themeMode,
            onDayBeforeChanged = { enabled ->
                dayBeforeEnabled = enabled
                saveSettings(enabled, hourBeforeEnabled, categories, themeMode)
            },
            onHourBeforeChanged = { enabled ->
                hourBeforeEnabled = enabled
                saveSettings(dayBeforeEnabled, enabled, categories, themeMode)
            },
            onAddCategory = { newCategory ->
                val updated = categories + newCategory
                categories = updated
                saveSettings(dayBeforeEnabled, hourBeforeEnabled, updated, themeMode)
            },
            onRemoveCategory = { toRemove ->
                val updated = categories.filterNot { it.name == toRemove.name }
                categories = updated
                saveSettings(dayBeforeEnabled, hourBeforeEnabled, updated, themeMode)
            },
            onToggleCategoryNotify = { toggled ->
                val updated = categories.map {
                    if (it.name == toggled.name) it.copy(notify = !it.notify) else it
                }
                categories = updated
                saveSettings(dayBeforeEnabled, hourBeforeEnabled, updated, themeMode)
            },
            onThemeModeChanged = { mode ->
                onThemeModeChanged(mode)
                saveSettings(dayBeforeEnabled, hourBeforeEnabled, categories, mode)
            },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun AppHeader(count: Int, syncing: Boolean, onSync: () -> Unit, onSettings: () -> Unit, onLogout: () -> Unit) {
    val subtitle = when (count) {
        0 -> "Nessuna scadenza in programma"
        1 -> "1 scadenza in programma"
        else -> "$count scadenze in programma"
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Scadenze",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Impostazioni",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            IconButton(onClick = onSync, enabled = !syncing) {
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Sincronizza",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            IconButton(onClick = onLogout) {
                Icon(
                    Icons.Default.ExitToApp,
                    contentDescription = "Esci",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AppointmentCard(
    appointment: Appointment,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val date = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date(appointment.appointmentMillis))
    val time = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(appointment.appointmentMillis))

    val now = System.currentTimeMillis()
    val diffMillis = appointment.appointmentMillis - now
    val diffDays = TimeUnit.MILLISECONDS.toDays(diffMillis)
    val isOverdue = diffMillis < 0
    val isToday = !isOverdue && diffDays == 0L

    val (badgeText, urgencyColor, onUrgencyColor) = when {
        isOverdue -> Triple("Scaduto", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        isToday -> Triple("Oggi", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        diffDays == 1L -> Triple("Domani", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
        else -> Triple("Tra $diffDays giorni", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surface)
    }
    val urgencyContainer = when {
        isOverdue -> MaterialTheme.colorScheme.errorContainer
        isToday -> MaterialTheme.colorScheme.primaryContainer
        diffDays == 1L -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onUrgencyContainer = when {
        isOverdue -> MaterialTheme.colorScheme.onErrorContainer
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        diffDays == 1L -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Se è stata assegnata una persona/categoria, il suo colore prende il posto
    // del colore di urgenza sulla card (fascia, icona e badge principale).
    val hasCategory = appointment.categoryColor != 0L
    val categoryColor = if (hasCategory) Color(appointment.categoryColor) else null
    val onCategoryColor = if (categoryColor != null) {
        if (categoryColor.luminance() > 0.5f) Color.Black else Color.White
    } else null

    val accent = categoryColor ?: urgencyColor
    val cardBackground = if (categoryColor != null) {
        categoryColor.copy(alpha = 0.20f).compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val accentContainer = if (categoryColor != null) {
        categoryColor.copy(alpha = 0.45f).compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        urgencyContainer
    }
    val onAccentContainer = categoryColor ?: onUrgencyContainer

    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = cardBackground,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp
    ) {
        Row(Modifier.fillMaxWidth()) {
            // fascia colorata a sinistra: persona/categoria se assegnata, altrimenti urgenza
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(accent)
            )

            Row(
                Modifier.weight(1f).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = accentContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            tint = onAccentContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        appointment.title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$date  •  $time",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hasCategory && categoryColor != null && onCategoryColor != null) {
                            Surface(color = categoryColor, shape = RoundedCornerShape(50)) {
                                Text(
                                    appointment.categoryName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = onCategoryColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    badgeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        } else {
                            Surface(color = urgencyColor, shape = RoundedCornerShape(50)) {
                                Text(
                                    badgeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = onUrgencyColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
                FilledTonalIconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Elimina")
                }
            }
        }
    }
}

@Composable
private fun AppointmentDialog(
    initial: Appointment?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (String, Long, Long, String, Long) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var date by remember {
        mutableLongStateOf(initial?.dateMillis ?: System.currentTimeMillis())
    }
    var time by remember {
        mutableLongStateOf(initial?.timeMillis ?: System.currentTimeMillis())
    }
    var selectedCategory by remember {
        mutableStateOf(
            if (initial != null && initial.categoryColor != 0L) {
                Category(initial.categoryName, initial.categoryColor)
            } else null
        )
    }

    val dateText = SimpleDateFormat("dd/MM/yy", Locale.ITALY).format(Date(date))
    val timeText = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(time))

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (initial == null) "Nuovo appuntamento" else "Modifica appuntamento",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Appuntamento") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = date }
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    date = Calendar.getInstance().apply {
                                        set(y, m, d, 0, 0, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(dateText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
                    }

                    OutlinedButton(
                        onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = time }
                            TimePickerDialog(
                                context,
                                { _, h, m ->
                                    time = Calendar.getInstance().apply {
                                        set(1970, 0, 1, h, m, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                },
                                c.get(Calendar.HOUR_OF_DAY),
                                c.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Text(timeText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (categories.isNotEmpty()) {
                    Text(
                        "Persona/categoria (facoltativo)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("Nessuna") }
                            )
                        }
                        items(categories) { category ->
                            val color = Color(category.color)
                            val isSelected = selectedCategory?.name == category.name
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedCategory = category },
                                label = { Text(category.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = color,
                                    selectedLabelColor = if (color.luminance() > 0.5f) Color.Black else Color.White
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = color
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Text(
                    "Avvisi automatici: giorno prima e un'ora prima.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = title.isNotBlank(),
                        onClick = {
                            onSave(
                                title.trim(),
                                date,
                                time,
                                selectedCategory?.name ?: "",
                                selectedCategory?.color ?: 0L
                            )
                        },
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Salva") }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    dayBeforeEnabled: Boolean,
    hourBeforeEnabled: Boolean,
    categories: List<Category>,
    themeMode: String,
    onDayBeforeChanged: (Boolean) -> Unit,
    onHourBeforeChanged: (Boolean) -> Unit,
    onAddCategory: (Category) -> Unit,
    onRemoveCategory: (Category) -> Unit,
    onToggleCategoryNotify: (Category) -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newCategoryName by remember { mutableStateOf("") }
    var newCategoryColor by remember { mutableStateOf(CATEGORY_PALETTE.first()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Impostazioni",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Tema",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeOptionChip(
                        label = "Automatico",
                        selected = themeMode == "system",
                        onClick = { onThemeModeChanged("system") },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOptionChip(
                        label = "Chiaro",
                        selected = themeMode == "light",
                        onClick = { onThemeModeChanged("light") },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOptionChip(
                        label = "Scuro",
                        selected = themeMode == "dark",
                        onClick = { onThemeModeChanged("dark") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Avvisi",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Scegli quando ricevere una notifica prima di ogni appuntamento. Vale su tutti i dispositivi collegati a questo account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))

                SettingsRow(
                    title = "Un giorno prima",
                    checked = dayBeforeEnabled,
                    onCheckedChange = onDayBeforeChanged
                )
                Spacer(Modifier.height(10.dp))
                SettingsRow(
                    title = "Un'ora prima",
                    checked = hourBeforeEnabled,
                    onCheckedChange = onHourBeforeChanged
                )

                Spacer(Modifier.height(28.dp))
                Text(
                    "Persone e categorie",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Crea un nome con un colore (es. \"Mamma\", \"Lavoro\") da assegnare alle scadenze: la card prenderà quel colore. Tocca un nome per attivare o silenziare i suoi avvisi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))

                categories.forEach { category ->
                    val color = Color(category.color)
                    val rowBackground = if (category.notify) {
                        color.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                    Surface(
                        color = rowBackground,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onToggleCategoryNotify(category) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp, 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (category.notify) {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, color, CircleShape)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(category.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (category.notify) "Avvisi attivi" else "Avvisi silenziati",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            IconButton(onClick = { onRemoveCategory(category) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Rimuovi ${category.name}",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(if (categories.isEmpty()) 0.dp else 8.dp))

                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("Nuovo nome") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CATEGORY_PALETTE) { colorLong ->
                        val color = Color(colorLong)
                        val isSelected = newCategoryColor == colorLong
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    } else Modifier
                                )
                                .clickable { newCategoryColor = colorLong }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    enabled = newCategoryName.isNotBlank(),
                    onClick = {
                        onAddCategory(Category(newCategoryName.trim(), newCategoryColor))
                        newCategoryName = ""
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aggiungi")
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Fatto") }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Paolo Free 1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private val CATEGORY_PALETTE: List<Long> = listOf(
    0xFF1E88E5L, // blu
    0xFFE53935L, // rosso
    0xFF43A047L, // verde
    0xFFFB8C00L, // arancione
    0xFF8E24AAL, // viola
    0xFF00897BL, // verde acqua
    0xFFD81B60L, // rosa
    0xFF6D4C41L, // marrone
    0xFF3949ABL, // indaco
    0xFF546E7AL  // grigio-blu
)

@Composable
private fun ThemeOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun SettingsRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun combineDateTime(dateMillis: Long, timeMillis: Long): Long {
    val d = Calendar.getInstance().apply { timeInMillis = dateMillis }
    val t = Calendar.getInstance().apply { timeInMillis = timeMillis }
    return Calendar.getInstance().apply {
        set(
            d.get(Calendar.YEAR),
            d.get(Calendar.MONTH),
            d.get(Calendar.DAY_OF_MONTH),
            t.get(Calendar.HOUR_OF_DAY),
            t.get(Calendar.MINUTE),
            0
        )
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

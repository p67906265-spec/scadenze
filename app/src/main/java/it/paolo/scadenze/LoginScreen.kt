package it.paolo.scadenze

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

@Composable
fun LoginScreen(auth: FirebaseAuth, onSignedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var resetLoading by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                "Scadenze",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (isRegister) "Crea un account per sincronizzare i tuoi appuntamenti"
                else "Accedi per vedere i tuoi appuntamenti su tutti i dispositivi",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null; info = null },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null; info = null },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            info?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                enabled = !loading && email.isNotBlank() && password.length >= 6,
                onClick = {
                    loading = true
                    error = null
                    info = null
                    val task = if (isRegister) {
                        auth.createUserWithEmailAndPassword(email.trim(), password)
                    } else {
                        auth.signInWithEmailAndPassword(email.trim(), password)
                    }
                    task.addOnSuccessListener {
                        loading = false
                        onSignedIn()
                    }.addOnFailureListener { e ->
                        loading = false
                        error = friendlyAuthError(e, isRegister)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (loading) "Attendere..." else if (isRegister) "Registrati" else "Accedi")
            }

            if (!isRegister) {
                TextButton(
                    enabled = !resetLoading && email.isNotBlank(),
                    onClick = {
                        resetLoading = true
                        error = null
                        info = null
                        auth.sendPasswordResetEmail(email.trim())
                            .addOnSuccessListener {
                                resetLoading = false
                                info = "Ti abbiamo inviato un'email a ${email.trim()} con il link per reimpostare la password."
                            }
                            .addOnFailureListener { e ->
                                resetLoading = false
                                error = friendlyAuthError(e, isRegister = false)
                            }
                    }
                ) {
                    Text(if (resetLoading) "Invio in corso..." else "Password dimenticata?")
                }
            }

            TextButton(
                onClick = { isRegister = !isRegister; error = null; info = null },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (isRegister) "Hai già un account? Accedi"
                    else "Non hai un account? Registrati"
                )
            }

            Text(
                "La password deve avere almeno 6 caratteri. Usa lo stesso account su tutti i dispositivi per sincronizzare gli appuntamenti.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * Firebase, per motivi di sicurezza, non può mai inviare via email la password esistente
 * (viene salvata solo in forma cifrata): "Password dimenticata" invia invece un'email con
 * un link che permette di sceglierne una nuova.
 */
private fun friendlyAuthError(e: Exception, isRegister: Boolean): String = when (e) {
    is FirebaseAuthInvalidCredentialsException ->
        if (isRegister) "Email non valida." else "Email o password non corretti."
    is FirebaseAuthInvalidUserException ->
        "Non esiste un account con questa email."
    is FirebaseAuthUserCollisionException ->
        "Esiste già un account con questa email. Prova ad accedere."
    is FirebaseAuthWeakPasswordException ->
        "Password troppo debole: usane una più lunga."
    else ->
        e.localizedMessage ?: "Si è verificato un errore. Riprova."
}

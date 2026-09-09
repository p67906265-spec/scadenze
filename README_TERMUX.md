# Scadenze

App Android semplice per ricordare gli appuntamenti.

## Funzioni

- Data
- Appuntamento
- Ora appuntamento
- Salvataggio su Firebase Firestore
- Sincronizzazione tra dispositivi con lo stesso account Firebase
- Avviso il giorno prima
- Avviso un'ora prima dell'appuntamento
- Modifica ed eliminazione degli appuntamenti

## Prima compilazione

1. Crea/configura un progetto Firebase.
2. Aggiungi un'app Android con package:
   `it.paolo.scadenze`
3. Scarica `google-services.json`.
4. Copialo in:
   `app/google-services.json`

Il file `google-services.json` non è incluso nello ZIP per sicurezza.

## Firestore

Nel progetto Firebase:

1. Abilita **Authentication → Sign-in method → Email/Password**.
2. Crea il **Firestore Database**.
3. Imposta queste regole di sicurezza (Firestore → Regole), che permettono a ogni utente di leggere/scrivere solo i propri appuntamenti:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /appointments/{document} {
      allow read, update, delete: if request.auth != null
        && request.auth.uid == resource.data.userId;
      allow create: if request.auth != null
        && request.auth.uid == request.resource.data.userId;
    }
    match /settings/{userId} {
      allow read, write: if request.auth != null
        && request.auth.uid == userId;
    }
  }
}
```

L'app ora richiede un accesso con email e password (creato al primo avvio con "Registrati"). Usa lo stesso account su ogni dispositivo per sincronizzare gli appuntamenti: l'accesso anonimo è stato rimosso perché generava un utente diverso per ogni installazione, impedendo la sincronizzazione reale e lasciando i dati di tutti gli utenti visibili a chiunque con le vecchie regole aperte.

## Termux

Dopo aver scaricato lo ZIP del progetto (es. `scadenze-main-v1.zip`) nella cartella condivisa del telefono:

```
cd ~
unzip -o ~/storage/shared/Scadenze/scadenze-main-v1.zip -d ~/scadenze-build
cd ~/scadenze-build/scadenze-main
cp ~/storage/shared/Scadenze/google-services.json app/google-services.json
chmod +x gradlew
./gradlew assembleDebug
```

Nota: lo ZIP contiene una sottocartella `scadenze-main/`, quindi dopo l'estrazione il progetto si trova in `~/scadenze-build/scadenze-main`, non direttamente in `~/scadenze-build`. Adatta i percorsi sopra al nome/percorso reale della cartella dove hai scaricato lo ZIP sul tuo telefono.

Se vedi l'errore `./gradlew: No such file or directory`, significa che ti trovi nella cartella sbagliata (senza il file `gradlew`) oppure che lo ZIP che hai estratto non includeva ancora il wrapper Gradle — usa uno ZIP aggiornato che lo contenga (cartella `gradle/wrapper/` con `gradle-wrapper.jar` e `gradle-wrapper.properties`, più i file `gradlew` e `gradlew.bat` nella radice del progetto).

L'APK sarà in:

`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

Il workflow `.github/workflows/build.yml` prepara automaticamente il progetto.

Per la build GitHub serve il file `google-services.json`. È possibile aggiungerlo localmente prima del push oppure configurare in seguito un secret GitHub per generarlo durante la build.

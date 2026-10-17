# Privacy Policy & Play Store Data Safety: Homey for Wear OS (Homey per Wear OS)

*Ultimo aggiornamento / Last updated: 17 Ottobre 2026*

Questo documento è suddiviso in tre sezioni:
1. **Guida alla Scheda "Sicurezza dei Dati" (Google Play Data Safety)**: Istruzioni puntuali per rispondere al questionario obbligatorio di Google Play Console.
2. **Informativa sulla Privacy (Versione Italiana)**: Testo formale completo conforme a GDPR e linee guida di Google Play.
3. **Privacy Policy (English Version)**: Full formal policy compliant with global standards and Google Play Developer Policies.

---

# PARTE 1: Guida alla Scheda "Sicurezza dei Dati" (Google Play Data Safety)

Quando compili il modulo **"Sicurezza dei dati"** su Google Play Console (sezione *Contenuto dell'app* -> *Sicurezza dei dati*), seleziona le seguenti risposte:

### 1. Panoramica sulla raccolta e condivisione dei dati
* **L'app raccoglie o condivide uno qualsiasi dei tipi di dati degli utenti richiesti?**
  👉 **NO** *(L'app non raccoglie dati personali dell'utente da trasmettere allo sviluppatore o a terzi).*
* **Tutti i dati dell'utente raccolti dall'app vengono criptati durante il trasferimento?**
  👉 **SÌ** *(Tutte le comunicazioni tra l'orologio e l'hub Homey / Athom Cloud avvengono tramite canali crittografati TLS/HTTPS).*
* **Fornisci un modo agli utenti per richiedere l'eliminazione dei loro dati?**
  👉 **SÌ** *(Disconnettendo l'hub dalle Impostazioni dell'app o disinstallando l'app, tutti i token e i dati memorizzati in locale vengono eliminati istantaneamente e definitivamente).*

### 2. Categorie di dati specifiche (Verifica dettagliata)
* **Posizione (Location)**: **NO** (Nessuna raccolta di posizione approssimativa o precisa).
* **Informazioni personali (Personal info)**: **NO** (Nessun nome, indirizzo email o identificativo utente viene raccolto o salvato dallo sviluppatore).
* **Informazioni finanziarie (Financial info)**: **NO**.
* **Salute e fitness (Health and fitness)**: **NO**.
* **Messaggi, Foto, Video, Audio**: **NO**.
* **File e documenti**: **NO**.
* **Calendario, Contatti**: **NO**.
* **Attività dell'app (App activity)**: **NO** (Nessun dato di navigazione, interazione o clic viene tracciato).
* **Navigazione web (Web browsing)**: **NO**.
* **Diagnostica e prestazioni dell'app**: **NO** (Non sono integrati SDK di terze parti come Crashlytics, Sentry o Firebase).
* **ID dispositivo o altri ID (Device or other IDs)**: **NO** (Nessun ID pubblicitario o ID univoco del dispositivo viene raccolto o associato all'utente dallo sviluppatore).

---

# PARTE 2: Informativa sulla Privacy (Italiano)

## 1. Titolare del Trattamento
L'applicazione **Homey per Wear OS** (nota anche come **Homey for Wear OS** / **HomeyWatch**) è sviluppata come software indipendente di terze parti per smartwatch Wear OS.
Per qualsiasi chiarimento in merito alla privacy e al trattamento dei dati, è possibile contattare lo sviluppatore tramite i canali ufficiali indicati nella scheda del Play Store.

## 2. Tipologia di Dati Trattati e Finalità
L'applicazione è progettata per consentire il controllo diretto e standalone dei dispositivi smart home collegati al proprio hub Athom Homey Pro. 

L'app tratta esclusivamente le seguenti informazioni a livello tecnico e locale:
* **Credenziali e Token di Accesso**: Token di sessione generati tramite il flusso ufficiale OAuth2 con Athom Cloud e l'hub Homey Pro, necessari per autenticare le richieste di comando (accensione luci, regolazione termostati, attivazione automazioni).
* **Stato dei Dispositivi Domotici**: Informazioni sullo stato dei dispositivi (acceso/spento, valori di temperatura, livello di carica delle batterie, cronologia recente di stato) acquisite esclusivamente per la visualizzazione a schermo e l'interazione da parte dell'utente.
* **Dati di Rete Locale (mDNS)**: Durante la fase di configurazione, l'app interroga la rete Wi-Fi locale per rilevare l'indirizzo IP e la porta dell'hub Homey Pro (`_homey._tcp`). Tali parametri vengono utilizzati unicamente per stabilire la connessione diretta locale.

## 3. Nessuna Raccolta o Trasferimento di Dati Personali
* **Zero Server Proprietari**: Lo sviluppatore non possiede, non gestisce e non utilizza alcun server cloud, database o infrastruttura di archiviazione dati.
* **Zero Tracciamento e Pubblicità**: Nell'applicazione non è presente alcun codice di tracciamento, strumento di analisi statistica (come Google Analytics o Firebase), framework pubblicitario né libreria di profilazione.
* **Nessun Trattamento di Dati Sensibili o Biometrici**: L'app non accede ai sensori biometrici dello smartwatch (battito cardiaco, contapassi, sensori di salute) né ai dati di geolocalizzazione GPS.

## 4. Archiviazione e Sicurezza dei Dati (Android Keystore)
Tutti i token di autenticazione e le impostazioni dell'utente (ad esempio l'hub attivo, l'assegnazione dei riquadri Wear OS e le preferenze di sincronizzazione) sono conservati esclusivamente all'interno dello smartwatch dell'utente.
* I dati vengono memorizzati tramite `EncryptedSharedPreferences`, protetti da crittografia hardware avanzata **AES-256-GCM** per i valori e **AES-256-SIV** per le chiavi, sfruttando l'**Android Keystore** del dispositivo.
* I dati non sono accessibili ad altre applicazioni installate sullo smartwatch.

## 5. Destinatari delle Comunicazioni di Rete
Le richieste di rete inviate dall'applicazione avvengono esclusivamente tra lo smartwatch e:
1. **L'hub locale Athom Homey Pro** sulla rete locale Wi-Fi dell'utente.
2. **I servizi cloud ufficiali di Athom B.V.** (`https://*.connect.athom.com` e `https://api.athom.com`), tramite connessioni protette crittografate (HTTPS con protocollo TLS), per consentire il controllo remoto e il rinnovo dei token di autenticazione.

Nessun dato viene mai inviato o comunicato a soggetti terzi o allo sviluppatore dell'app.

## 6. Permessi Android Richiesti
L'applicazione richiede unicamente i permessi strettamente necessari al suo funzionamento:
* `android.permission.INTERNET`: Necessario per consentire la comunicazione di rete locale e remota con l'hub Homey Pro e le API Athom.
* `android.permission.ACCESS_NETWORK_STATE`: Utilizzato per verificare lo stato della connettività (Wi-Fi, Bluetooth o rete dati) prima di inoltrare i comandi.
* `android.permission.ACCESS_WIFI_STATE`: Necessario per coordinare la gestione delle connessioni sulla rete Wi-Fi locale.
* `android.permission.CHANGE_WIFI_MULTICAST_STATE`: Richiesto temporaneamente durante la procedura guidata di prima configurazione per rilevare in automatico l'hub Homey Pro tramite protocollo multicast DNS (mDNS).
* `android.permission.WAKE_LOCK`: Utilizzato in background per garantire la rapida elaborazione e l'aggiornamento dei riquadri (Tiles) dell'orologio senza blocchi di sistema.

## 7. Conservazione e Cancellazione dei Dati (Diritto all'Oblio)
* Poiché i dati risiedono unicamente nello spazio di memoria protetto dello smartwatch, l'utente ha il pieno e istantaneo controllo sui propri dati.
* Selezionando l'opzione **"Disconnetti"** all'interno delle Impostazioni dell'app, tutti i token di accesso, gli indirizzi degli hub e i dati memorizzati vengono **cancellati immediatamente e definitivamente** dal dispositivo.
* La disinstallazione dell'applicazione o la cancellazione dei dati tramite le impostazioni di sistema di Wear OS rimuove anch'essa istantaneamente qualsiasi dato memorizzato.

## 8. Modifiche alla presente Informativa
Qualora dovessero essere introdotte nuove funzionalità che comportino modifiche nel trattamento delle informazioni, la presente informativa verrà tempestivamente aggiornata e resa disponibile su questo documento e sullo store.

---

# PARTE 3: Privacy Policy (English)

## 1. Overview & Data Controller
The application **Homey for Wear OS** (also referred to as **Homey per Wear OS** / **HomeyWatch**) is an independent third-party client built for Wear OS smartwatches to interface with Athom Homey Pro smart home hubs.
For any privacy-related questions or inquiries, users may contact the developer through the contact channels provided on the Google Play Store listing.

## 2. Information Handled & Purpose
Homey for Wear OS operates as a direct, standalone remote controller for smart home devices paired with your Athom Homey Pro hub.

The app handles only the following technical information strictly on the local device:
* **Authentication Tokens**: OAuth2 session and refresh tokens generated via the official Athom Cloud and Homey Pro authentication flows, used solely to authorize device control commands (e.g., toggling lights, setting temperatures, triggering flows).
* **Smart Home Device States**: Real-time attributes of configured devices (such as on/off status, temperature readouts, battery levels, and recent device history) loaded on-demand for display on the watch face and interactive actuation.
* **Local Network Discovery Data (mDNS)**: During initial pairing, the app inspects the local Wi-Fi network for service type `_homey._tcp` to discover the local IP address and port of your Homey Pro hub.

## 3. Zero Data Collection & Zero Third-Party Sharing
* **No Developer Servers**: The developer does not operate, host, or maintain any external servers, databases, or cloud storage.
* **No Tracking, Analytics, or Advertising**: The application contains **zero analytics SDKs** (such as Firebase, Google Analytics, or Mixpanel), **zero crash reporting trackers**, and **zero advertising frameworks**.
* **No Health, Biometric, or Location Data**: The application does not access smartwatch health sensors (heart rate, step counts, ECG) nor does it collect precise or approximate GPS location data.

## 4. Local Storage & Hardware-Backed Security
All user preferences (active hub selection, Wear OS Tile slot configurations, polling intervals) and authentication tokens are retained exclusively on the watch itself:
* Stored using Android's `EncryptedSharedPreferences`, secured with hardware-backed encryption (**AES-256-GCM** for data values, **AES-256-SIV** for keys) managed by the device's **Android Keystore**.
* Data is completely isolated and cannot be read or accessed by any other app on your smartwatch.

## 5. Network Endpoints
Network requests dispatched by the watch are routed strictly and exclusively between the watch and:
1. **Your Local Athom Homey Pro hub** over your home Wi-Fi network.
2. **Official Athom Cloud APIs** (`https://*.connect.athom.com` and `https://api.athom.com`) over industry-standard encrypted HTTPS/TLS connections for remote access and token renewal.

No personal data or telemetry is ever shared with or accessible to the developer or any third party.

## 6. Android Permissions
The application requests only permissions essential to its core functions:
* `android.permission.INTERNET`: Required for local network and secure cloud communications with your Homey Pro hub and Athom APIs.
* `android.permission.ACCESS_NETWORK_STATE`: Used to verify active network connectivity (Wi-Fi, Bluetooth proxy, or cellular) before dispatching commands.
* `android.permission.ACCESS_WIFI_STATE`: Required to inspect Wi-Fi state for local communications.
* `android.permission.CHANGE_WIFI_MULTICAST_STATE`: Temporarily utilized during setup to perform zero-configuration discovery (mDNS) of local Homey Pro hubs.
* `android.permission.WAKE_LOCK`: Used to ensure timely rendering and updating of glanceable Wear OS Tiles without UI stutter.

## 7. Data Retention & Deletion
* Because all data resides solely within the local encrypted storage of your smartwatch, you maintain full control over your information at all times.
* Tapping **"Disconnect"** in the app's Settings screen immediately and permanently purges all authentication tokens, hub identifiers, and cached data from the device.
* Uninstalling the application or clearing application storage through Wear OS System Settings also irrevocably deletes all stored data immediately.

## 8. Updates to this Policy
Any future updates to this Privacy Policy will be reflected in this document and posted alongside application updates on the Google Play Store.

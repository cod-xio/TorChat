package com.torchat.app.ui

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.torchat.app.model.*
import com.torchat.app.network.TorStatus
import com.torchat.app.i18n.AppStrings
import com.torchat.app.i18n.AppLanguage
import com.torchat.app.debug.TorChatLogger
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.layout.ContentScale
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
// Theme
// ─────────────────────────────────────────────
val BgDeep      = Color(0xFF060810)
val BgCard      = Color(0xFF0D1117)
val BgElevated  = Color(0xFF1A2235)
val AccentGreen  = Color(0xFF00FF9D)
val AccentOrange = Color(0xFFFF6B35)
val AccentPurple = Color(0xFFa855f7)
val TextPrimary   = Color(0xFFE8EDF5)
val TextSecondary = Color(0xFF6B7A9A)
val TextDim     = Color(0xFF3D4A65)
val BorderColor = Color(0x0FFFFFFF)

// ── App-Hintergrund reaktiver State ──────────
object AppBackground {
    val bgType      = androidx.compose.runtime.mutableStateOf("color")
    val bgColorHex  = androidx.compose.runtime.mutableStateOf("#FF060810")
    val bgImageUri  = androidx.compose.runtime.mutableStateOf("")

    fun load(context: android.content.Context) {
        bgType.value     = com.torchat.app.data.SettingsManager.getBgType(context)
        bgColorHex.value = com.torchat.app.data.SettingsManager.getBgColor(context)
        bgImageUri.value = com.torchat.app.data.SettingsManager.getBgImageUri(context)
    }

    fun resolvedColor(): Color = try {
        Color(android.graphics.Color.parseColor(bgColorHex.value))
    } catch (_: Exception) { BgDeep }
}

val TorChatColorScheme = darkColorScheme(
    primary = AccentGreen, background = BgDeep, surface = BgCard,
    onPrimary = Color.Black, onBackground = TextPrimary, onSurface = TextPrimary)

fun generateQrBitmap(content: String, size: Int = 512): Bitmap? = try {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bmp ->
        for (x in 0 until size) for (y in 0 until size)
            bmp.setPixel(x, y, if (matrix[x, y]) 0xFF060810.toInt() else 0xFF00FF9D.toInt())
    }
} catch (_: Exception) { null }

// ─────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    private val listVm: ChatListViewModel by viewModels { AppViewModelFactory(application) }
    private val chatVm: ChatViewModel     by viewModels { AppViewModelFactory(application) }
    private val setVm:  SettingsViewModel by viewModels { AppViewModelFactory(application) }

    // Für onNewIntent: contactId an Compose weiterleiten
    private val pendingContactId = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val cid = intent.getStringExtra("contactId")
        if (!cid.isNullOrEmpty()) {
            pendingContactId.value = cid
        }
    }

    // Permission Launcher für POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        com.torchat.app.debug.TorChatLogger.i("MainActivity",
            "POST_NOTIFICATIONS: ${if (granted) "✅ gewährt" else "❌ verweigert"}")
        // Service starten sobald Permission-Dialog erledigt
        startTorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hintergrundeinstellung laden
        AppBackground.load(this)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            com.torchat.app.debug.TorChatLogger.e("TorChat",
                "Uncaught: ${throwable.message}", throwable)
        }

        // POST_NOTIFICATIONS anfragen (Android 13+ / API 33+)
        // TorService braucht diese Permission für seine Foreground-Notification
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                com.torchat.app.debug.TorChatLogger.i("MainActivity",
                    "Frage POST_NOTIFICATIONS an (Android 13+)...")
                notificationPermissionLauncher.launch(perm)
            } else {
                startTorService()
            }
        } else {
            startTorService()
        }

        setContent {
            MaterialTheme(colorScheme = TorChatColorScheme) {
                val app = application as? com.torchat.app.TorChatApp
                val pinMgr = try { app?.pinManager } catch (_: UninitializedPropertyAccessException) { null }
                if (app != null && pinMgr != null) {
                    PinGate(pinManager = pinMgr) {
                        TorChatNavHost(listVm, chatVm, setVm, pendingContactId)                    }
                } else {
                    TorChatNavHost(listVm, chatVm, setVm, pendingContactId)
                }
            }
        }
    }

    private fun startTorService() {
        try {
            listVm.startService()
            requestIgnoreBatteryOptimizations()
        } catch (e: Exception) {
            com.torchat.app.debug.TorChatLogger.e("MainActivity", "Service start: ${e.message}", e)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val pm = getSystemService(android.os.PowerManager::class.java)
                val pkg = packageName
                if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$pkg")
                    )
                    startActivity(intent)
                    com.torchat.app.debug.TorChatLogger.i("MainActivity",
                        "Batterieoptimierung-Dialog geöffnet")
                }
            }
        } catch (e: Exception) {
            com.torchat.app.debug.TorChatLogger.w("MainActivity",
                "Batterieoptimierung: ${e.message}")
        }
    }
} // end MainActivity

// ─────────────────────────────────────────────
// PinSuppressor — verhindert PIN-Lock wenn wir
// bewusst eine externe Activity starten
// (QR-Scanner, Kamera, Galerie, Datei-Picker)
// ─────────────────────────────────────────────
object PinSuppressor {
    @Volatile var suppressed = false
    fun suppress()           { suppressed = true }
    fun consumeAndCheck(): Boolean { val was = suppressed; suppressed = false; return was }
}

// ─────────────────────────────────────────────
// PIN-Gate: zeigt PIN-Screen wenn aktiv
// Nutzt ProcessLifecycleOwner — überwacht den
// App-Prozess, nicht einzelne Activities.
// Zuverlässig auf allen Android-Versionen
// und OEM-ROMs (Samsung, Xiaomi, etc.)
// ─────────────────────────────────────────────
@Composable
fun PinGate(
    pinManager: com.torchat.app.security.PinManager,
    content: @Composable () -> Unit
) {
    // Bei App-Start PIN sofort verlangen wenn aktiv
    var unlocked by remember { mutableStateOf(!pinManager.isPinActive) }
    val context = androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity

        // ProcessLifecycleOwner beobachtet den GESAMTEN App-Prozess.
        // ON_STOP  = App vollständig im Hintergrund (Home, Recents, andere App)
        // ON_START = App wieder im Vordergrund
        // NICHT ausgelöst bei: Rotation, externe Activities (Kamera, QR-Scanner)
        // weil der Prozess dabei nicht in den Hintergrund geht.
        val processObserver = object : androidx.lifecycle.DefaultLifecycleObserver {

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                // App geht in den Hintergrund
                // Rotation wird hier NICHT ausgelöst — ProcessLifecycle ignoriert das
                if (PinSuppressor.suppressed) return  // Wir haben selbst eine externe Activity gestartet
                if (pinManager.isPinActive) {
                    unlocked = false
                }
            }

            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                // App kommt in den Vordergrund
                // PinSuppressor konsumieren falls gesetzt (QR, Kamera, Galerie)
                PinSuppressor.consumeAndCheck()
                // unlocked bleibt wie onStop es gesetzt hat
            }
        }

        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
            .addObserver(processObserver)

        onDispose {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
                .removeObserver(processObserver)
        }
    }

    if (unlocked) {
        content()
    } else {
        PinLockScreen(
            onCorrectPin = { unlocked = true },
            pinManager   = pinManager
        )
    }
}

// ─────────────────────────────────────────────
// PIN-Eingabe Screen
// ─────────────────────────────────────────────
@Composable
fun PinLockScreen(
    onCorrectPin: () -> Unit,
    pinManager: com.torchat.app.security.PinManager
) {
    val s = AppStrings.current
    var pin     by remember { mutableStateOf("") }
    var error   by remember { mutableStateOf(false) }
    var shake   by remember { mutableStateOf(false) }

    val shakeOffset by animateFloatAsState(
        targetValue = if (shake) 12f else 0f,
        animationSpec = if (shake)
            androidx.compose.animation.core.spring(dampingRatio = 0.2f, stiffness = 800f)
        else androidx.compose.animation.core.spring(), label = "shake",
        finishedListener = { shake = false }
    )

    fun tryPin() {
        if (pinManager.checkPin(pin)) {
            onCorrectPin()
        } else {
            error = true; shake = true
            pin = ""
        }
    }

    Box(Modifier.fillMaxSize().background(BgDeep), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.offset(x = shakeOffset.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Logo
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(72.dp).background(AccentGreen.copy(.12f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, null, tint = AccentGreen, modifier = Modifier.size(36.dp))
                }
                Text(s.appName, color = AccentGreen, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(s.pinEnter, color = TextSecondary, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace)
            }

            // PIN-Punkte Anzeige
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(6) { i ->
                    val filled = i < pin.length
                    Box(Modifier.size(16.dp).background(
                        if (filled) if (error) Color(0xFFEF4444) else AccentGreen
                        else BorderColor.copy(.3f),
                        CircleShape))
                }
            }

            if (error) {
                Text(s.pinWrong, color = Color(0xFFEF4444),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            // Numpad — jede Taste hat eine eigene Farbe
            val keyColors = mapOf(
                "1" to Color(0xFF00FF9D), "2" to Color(0xFF00D4FF),
                "3" to Color(0xFFFF6B6B), "4" to Color(0xFFFFD93D),
                "5" to Color(0xFFa855f7), "6" to Color(0xFF4ADE80),
                "7" to Color(0xFFF97316), "8" to Color(0xFF06B6D4),
                "9" to Color(0xFFEC4899), "0" to Color(0xFF84CC16)
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                for (row in listOf(listOf("1","2","3"), listOf("4","5","6"),
                                   listOf("7","8","9"), listOf("","0","⌫"))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { key ->
                            if (key.isEmpty()) {
                                Spacer(Modifier.size(72.dp))
                            } else {
                                val kc = keyColors[key] ?: TextSecondary
                                Box(Modifier.size(72.dp)
                                    .background(kc.copy(alpha = .15f), RoundedCornerShape(36.dp))
                                    .clickable {
                                        error = false
                                        when (key) {
                                            "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                            else -> if (pin.length < 6) {
                                                pin += key
                                                if (pin.length >= 4) tryPin()
                                            }
                                        }
                                    },
                                    contentAlignment = Alignment.Center) {
                                    if (key == "⌫") {
                                        Icon(Icons.Default.Backspace, null,
                                            tint = TextSecondary, modifier = Modifier.size(22.dp))
                                    } else {
                                        Text(key, color = kc, fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Navigation  — alle Routen definiert
// ─────────────────────────────────────────────
@Composable
fun TorChatNavHost(
    listVm: ChatListViewModel,
    chatVm: ChatViewModel,
    setVm: SettingsViewModel,
    pendingContactId: androidx.compose.runtime.MutableState<String?> = androidx.compose.runtime.mutableStateOf(null)
) {
    val s = AppStrings.current
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Beim Start via Push-Benachrichtigung direkt zum betreffenden Chat navigieren
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity
        val contactIdFromNotif = activity?.intent?.getStringExtra("contactId")
        if (!contactIdFromNotif.isNullOrEmpty()) {
            chatVm.setContact(contactIdFromNotif)
            nav.navigate("chat/$contactIdFromNotif") {
                popUpTo("chats") { inclusive = false }
            }
            activity.intent?.removeExtra("contactId")
        }
    }

    // Wenn App bereits läuft und neue Notification getippt → onNewIntent → hier reagieren
    LaunchedEffect(pendingContactId.value) {
        val cid = pendingContactId.value ?: return@LaunchedEffect
        pendingContactId.value = null
        chatVm.setContact(cid)
        nav.navigate("chat/$cid") {
            popUpTo("chats") { inclusive = false }
        }
    }

    // Globaler Handler für eingehende Nachrichten
    LaunchedEffect(Unit) {
        val app = context.applicationContext as? com.torchat.app.TorChatApp
        app?.messagingEngine?.incomingMessages?.collect { msg ->
            // Room-Flow aktualisiert Kontakte automatisch
        }
    }

    NavHost(nav, startDestination = "chats") {

        composable("chats") {
            ChatListScreen(listVm,
                onOpenChat    = { c -> chatVm.setContact(c.id); nav.navigate("chat/${c.id}") },
                onOpenSettings = { nav.navigate("settings") },
                onAddContact  = { nav.navigate("add_contact") },        // ← verknüpft
                onCreateGroup = { nav.navigate("create_group") }        // ← verknüpft
            )
        }
        composable("chat/{contactId}") { back ->
            val cid = back.arguments?.getString("contactId") ?: return@composable
            val contacts by listVm.contacts.collectAsState()
            val contact = contacts.find { it.id == cid }
            if (contact == null) {
                // Kurzer Ladeindikator während Room-DB initialisiert
                if (contacts.isNotEmpty()) {
                    // Kontakt existiert nicht mehr → zurück
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    Box(Modifier.fillMaxSize().background(BgDeep),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentGreen,
                            modifier = Modifier.size(32.dp))
                    }
                }
                return@composable
            }
            ChatScreen(contact, chatVm) { nav.popBackStack() }
        }
        composable("settings") {
            SettingsScreen(setVm, listVm, onBack = { nav.popBackStack() },
                onDiagnostics = { nav.navigate("diagnostics") })
        }
        composable("diagnostics") {
            DiagnosticsScreen { nav.popBackStack() }
        }
        composable("add_contact") {                                      // ← Route existiert
            AddContactScreen(listVm) { nav.popBackStack() }
        }
        composable("create_group") {                                     // ← NEU
            CreateGroupScreen(listVm) { nav.popBackStack() }
        }
    }
}

// ─────────────────────────────────────────────
// Chat List  — mit BottomSheet für + Menü
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    onOpenChat:     (Contact) -> Unit,
    onOpenSettings: () -> Unit,
    onAddContact:   () -> Unit,
    onCreateGroup:  () -> Unit
) {
    val s = AppStrings.current
    val contacts  by viewModel.contacts.collectAsState()
    val torStatus by viewModel.torStatus.collectAsState()
    var showAddMenu by remember { mutableStateOf(false) }   // Bottom-Sheet

    // Bottom-Sheet für + Menü
    if (showAddMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAddMenu = false },
            containerColor   = BgCard,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Neu", color = TextPrimary, fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
                // Kontakt hinzufügen
                ListItem(
                    modifier = Modifier.clickable { showAddMenu = false; onAddContact() }
                        .background(BgElevated, RoundedCornerShape(12.dp)),
                    headlineContent  = { Text(s.addContact, color = TextPrimary) },
                    supportingContent = { Text("Onion-Adresse oder QR-Code scannen",
                        color = TextSecondary, fontSize = 12.sp) },
                    leadingContent   = {
                        Box(Modifier.size(40.dp).background(AccentGreen.copy(.15f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null,
                                tint = AccentGreen, modifier = Modifier.size(20.dp))
                        }
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                Spacer(Modifier.height(6.dp))
                // Gruppe erstellen
                ListItem(
                    modifier = Modifier.clickable { showAddMenu = false; onCreateGroup() }
                        .background(BgElevated, RoundedCornerShape(12.dp)),
                    headlineContent  = { Text(s.createGroup, color = TextPrimary) },
                    supportingContent = { Text("Mehrere Kontakte in einem Chat",
                        color = TextSecondary, fontSize = 12.sp) },
                    leadingContent   = {
                        Box(Modifier.size(40.dp).background(AccentPurple.copy(.15f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Group, contentDescription = null,
                                tint = AccentPurple, modifier = Modifier.size(20.dp))
                        }
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            Column(Modifier.background(BgDeep).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(s.appName, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, color = AccentGreen)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TorStatusBadge(torStatus)
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, null, tint = TextSecondary)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick  = { showAddMenu = true },   // ← öffnet Bottom-Sheet
                containerColor = AccentGreen, contentColor = Color.Black,
                shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Default.Add, "Neu")
            }
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🧅", fontSize = 56.sp)
                    Text("Noch keine Chats", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Tippe  +  um einen Kontakt oder\neine Gruppe hinzuzufügen",
                        color = TextDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                // Gruppen
                val groups   = contacts.filter { it.isGroup }
                val singles  = contacts.filter { !it.isGroup }
                if (groups.isNotEmpty()) {
                    item { SectionHeader("GRUPPEN") }
                    items(groups) { contact ->
                        ChatRow(contact,
                            onClick  = { onOpenChat(contact) },
                            onBlock  = { viewModel.blockContact(contact) },
                            onDelete = { viewModel.deleteContact(contact) },
                            onRename = { name -> viewModel.renameContact(contact, name) })
                    }
                }
                if (singles.isNotEmpty()) {
                    item { SectionHeader("KONTAKTE") }
                    items(singles) { contact ->
                        ChatRow(contact,
                            onClick  = { onOpenChat(contact) },
                            onBlock  = { viewModel.blockContact(contact) },
                            onDelete = { viewModel.deleteContact(contact) },
                            onRename = { name -> viewModel.renameContact(contact, name) })
                    }
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, fontSize = 9.sp, color = TextDim, fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
fun TorStatusBadge(status: TorStatus) {
    val (color, label, animate) = when (status) {
        TorStatus.CONNECTED    -> Triple(AccentGreen,          "TOR",        false)
        TorStatus.CONNECTING   -> Triple(Color(0xFFF59E0B),    "VERBINDET",  true)
        TorStatus.DISCONNECTED -> Triple(Color(0xFFEF4444),    "GETRENNT",   false)
        TorStatus.ERROR        -> Triple(Color(0xFFEF4444),    "FEHLER",     false)
    }
    Row(Modifier.background(color.copy(.12f), RoundedCornerShape(20.dp))
        .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (animate) {
            // Pulsierender Punkt beim Verbinden
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    androidx.compose.animation.core.tween(700),
                    androidx.compose.animation.core.RepeatMode.Reverse),
                label = "alpha")
            Box(Modifier.size(6.dp).background(color.copy(alpha), CircleShape))
        } else {
            Box(Modifier.size(6.dp).background(color, CircleShape))
        }
        Text(label, color = color, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ChatRow(
    contact:  Contact,
    onClick:  () -> Unit,
    onBlock:  (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null
) {
    val s = AppStrings.current
    val avatarColor = if (contact.isGroup) AccentPurple else try {
        Color(android.graphics.Color.parseColor(contact.avatarColor)) } catch (_: Exception) { AccentGreen }

    var showMenu         by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newName          by remember(contact.name) { mutableStateOf(contact.name) }

    // ── Umbenennen-Dialog ────────────────────────────────────────────
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false; newName = contact.name },
            containerColor   = BgCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Edit, null, tint = AccentGreen,
                        modifier = Modifier.size(20.dp))
                    Text("Kontakt umbenennen", color = TextPrimary,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aktueller Name: ${contact.name}", color = TextDim,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        placeholder   = { Text("Neuer Name...", color = TextDim) },
                        singleLine    = true,
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentGreen,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary,
                            cursorColor          = AccentGreen
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showRenameDialog = false; newName = contact.name }) {
                        Text("Abbrechen", color = TextDim, fontFamily = FontFamily.Monospace)
                    }
                    Button(
                        onClick = {
                            val trimmed = newName.trim()
                            if (trimmed.isNotEmpty()) {
                                onRename?.invoke(trimmed)
                                showRenameDialog = false
                            }
                        },
                        enabled = newName.trim().isNotEmpty() && newName.trim() != contact.name,
                        colors  = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        shape   = RoundedCornerShape(10.dp)
                    ) {
                        Text("Speichern", color = Color.Black,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }

    // Löschen-Bestätigung
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor   = BgCard,
            title = { Text(s.deleteContact, color = Color(0xFFEF4444),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text  = { Text("${contact.name} und alle Nachrichten werden endgültig gelöscht.",
                color = TextSecondary, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke(); showDeleteDialog = false }) {
                    Text(s.delete, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(s.cancel, color = TextSecondary)
                }
            }
        )
    }

    Box {
        Row(Modifier.fillMaxWidth()
            .combinedClickable(
                onClick    = onClick,
                onLongClick = { showMenu = true }
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {

            Box(Modifier.size(50.dp).background(avatarColor.copy(.15f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                if (contact.isGroup)
                    Icon(Icons.Default.Group, null, tint = avatarColor, modifier = Modifier.size(24.dp))
                else
                    Text(contact.initials, color = avatarColor, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(contact.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    if (contact.isGroup)
                        Text(s.groupLabel, color = AccentPurple, fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.background(AccentPurple.copy(.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp))
                    else
                        Text(".onion", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.background(BgElevated, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp))
                }
                Text(
                    if (contact.isGroup) "${contact.memberCount} Mitglieder"
                    else if (!contact.isGroup && contact.remoteDisplayName.isNotBlank()
                             && contact.remoteDisplayName != contact.name)
                        "\"${contact.remoteDisplayName}\""
                    else contact.shortOnion,
                    color = if (!contact.isGroup && contact.remoteDisplayName.isNotBlank()
                                && contact.remoteDisplayName != contact.name)
                                AccentGreen.copy(.7f) else TextSecondary,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Kontextmenü (Long Press)
        DropdownMenu(
            expanded         = showMenu,
            onDismissRequest = { showMenu = false },
            modifier         = Modifier.background(BgCard)
        ) {
            // Umbenennen (immer sichtbar)
            DropdownMenuItem(
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, null,
                            tint = AccentGreen, modifier = Modifier.size(18.dp))
                        Text("Umbenennen", color = TextPrimary, fontSize = 14.sp)
                    }
                },
                onClick = {
                    newName = contact.name
                    showRenameDialog = true
                    showMenu = false
                }
            )
            Divider(color = BorderColor)
            if (!contact.isGroup) {
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Block, null,
                                tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                            Text(s.block, color = Color(0xFFEF4444), fontSize = 14.sp)
                        }
                    },
                    onClick = { onBlock?.invoke(); showMenu = false }
                )
                Divider(color = BorderColor)
            }
            DropdownMenuItem(
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteForever, null,
                            tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                        Text(s.delete, color = Color(0xFFEF4444), fontSize = 14.sp)
                    }
                },
                onClick = { showDeleteDialog = true; showMenu = false }
            )
        }
    }
}

// ─────────────────────────────────────────────
// QR Dialog
// ─────────────────────────────────────────────
@Composable
fun QrDialog(title: String, content: String, onDismiss: () -> Unit) {
    val bitmap = remember(content) { generateQrBitmap(content, 400) }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = BgCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(.3f)),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = AccentGreen, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                if (bitmap != null)
                    Image(bitmap.asImageBitmap(), "QR",
                        Modifier.size(220.dp).background(Color.White).padding(8.dp))
                Text(content, color = AccentGreen, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(BgDeep, RoundedCornerShape(8.dp)).padding(8.dp))
                OutlinedButton(onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(.4f))) {
                    Text("Schließen")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Add Contact Screen
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(viewModel: ChatListViewModel, onBack: () -> Unit) {
    // MutableState als separate Objekte damit der Launcher-Callback
    // immer auf die neueste Referenz schreibt
    val nameState    = remember { mutableStateOf("") }
    val onionState   = remember { mutableStateOf("") }
    var name         by nameState
    var onionAddress by onionState
    val s = AppStrings.current
    var showMyQr     by remember { mutableStateOf(false) }
    var scanFeedback by remember { mutableStateOf("") }  // Zeigt was gescannt wurde
    val myOnion      = viewModel.myOnionAddress

    // Launcher muss nameState/onionState direkt referenzieren
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents?.trim() ?: return@rememberLauncherForActivityResult
        scanFeedback = "Gescannt: $scanned"
        when {
            scanned.startsWith("torchat:") -> {
                // Format: torchat:ADRESSE:NAME
                val payload = scanned.removePrefix("torchat:")
                val colonIdx = payload.indexOf(":")
                if (colonIdx > 0) {
                    onionState.value = payload.substring(0, colonIdx)
                    nameState.value  = payload.substring(colonIdx + 1)
                } else {
                    onionState.value = payload
                }
            }
            scanned.contains(".onion") -> {
                // Direkte .onion Adresse
                onionState.value = scanned
            }
            else -> {
                // Unbekanntes Format – trotzdem ins Feld eintragen
                onionState.value = scanned
            }
        }
    }

    if (showMyQr) QrDialog("Meine Adresse teilen", "torchat:$myOnion") { showMyQr = false }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            Row(Modifier.fillMaxWidth().background(BgDeep).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AccentGreen) }
                Text(s.addContact, color = AccentGreen, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // Meine Adresse teilen
            item {
                Spacer(Modifier.height(4.dp))
                Card(Modifier.fillMaxWidth().clickable { showMyQr = true },
                    colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(.06f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(.25f)),
                    shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(44.dp).background(AccentGreen.copy(.15f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.QrCode, null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Meine Adresse teilen", color = TextPrimary,
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("QR-Code anzeigen & weiterleiten",
                                color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                    }
                }
            }

            // QR Scanner
            item {
                Button(onClick = {
                    PinSuppressor.suppress(); scanLauncher.launch(ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("TorChat QR-Code scannen – alle Richtungen möglich")
                        setBeepEnabled(false)
                        // Alle Kamera-Orientierungen erlauben (Portrait + Landscape)
                        setOrientationLocked(false)
                        // Beide Kameras erlauben (Vorder- und Rückkamera)
                        setCameraId(0)
                        // Barcode im ganzen Bild suchen (nicht nur Mitte)
                        setBarcodeImageEnabled(false)
                    })
                }, Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BgElevated, contentColor = AccentGreen),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(.4f)),
                    shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("QR-Code scannen", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }

            // Scan-Feedback — zeigt was gescannt wurde
            if (scanFeedback.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth()
                        .background(AccentGreen.copy(.08f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                        Text(scanFeedback, color = AccentGreen, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Divider
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Divider(Modifier.weight(1f), color = BorderColor)
                    Text("  oder manuell eingeben  ", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Divider(Modifier.weight(1f), color = BorderColor)
                }
            }

            // Name
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { nameState.value = it },
                    label = { Text(s.contactName, color = TextSecondary) },
                    placeholder = { Text("z.B. Anna K.", color = TextDim) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = BorderColor, focusedBorderColor = AccentGreen.copy(.5f),
                        unfocusedContainerColor = BgElevated, focusedContainerColor = BgElevated,
                        unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary,
                        unfocusedLabelColor = TextSecondary, focusedLabelColor = AccentGreen),
                    singleLine = true)
            }

            // Onion Adresse
            item {
                OutlinedTextField(
                    value = onionAddress,
                    onValueChange = { onionState.value = it },
                    label = { Text(s.onionAddress, color = TextSecondary) },
                    placeholder = { Text("abc123.onion", color = TextDim, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = if (onionAddress.isNotEmpty()) AccentGreen.copy(.4f) else BorderColor,
                        focusedBorderColor = AccentGreen.copy(.5f),
                        unfocusedContainerColor = BgElevated, focusedContainerColor = BgElevated,
                        unfocusedTextColor = AccentGreen, focusedTextColor = AccentGreen,
                        unfocusedLabelColor = TextSecondary, focusedLabelColor = AccentGreen),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            }

            // Button
            item {
                // Validierung: Onion-Adresse muss mindestens 20 Zeichen haben (v2 min) und .onion enden
                val normalizedOnion = onionAddress.trim().lowercase()
                    .let { if (it.endsWith(".onion")) it else "$it.onion" }
                val onionValid = normalizedOnion.length >= 20 &&
                    normalizedOnion.endsWith(".onion") &&
                    normalizedOnion != "$myOnion.onion" &&
                    normalizedOnion != myOnion
                val canAdd = name.isNotBlank() && onionAddress.isNotBlank() && onionValid

                // Hinweis wenn Adresse ungültig
                if (onionAddress.isNotBlank() && !onionValid) {
                    Row(Modifier.fillMaxWidth()
                        .background(Color(0xFFEF4444).copy(.08f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp))
                        Text(
                            if (normalizedOnion == myOnion || normalizedOnion == "$myOnion.onion")
                                "Du kannst dich nicht selbst als Kontakt hinzufügen"
                            else
                                "Ungültige Onion-Adresse — mindestens 20 Zeichen erforderlich",
                            color = Color(0xFFEF4444), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Button(onClick = {
                    if (canAdd) { viewModel.addContact(name, onionAddress); onBack() }
                }, Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black),
                    shape = RoundedCornerShape(14.dp),
                    enabled = canAdd) {
                    Text(s.addContact, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────
// Create Group Screen  ← NEU
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(viewModel: ChatListViewModel, onBack: () -> Unit) {
    val s = AppStrings.current
    var groupName by remember { mutableStateOf("") }
    val contacts  by viewModel.contacts.collectAsState()
    val individuals = contacts.filter { !it.isGroup }
    val selected  = remember { mutableStateListOf<String>() }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            Row(Modifier.fillMaxWidth().background(BgDeep).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AccentGreen) }
                Text("Neue Gruppe", color = AccentPurple, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            item { Spacer(Modifier.height(4.dp)) }

            // Gruppenname
            item {
                OutlinedTextField(groupName, { groupName = it },
                    label = { Text(s.groupName, color = TextSecondary) },
                    placeholder = { Text("z.B. Projektteam", color = TextDim) },
                    leadingIcon = { Icon(Icons.Default.Group, null, tint = AccentPurple, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = BorderColor, focusedBorderColor = AccentPurple.copy(.5f),
                        unfocusedContainerColor = BgElevated, focusedContainerColor = BgElevated,
                        unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary,
                        unfocusedLabelColor = TextSecondary, focusedLabelColor = AccentPurple),
                    singleLine = true)
            }

            // Mitglieder auswählen
            item {
                Text("MITGLIEDER AUSWÄHLEN", fontSize = 9.sp, color = TextDim,
                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            }

            if (individuals.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = BgCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                        shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                            Text("Keine Kontakte vorhanden.\nFüge zuerst Kontakte hinzu.",
                                color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                items(individuals) { contact ->
                    val isSelected = contact.id in selected
                    val avatarColor = try { Color(android.graphics.Color.parseColor(contact.avatarColor)) }
                    catch (_: Exception) { AccentGreen }

                    Card(Modifier.fillMaxWidth().clickable {
                        if (isSelected) selected.remove(contact.id) else selected.add(contact.id)
                    }, colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) AccentPurple.copy(.1f) else BgCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp,
                            if (isSelected) AccentPurple.copy(.5f) else BorderColor),
                        shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(40.dp).background(avatarColor.copy(.15f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(contact.initials, color = avatarColor,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(contact.shortOnion, color = TextSecondary, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (isSelected)
                                Box(Modifier.size(24.dp).background(AccentPurple, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            else
                                Box(Modifier.size(24.dp).background(BgElevated, CircleShape))
                        }
                    }
                }
            }

            // Ausgewählte Mitglieder Zähler
            if (selected.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().background(AccentPurple.copy(.1f), RoundedCornerShape(10.dp))
                        .padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Group, null, tint = AccentPurple, modifier = Modifier.size(16.dp))
                        Text("${selected.size} Mitglieder ausgewählt",
                            color = AccentPurple, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Erstellen Button
            item {
                Button(onClick = {
                    if (groupName.isNotBlank() && selected.isNotEmpty()) {
                        viewModel.createGroup(groupName, selected.toList())
                        onBack()
                    }
                }, Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                    enabled = groupName.isNotBlank() && selected.isNotEmpty()) {
                    Icon(Icons.Default.Group, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.createGroup, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────
// Chat Screen  –  mit Foto, Audio, Kamera
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(contact: Contact, viewModel: ChatViewModel, onBack: () -> Unit) {
    val context  = androidx.compose.ui.platform.LocalContext.current
    val s = AppStrings.current
    val messages by viewModel.messages.collectAsState()
    var input    by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboard  = LocalSoftwareKeyboardController.current
    var showAttachMenu by remember { mutableStateOf(false) }
    var currentlyPlayingId by remember { mutableStateOf<String?>(null) }

    // Gruppenmitglieder für Absender-Anzeige – reaktiver Flow (aktualisiert sich automatisch)
    val listVm: ChatListViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val groupMembers by remember(contact.id, contact.isGroup) {
        if (contact.isGroup) listVm.getGroupMembersFlow(contact.id)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Hintergrund – reaktiv: Änderungen in Einstellungen sofort sichtbar
    val bgType     by AppBackground.bgType
    val bgImageUri by AppBackground.bgImageUri
    val bgColorHex by AppBackground.bgColorHex
    val bgColor    = try { Color(android.graphics.Color.parseColor(bgColorHex)) } catch (_: Exception) { BgDeep }

    // Kontakt setzen → messages Flow aktivieren
    LaunchedEffect(contact.id) {
        viewModel.setContact(contact.id)
    }

    // Einfügen-Aktion aus Kontextmenü → ins Eingabefeld übernehmen
    LaunchedEffect(Unit) {
        viewModel.inputAppend.collect { text ->
            input += text
            keyboard?.show()
        }
    }

    // Eingehende Nachrichten vom P2P-Engine direkt in den Chat leiten
    val app = context.applicationContext as? com.torchat.app.TorChatApp
    LaunchedEffect(contact.id) {
        app?.messagingEngine?.incomingMessages?.collect { msg ->
            if (msg.contactId == contact.id) {
                // DB-Flow aktualisiert sich automatisch → kein explizites Refresh nötig
                // Aber markAllRead aufrufen
                viewModel.setContact(contact.id)
            }
        }
    }

    // Audio Recorder State
    var isRecording    by remember { mutableStateOf(false) }
    var recordStartMs  by remember { mutableStateOf(0L) }
    val recorder       = remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var audioFile      by remember { mutableStateOf<java.io.File?>(null) }
    // Vorschau-Zustand nach Aufnahme
    var previewFile    by remember { mutableStateOf<java.io.File?>(null) }
    var previewDurMs   by remember { mutableStateOf(0L) }
    var recordElapsedMs by remember { mutableStateOf(0L) }

    // 5-Minuten Auto-Stop + Elapsed-Timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val start = System.currentTimeMillis()
            while (isRecording) {
                recordElapsedMs = System.currentTimeMillis() - start
                if (recordElapsedMs >= 5 * 60_000L) {
                    // Auto-Stop nach 5 Minuten
                    isRecording = false
                    val dur = recordElapsedMs
                    try { recorder.value?.apply { stop(); release() }; recorder.value = null }
                    catch (_: Exception) { recorder.value = null }
                    audioFile?.let { f ->
                        if (f.exists() && dur > 500) {
                            previewFile  = f
                            previewDurMs = dur
                            audioFile    = null
                        }
                    }
                    break
                }
                kotlinx.coroutines.delay(100)
            }
        } else {
            recordElapsedMs = 0L
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Kamera: Uri für Foto
    var cameraImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraImageUri != null) {
            val uri  = cameraImageUri!!
            val path = getPathFromUri(context, uri)
            if (path != null) viewModel.sendPhoto(contact, path, "foto_${System.currentTimeMillis()}.jpg")
        }
    }

    // Galerie: Foto auswählen
    val galleryLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val path = getPathFromUri(context, uri)
        if (path != null) viewModel.sendPhoto(contact, path, "foto_${System.currentTimeMillis()}.jpg")
    }

    // Permissions
    val permLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[android.Manifest.permission.CAMERA] == true) {
            cameraImageUri = createCameraImageUri(context)
            cameraImageUri?.let { PinSuppressor.suppress(); cameraLauncher.launch(it) }
        }
    }

    val avatarColor = if (contact.isGroup) AccentPurple else try {
        Color(android.graphics.Color.parseColor(contact.avatarColor)) } catch (_: Exception) { AccentGreen }

    // Attach-Menu BottomSheet
    if (showAttachMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachMenu = false },
            containerColor   = BgCard,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anhang senden", color = TextPrimary, fontWeight = FontWeight.Bold,
                    fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))

                // Kamera
                AttachOption(Icons.Default.CameraAlt, "Kamera", "Foto aufnehmen", AccentGreen) {
                    showAttachMenu = false
                    val hasCam = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasCam) {
                        cameraImageUri = createCameraImageUri(context)
                        cameraImageUri?.let { PinSuppressor.suppress(); cameraLauncher.launch(it) }
                    } else {
                        PinSuppressor.suppress(); permLauncher.launch(arrayOf(android.Manifest.permission.CAMERA))
                    }
                }

                // Galerie
                AttachOption(Icons.Default.Photo, "Galerie", "Foto aus Galerie", Color(0xFF38bdf8)) {
                    showAttachMenu = false
                    PinSuppressor.suppress(); galleryLauncher.launch("image/*")
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }

    var showGroupManagement by remember { mutableStateOf(false) }

    // Gruppenverwaltungs-Dialog
    if (showGroupManagement && contact.isGroup) {
        GroupManagementDialog(
            group     = contact,
            viewModel = listVm,
            onDismiss = { showGroupManagement = false }
        )
    }

    Scaffold(containerColor = if (bgType == "color") bgColor else BgDeep,
        topBar = {
            Surface(color = BgDeep.copy(alpha = 0.92f), shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = AccentGreen) }
                    Box(Modifier.size(38.dp).background(avatarColor.copy(.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        if (contact.isGroup)
                            Icon(Icons.Default.Group, null, tint = avatarColor, modifier = Modifier.size(20.dp))
                        else
                            Text(contact.initials, color = avatarColor,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(contact.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            if (contact.isGroup)
                                Text("${contact.memberCount} Mitglieder", color = AccentPurple,
                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Security, null, tint = AccentGreen, modifier = Modifier.size(10.dp))
                            // remoteDisplayName anzeigen wenn vorhanden und abweichend
                            if (!contact.isGroup && contact.remoteDisplayName.isNotBlank()
                                && contact.remoteDisplayName != contact.name) {
                                Text("\"${contact.remoteDisplayName}\"",
                                    color = AccentGreen.copy(.7f), fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                Text(contact.onionAddress, color = TextSecondary, fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    // Gruppenverwaltung-Button
                    if (contact.isGroup) {
                        IconButton(onClick = { showGroupManagement = true }) {
                            Icon(Icons.Default.ManageAccounts, null, tint = AccentPurple)
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = BgDeep) {
                Column {

                    // ── Vorschau-Leiste nach Aufnahme ───────────────────────────
                    if (previewFile != null) {
                        val pFile = previewFile!!
                        var pvPlaying  by remember { mutableStateOf(false) }
                        var pvProgress by remember { mutableStateOf(0f) }
                        var pvDurMs    by remember { mutableStateOf(previewDurMs.coerceAtLeast(1L)) }
                        val pvPlayer = remember(pFile.absolutePath) {
                            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().also { p ->
                                p.setMediaItem(androidx.media3.common.MediaItem.fromUri(
                                    android.net.Uri.fromFile(pFile)))
                                p.prepare()
                                p.addListener(object : androidx.media3.common.Player.Listener {
                                    override fun onPlaybackStateChanged(state: Int) {
                                        if (state == androidx.media3.common.Player.STATE_ENDED) {
                                            pvPlaying = false; pvProgress = 1f
                                        }
                                    }
                                })
                            }
                        }
                        DisposableEffect(pFile.absolutePath) {
                            onDispose { pvPlayer.release() }
                        }
                        LaunchedEffect(pvPlaying) {
                            while (pvPlaying) {
                                val dur = pvPlayer.duration.takeIf { it > 0 } ?: previewDurMs.coerceAtLeast(1L)
                                pvDurMs   = dur
                                pvProgress = pvPlayer.currentPosition.toFloat() / dur
                                kotlinx.coroutines.delay(100)
                            }
                        }

                        Column(
                            Modifier.fillMaxWidth()
                                .background(BgCard)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Titel
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Mic, null, tint = AccentGreen,
                                    modifier = Modifier.size(14.dp))
                                Text("Aufnahme bereit — Vorschau",
                                    color = AccentGreen, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(formatDuration(pvDurMs), color = TextDim,
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }

                            // Vorschau Player
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Play/Pause
                                Box(
                                    Modifier.size(36.dp)
                                        .background(AccentGreen.copy(.15f), CircleShape)
                                        .clickable {
                                            if (pvPlaying) { pvPlayer.pause(); pvPlaying = false }
                                            else {
                                                if (pvProgress >= 1f) { pvPlayer.seekTo(0); pvPlayer.prepare() }
                                                pvPlayer.play(); pvPlaying = true
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (pvPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                                }
                                // Seek Slider
                                androidx.compose.material3.Slider(
                                    value = pvProgress,
                                    onValueChange = { pos ->
                                        pvProgress = pos
                                        pvPlayer.seekTo((pos * pvDurMs).toLong())
                                    },
                                    modifier = Modifier.weight(1f).height(20.dp),
                                    colors = androidx.compose.material3.SliderDefaults.colors(
                                        thumbColor = AccentGreen,
                                        activeTrackColor = AccentGreen,
                                        inactiveTrackColor = AccentGreen.copy(.2f)
                                    )
                                )
                                Text(formatDuration((pvProgress * pvDurMs).toLong()),
                                    color = TextDim, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace)
                            }

                            // Aktionen
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Verwerfen
                                OutlinedButton(
                                    onClick = {
                                        pvPlayer.release()
                                        pFile.delete()
                                        previewFile = null
                                        previewDurMs = 0L
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, Color(0xFFEF4444).copy(.5f))
                                ) {
                                    Icon(Icons.Default.Delete, null,
                                        tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Verwerfen", color = Color(0xFFEF4444),
                                        fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                }
                                // Neu aufnehmen
                                OutlinedButton(
                                    onClick = {
                                        pvPlayer.release()
                                        pFile.delete()
                                        previewFile = null
                                        previewDurMs = 0L
                                        // Mikrofon-Permission prüfen und direkt starten
                                        val hasMic = context.checkSelfPermission(
                                            android.Manifest.permission.RECORD_AUDIO) ==
                                            android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (hasMic) {
                                            val dir = java.io.File(context.cacheDir, "audio").apply { mkdirs() }
                                            val f   = java.io.File(dir, "rec_${System.currentTimeMillis()}.m4a")
                                            audioFile    = f
                                            recordStartMs = System.currentTimeMillis()
                                            isRecording  = true
                                            try {
                                                recorder.value = android.media.MediaRecorder(context).apply {
                                                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                                                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                                                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                                                    setAudioSamplingRate(44100)
                                                    setAudioEncodingBitRate(128000)
                                                    setOutputFile(f.absolutePath)
                                                    prepare(); start()
                                                }
                                            } catch (_: Exception) { isRecording = false }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, AccentGreen.copy(.4f))
                                ) {
                                    Icon(Icons.Default.Mic, null,
                                        tint = AccentGreen, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Neu", color = AccentGreen,
                                        fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                }
                                // Senden
                                Button(
                                    onClick = {
                                        pvPlayer.release()
                                        viewModel.sendAudio(contact, pFile.absolutePath, pvDurMs)
                                        previewFile  = null
                                        previewDurMs = 0L
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                ) {
                                    Icon(Icons.Default.Send, null,
                                        tint = Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Senden", color = Color.Black,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    // ── Eingabezeile ────────────────────────────────────────────
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                        .navigationBarsPadding().imePadding(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {

                        // Anhang-Button (während Aufnahme ausgeblendet)
                        if (!isRecording) {
                            IconButton(onClick = { showAttachMenu = true },
                                modifier = Modifier.size(44.dp)
                                    .background(BgElevated, RoundedCornerShape(14.dp))) {
                                Icon(Icons.Default.AttachFile, null, tint = AccentGreen,
                                    modifier = Modifier.size(22.dp))
                            }
                        }

                        // Während Aufnahme: Timer + Waveform-Indikator im Textfeld-Bereich
                        if (isRecording) {
                            Row(
                                Modifier.weight(1f)
                                    .background(Color(0xFFEF4444).copy(.08f), RoundedCornerShape(22.dp))
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Pulsierender Punkt
                                val pulse by rememberInfiniteTransition(label = "pulse")
                                    .animateFloat(
                                        initialValue = 0.4f, targetValue = 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = androidx.compose.animation.core.tween(600),
                                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                                        ), label = "pulseAlpha"
                                    )
                                Box(Modifier.size(8.dp)
                                    .background(Color(0xFFEF4444).copy(pulse), CircleShape))
                                Text(formatDuration(recordElapsedMs),
                                    color = Color(0xFFEF4444), fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text("/ 5:00", color = TextDim, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.weight(1f))
                                Text("Aufnahme läuft", color = TextDim, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        } else {
                            // Text-Eingabe
                            OutlinedTextField(input, { input = it },
                                placeholder = { Text(s.messageHint, color = TextDim, fontSize = 14.sp) },
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(22.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = BorderColor, focusedBorderColor = AccentGreen.copy(.4f),
                                    unfocusedContainerColor = BgElevated, focusedContainerColor = BgElevated,
                                    unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    if (input.isNotBlank()) { viewModel.sendMessage(contact, input); input = ""; keyboard?.hide() }
                                }), maxLines = 4)
                        }

                        // Rechts: Stop (während Aufnahme) / Senden (Text) / Mikrofon (idle)
                        when {
                            isRecording -> {
                                // Stop-Button — beendet Aufnahme und öffnet Vorschau
                                Box(
                                    Modifier.size(44.dp)
                                        .background(Color(0xFFEF4444), RoundedCornerShape(14.dp))
                                        .clickable {
                                            isRecording = false
                                            val dur = recordElapsedMs
                                            try { recorder.value?.apply { stop(); release() }; recorder.value = null }
                                            catch (_: Exception) { recorder.value = null }
                                            audioFile?.let { f ->
                                                if (f.exists() && dur > 500) {
                                                    previewFile  = f
                                                    previewDurMs = dur
                                                    audioFile    = null
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Stop, null, tint = Color.White,
                                        modifier = Modifier.size(22.dp))
                                }
                            }
                            input.isNotBlank() -> {
                                FloatingActionButton(onClick = {
                                    viewModel.sendMessage(contact, input); input = ""; keyboard?.hide()
                                }, Modifier.size(44.dp), containerColor = AccentGreen, contentColor = Color.Black,
                                    shape = RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp))
                                }
                            }
                            else -> {
                                // Mikrofon-Button — Tippen startet Aufnahme
                                Box(
                                    Modifier.size(44.dp)
                                        .background(BgElevated, RoundedCornerShape(14.dp))
                                        .clickable {
                                            val hasMic = context.checkSelfPermission(
                                                android.Manifest.permission.RECORD_AUDIO) ==
                                                android.content.pm.PackageManager.PERMISSION_GRANTED
                                            if (!hasMic) {
                                                PinSuppressor.suppress(); permLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                                                return@clickable
                                            }
                                            val dir = java.io.File(context.cacheDir, "audio").apply { mkdirs() }
                                            val f   = java.io.File(dir, "rec_${System.currentTimeMillis()}.m4a")
                                            audioFile    = f
                                            recordStartMs = System.currentTimeMillis()
                                            isRecording  = true
                                            try {
                                                recorder.value = android.media.MediaRecorder(context).apply {
                                                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                                                    setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                                                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                                                    setAudioSamplingRate(44100)
                                                    setAudioEncodingBitRate(128000)
                                                    setOutputFile(f.absolutePath)
                                                    prepare(); start()
                                                }
                                            } catch (e: Exception) {
                                                isRecording = false
                                                TorChatLogger.e("Chat", "Aufnahme fehlgeschlagen: ${e.message}")
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Mic, null, tint = AccentGreen,
                                        modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Hintergrundbild (wenn gesetzt)
            if (bgType == "image" && bgImageUri.isNotEmpty()) {
                val imgUri = android.net.Uri.parse(bgImageUri)
                coil.compose.AsyncImage(
                    model = imgUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alpha = 0.35f
                )
            }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(.05f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(.2f)),
                    shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = AccentGreen, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ende-zu-Ende verschlüsselt via Tor", color = AccentGreen,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            items(messages, key = { it.id }) { msg ->
                var showMenu          by remember { mutableStateOf(false) }
                var showDeleteDialog  by remember { mutableStateOf(false) }
                var showForwardPicker by remember { mutableStateOf(false) }
                val clipboardManager  = androidx.compose.ui.platform.LocalClipboardManager.current
                val ctx               = androidx.compose.ui.platform.LocalContext.current
                val allContacts       by viewModel.contacts.collectAsState()

                Box(Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { if (msg.type != MessageType.SYSTEM) showMenu = true }
                )) {
                    when (msg.type) {
                        MessageType.SYSTEM -> SystemMessage(msg.content)
                        MessageType.IMAGE  -> PhotoBubble(msg, groupMembers)
                        MessageType.FILE   -> if (msg.fileName?.endsWith(".m4a") == true ||
                            msg.fileName?.endsWith(".aac") == true) {
                            val audioMessages = messages.filter {
                                it.type == MessageType.FILE &&
                                (it.fileName?.endsWith(".m4a") == true ||
                                 it.fileName?.endsWith(".aac") == true)
                            }
                            val nextAudioId = audioMessages
                                .dropWhile { it.id != msg.id }
                                .drop(1).firstOrNull()?.id
                            AudioBubble(
                                message          = msg,
                                isCurrentlyPlaying = currentlyPlayingId == msg.id,
                                onPlayStateChange  = { playing ->
                                    currentlyPlayingId = if (playing) msg.id else null
                                },
                                onEnded = {
                                    currentlyPlayingId = null
                                    if (nextAudioId != null) {
                                        currentlyPlayingId = nextAudioId
                                    }
                                },
                                groupMembers = groupMembers
                            )
                        } else FileBubble(msg, groupMembers)
                        else               -> MessageBubble(msg, groupMembers)
                    }
                }

                // ── Haupt-Kontextmenü ─────────────────────────────
                if (showMenu) {
                    AlertDialog(
                        onDismissRequest = { showMenu = false },
                        containerColor   = BgCard,
                        title = {
                            Text(
                                when {
                                    msg.isOutgoing -> "✉ Deine Nachricht"
                                    contact.isGroup && msg.senderOnion.isNotEmpty() -> {
                                        val member = groupMembers.find { it.onionAddress == msg.senderOnion }
                                        val name = member?.displayName?.takeIf { it.isNotBlank() }
                                        "✉ ${name ?: "Mitglied"}"
                                    }
                                    else -> "✉ Nachricht von ${contact.name.take(20)}"
                                },
                                color = TextSecondary, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                // KOPIEREN
                                if (msg.content.isNotBlank()) {
                                    MenuAction(
                                        icon = Icons.Default.ContentCopy,
                                        label = "Kopieren",
                                        color = AccentGreen
                                    ) {
                                        clipboardManager.setText(
                                            androidx.compose.ui.text.AnnotatedString(msg.content))
                                        android.widget.Toast.makeText(
                                            ctx, "Kopiert", android.widget.Toast.LENGTH_SHORT).show()
                                        showMenu = false
                                    }
                                }
                                // WEITERLEITEN
                                MenuAction(
                                    icon = Icons.Default.Forward,
                                    label = "Weiterleiten",
                                    color = Color(0xFFa855f7)
                                ) {
                                    showMenu = false
                                    showForwardPicker = true
                                }
                                Divider(color = BorderColor, thickness = 0.5.dp,
                                    modifier = Modifier.padding(vertical = 4.dp))
                                // LÖSCHEN
                                MenuAction(
                                    icon = Icons.Default.Delete,
                                    label = "Löschen",
                                    color = Color(0xFFEF4444)
                                ) {
                                    showMenu = false
                                    showDeleteDialog = true
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showMenu = false }) {
                                Text("Schließen", color = TextDim,
                                    fontFamily = FontFamily.Monospace)
                            }
                        },
                        dismissButton = {}
                    )
                }

                // ── Weiterleitungs-Picker ──────────────────────────
                if (showForwardPicker) {
                    val others = allContacts.filter { it.id != contact.id }
                    AlertDialog(
                        onDismissRequest = { showForwardPicker = false },
                        containerColor   = BgCard,
                        title = {
                            Text("Weiterleiten an...", color = TextPrimary,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        },
                        text = {
                            if (others.isEmpty()) {
                                Text("Keine weiteren Kontakte vorhanden.",
                                    color = TextSecondary, fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace)
                            } else {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.heightIn(max = 320.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(others, key = { it.id }) { target ->
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .background(BgElevated, RoundedCornerShape(10.dp))
                                                .clickable {
                                                    viewModel.forwardMessage(target, msg.content.ifBlank { msg.fileName ?: "" })
                                                    showForwardPicker = false
                                                    android.widget.Toast.makeText(
                                                        ctx,
                                                        "➤ Weitergeleitet an ${target.name}",
                                                        android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(Modifier.size(36.dp)
                                                .background(
                                                    Color(android.graphics.Color.parseColor(
                                                        target.avatarColor)), CircleShape),
                                                contentAlignment = Alignment.Center) {
                                                Text(target.initials, color = Color.Black,
                                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Column {
                                                Text(target.name, color = TextPrimary,
                                                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                Text(target.shortOnion, color = TextDim,
                                                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showForwardPicker = false }) {
                                Text("Abbrechen", color = TextDim,
                                    fontFamily = FontFamily.Monospace)
                            }
                        },
                        dismissButton = {}
                    )
                }

                // ── Löschen-Dialog ─────────────────────────────────
                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        containerColor   = BgCard,
                        title = {
                            Text("🗑 Nachricht löschen", color = TextPrimary,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Text("Wie soll die Nachricht gelöscht werden?",
                                color = TextSecondary, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace)
                        },
                        confirmButton = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 8.dp)) {
                                if (msg.isOutgoing) {
                                    Button(
                                        onClick = {
                                            viewModel.deleteForAll(contact, msg.id)
                                            showDeleteDialog = false
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFEF4444)),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape    = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("🗑 Für alle löschen",
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold)
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.deleteForMe(msg.id)
                                        showDeleteDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape    = RoundedCornerShape(10.dp),
                                    border   = androidx.compose.foundation.BorderStroke(
                                        1.dp, TextSecondary.copy(.4f))
                                ) {
                                    Text("Für mich löschen", color = TextSecondary,
                                        fontFamily = FontFamily.Monospace)
                                }
                                TextButton(
                                    onClick = { showDeleteDialog = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Abbrechen", color = TextDim,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        },
                        dismissButton = {}
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            // ── Freie Fläche: Long-Press → Einfügen ──────────────────
            item {
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                var showPasteHint by remember { mutableStateOf(false) }
                val clipText = clipboardManager.getText()?.text ?: ""

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (clipText.isNotBlank()) showPasteHint = true
                            }
                        )
                )
                if (showPasteHint && clipText.isNotBlank()) {
                    AlertDialog(
                        onDismissRequest = { showPasteHint = false },
                        containerColor = BgCard,
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.ContentPaste, null,
                                    tint = Color(0xFF06B6D4), modifier = Modifier.size(20.dp))
                                Text("Einfügen", color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold)
                            }
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Zwischenablage:", color = TextDim,
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Box(Modifier
                                    .fillMaxWidth()
                                    .background(BgDeep, RoundedCornerShape(8.dp))
                                    .padding(10.dp)) {
                                    Text(
                                        text = if (clipText.length > 120)
                                            clipText.take(120) + "…"
                                        else clipText,
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 4.dp)) {
                                TextButton(onClick = { showPasteHint = false }) {
                                    Text("Abbrechen", color = TextDim,
                                        fontFamily = FontFamily.Monospace)
                                }
                                Button(
                                    onClick = {
                                        viewModel.appendToInput(clipText)
                                        showPasteHint = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF06B6D4)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Einfügen", fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    )
                }
            }
        }
        } // end Box
    }
}

// ─────────────────────────────────────────────
// Gruppenverwaltungs-Dialog
// ─────────────────────────────────────────────
@Composable
fun GroupManagementDialog(
    group:    Contact,
    viewModel: ChatListViewModel,
    onDismiss: () -> Unit
) {
    val s = AppStrings.current
    var members by remember { mutableStateOf<List<com.torchat.app.model.GroupMember>>(emptyList()) }
    var showAddMember by remember { mutableStateOf(false) }
    val allContacts by viewModel.contacts.collectAsState()

    // Bin ich Admin dieser Gruppe?
    val myOnion = viewModel.myOnionAddress
    val iAmAdmin by remember(members, myOnion) {
        derivedStateOf {
            members.any { it.onionAddress == myOnion && it.isAdmin }
        }
    }

    // Mitglieder laden
    LaunchedEffect(group.id) {
        viewModel.getGroupMembers(group.id) { members = it }
    }

    // Mitglied-Hinzufügen Dialog
    if (showAddMember) {
        val eligible = allContacts.filter { c ->
            !c.isGroup && members.none { m -> m.contactId == c.id }
        }
        AlertDialog(
            onDismissRequest = { showAddMember = false },
            containerColor = BgCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PersonAdd, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                    Text("Mitglied hinzufügen", color = TextPrimary,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                if (eligible.isEmpty()) {
                    Text("Alle Kontakte sind bereits Mitglied.", color = TextSecondary,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(eligible) { contact ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(BgElevated, RoundedCornerShape(10.dp))
                                    .clickable {
                                        viewModel.addMemberToGroup(group, contact)
                                        viewModel.getGroupMembers(group.id) { members = it }
                                        showAddMember = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val ac = try { Color(android.graphics.Color.parseColor(
                                    contact.avatarColor.ifEmpty { "#00ff9d" })) }
                                catch (_: Exception) { AccentGreen }
                                Box(Modifier.size(36.dp).background(ac.copy(.15f), CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Text(contact.initials, color = ac, fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                                Column {
                                    Text(contact.name, color = TextPrimary, fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                    Text(contact.shortOnion, color = TextDim, fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddMember = false }) {
                    Text("Abbrechen", color = TextDim, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    // Hauptdialog Gruppenverwaltung
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AccentPurple.copy(.3f))
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier.fillMaxWidth()
                        .background(AccentPurple.copy(.08f))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.ManageAccounts, null, tint = AccentPurple,
                        modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, color = TextPrimary, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${members.size} Mitglieder", color = AccentPurple,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = TextDim)
                    }
                }

                // Mitglied hinzufügen Button – nur für Admin
                if (iAmAdmin) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .background(AccentGreen.copy(.08f), RoundedCornerShape(12.dp))
                        .clickable { showAddMember = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, null, tint = AccentGreen,
                        modifier = Modifier.size(20.dp))
                    Text("Mitglied hinzufügen", color = AccentGreen,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                        fontSize = 13.sp)
                }
                }

                Divider(color = BorderColor, thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp))

                // Mitgliederliste
                Text("MITGLIEDER", color = TextDim, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(members, key = { it.id }) { member ->
                        var showActions by remember { mutableStateOf(false) }
                        val borderColor = when {
                            member.isBlockedInGroup -> Color(0xFFEF4444).copy(.3f)
                            member.isAdmin          -> AccentPurple.copy(.3f)
                            else                    -> BorderColor
                        }

                        // Aktions-Dropdown für dieses Mitglied
                        if (showActions) {
                            val isSelf = member.onionAddress == myOnion
                            AlertDialog(
                                onDismissRequest = { showActions = false },
                                containerColor = BgCard,
                                title = {
                                    Text(member.displayName.ifEmpty { "Mitglied" },
                                        color = TextPrimary, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold)
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (isSelf) {
                                            // Eigener Eintrag: nur "Gruppe verlassen"
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .background(Color(0xFFEF4444).copy(.15f), RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        viewModel.removeMemberFromGroup(group, member)
                                                        showActions = false
                                                        onDismiss()
                                                    }
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(Icons.Default.ExitToApp, null,
                                                    tint = Color(0xFFEF4444),
                                                    modifier = Modifier.size(18.dp))
                                                Text("Gruppe verlassen",
                                                    color = Color(0xFFEF4444), fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold)
                                            }
                                        } else if (iAmAdmin) {
                                            // Admin-Aktionen für andere Mitglieder
                                            // Admin-Status
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .background(AccentPurple.copy(.08f), RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        viewModel.setMemberAdmin(group, member, !member.isAdmin)
                                                        viewModel.getGroupMembers(group.id) { members = it }
                                                        showActions = false
                                                    }
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    if (member.isAdmin) Icons.Default.RemoveModerator
                                                    else Icons.Default.AdminPanelSettings,
                                                    null, tint = AccentPurple,
                                                    modifier = Modifier.size(18.dp))
                                                Text(
                                                    if (member.isAdmin) "Admin-Status entfernen"
                                                    else "Zum Admin ernennen",
                                                    color = AccentPurple, fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace)
                                            }
                                            // Blockieren / Entsperren
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .background(Color(0xFFEF4444).copy(.08f), RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        if (member.isBlockedInGroup)
                                                            viewModel.unblockMemberInGroup(group, member)
                                                        else
                                                            viewModel.blockMemberInGroup(group, member)
                                                        viewModel.getGroupMembers(group.id) { members = it }
                                                        showActions = false
                                                    }
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    if (member.isBlockedInGroup) Icons.Default.LockOpen
                                                    else Icons.Default.Block,
                                                    null, tint = Color(0xFFEF4444),
                                                    modifier = Modifier.size(18.dp))
                                                Text(
                                                    if (member.isBlockedInGroup) "Entsperren"
                                                    else "In Gruppe blockieren",
                                                    color = Color(0xFFEF4444), fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace)
                                            }
                                            // Aus Gruppe entfernen
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .background(Color(0xFFEF4444).copy(.15f), RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        viewModel.removeMemberFromGroup(group, member)
                                                        viewModel.getGroupMembers(group.id) { members = it }
                                                        showActions = false
                                                    }
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(Icons.Default.PersonRemove, null,
                                                    tint = Color(0xFFEF4444),
                                                    modifier = Modifier.size(18.dp))
                                                Text("Aus Gruppe entfernen",
                                                    color = Color(0xFFEF4444), fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        TextButton(
                                            onClick = { showActions = false },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Abbrechen", color = TextDim,
                                                fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                },
                                confirmButton = {}
                            )
                        }

                        // Mitglieder-Zeile
                        Row(
                            Modifier.fillMaxWidth()
                                .background(BgElevated, RoundedCornerShape(12.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                .clickable { showActions = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Avatar
                            Box(Modifier.size(38.dp)
                                .background(AccentPurple.copy(.15f), CircleShape),
                                contentAlignment = Alignment.Center) {
                                val initials = member.displayName.split(" ")
                                    .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                    .joinToString("").ifEmpty { "?" }
                                Text(initials, color = AccentPurple, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                            // Info
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(member.displayName.ifEmpty { member.onionAddress.take(12) },
                                        color = if (member.isBlockedInGroup) TextDim else TextPrimary,
                                        fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium)
                                    if (member.isAdmin)
                                        Text("ADMIN", color = AccentPurple, fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier
                                                .background(AccentPurple.copy(.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp))
                                    if (member.isBlockedInGroup)
                                        Text("BLOCKIERT", color = Color(0xFFEF4444), fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier
                                                .background(Color(0xFFEF4444).copy(.12f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                                Text(member.onionAddress.take(20) + "...", color = TextDim,
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Icon(Icons.Default.MoreVert, null, tint = TextDim,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
fun MenuAction(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    label:   String,
    color:   Color,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(.07f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, color = color, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun AttachOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String,
                  subtitle: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .background(BgElevated, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(42.dp).background(color.copy(.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// Hilfsfunktionen für Kamera und Dateipfad
fun createCameraImageUri(context: android.content.Context): android.net.Uri? {
    return try {
        val dir = java.io.File(context.cacheDir, "camera").also { it.mkdirs() }
        val file = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file)
    } catch (e: Exception) { null }
}

fun getPathFromUri(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        val dir = java.io.File(context.cacheDir, "media").also { it.mkdirs() }
        val file = java.io.File(dir, "img_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.absolutePath
    } catch (e: Exception) { null }
}

// ─────────────────────────────────────────────
// Foto-Bubble
// ─────────────────────────────────────────────
@Composable
fun PhotoBubble(message: Message, groupMembers: List<com.torchat.app.model.GroupMember> = emptyList()) {
    val isMe = message.isOutgoing
    val time = remember(message.timestamp) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.timestamp)) }
    var showFull by remember { mutableStateOf(false) }

    // Absender-Label für Gruppennachrichten
    val senderLabel: String? = if (!isMe && message.senderOnion.isNotEmpty()) {
        val member = groupMembers.find { it.onionAddress == message.senderOnion }
        member?.displayName?.takeIf { it.isNotBlank() } ?: "Mitglied"
    } else null

    // ── Vollbild-Viewer mit Pinch-to-Zoom ──────────────────────────
    if (showFull && message.filePath != null) {
        Dialog(
            onDismissRequest = { showFull = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside   = true
            )
        ) {
            var scale        by remember { mutableStateOf(1f) }
            var offsetX      by remember { mutableStateOf(0f) }
            var offsetY      by remember { mutableStateOf(0f) }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // Doppel-Tap: zwischen 1x und 2.5x wechseln
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale   = if (scale > 1.2f) 1f else 2.5f
                                offsetX = 0f; offsetY = 0f
                            },
                            onTap = { showFull = false }
                        )
                    }
                    // Pinch-to-Zoom + Panning
                    .pointerInput(Unit) {
                        detectTransformGestures { _: androidx.compose.ui.geometry.Offset,
                            pan: androidx.compose.ui.geometry.Offset,
                            zoom: Float, _: Float ->
                            val newScale = (scale * zoom).coerceIn(0.5f, 5f)
                            scale = newScale
                            if (newScale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f; offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                coil.compose.AsyncImage(
                    model = message.filePath,
                    contentDescription = "Foto",
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(
                            scaleX          = scale,
                            scaleY          = scale,
                            translationX    = offsetX,
                            translationY    = offsetY
                        ),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
                // Schließen-Button oben rechts
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(36.dp)
                        .background(Color.Black.copy(.5f), RoundedCornerShape(18.dp))
                        .clickable { showFull = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White,
                        modifier = Modifier.size(20.dp))
                }
                // Zoom-Hinweis (nur bei 1x)
                if (scale <= 1.05f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                            .background(Color.Black.copy(.5f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Doppeltippen oder Pinch zum Zoomen",
                            color = Color.White.copy(.8f), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.widthIn(max = 260.dp)
                .background(if (isMe) AccentGreen.copy(.12f) else BgElevated,
                    RoundedCornerShape(18.dp, 18.dp, if (isMe) 4.dp else 18.dp, if (isMe) 18.dp else 4.dp)),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
            if (senderLabel != null) {
                Text(senderLabel, color = AccentPurple, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 2.dp))
            }
            if (message.filePath != null) {
                coil.compose.AsyncImage(
                    model = message.filePath,
                    contentDescription = "Foto",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                        .clickable { showFull = true },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxWidth().height(160.dp)
                    .background(BgDeep), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.BrokenImage, null, tint = TextDim, modifier = Modifier.size(40.dp))
                }
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Lock, null, tint = AccentGreen.copy(.6f), modifier = Modifier.size(9.dp))
                Text(time, color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                if (isMe) Icon(
                    if (message.status == MessageStatus.READ) Icons.Default.DoneAll else Icons.Default.Done,
                    null, tint = if (message.status == MessageStatus.READ) AccentGreen else TextDim,
                    modifier = Modifier.size(13.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────
// Audio-Bubble  (Sprachnachricht)
// ─────────────────────────────────────────────
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun AudioBubble(
    message: Message,
    isCurrentlyPlaying: Boolean = false,
    onPlayStateChange: (Boolean) -> Unit = {},
    onEnded: () -> Unit = {},
    groupMembers: List<com.torchat.app.model.GroupMember> = emptyList()
) {
    val isMe    = message.isOutgoing
    val context = androidx.compose.ui.platform.LocalContext.current
    val time    = remember(message.timestamp) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(message.timestamp)) }

    // Absender-Label für Gruppennachrichten
    val senderLabel: String? = if (!isMe && message.senderOnion.isNotEmpty()) {
        val member = groupMembers.find { it.onionAddress == message.senderOnion }
        member?.displayName?.takeIf { it.isNotBlank() } ?: "Mitglied"
    } else null

    var isPlaying   by remember { mutableStateOf(false) }
    var progress    by remember { mutableStateOf(0f) }
    var durationMs  by remember { mutableStateOf(1L) }
    var volume      by remember { mutableStateOf(1f) }      // 0..1
    var showVolume  by remember { mutableStateOf(false) }
    var playEnded   by remember { mutableStateOf(false) }   // für Replay-Fix

    val player = remember(message.filePath) {
        if (message.filePath == null) null
        else androidx.media3.exoplayer.ExoPlayer.Builder(context).build().also { p ->
            p.setMediaItem(
                androidx.media3.common.MediaItem.fromUri(
                    android.net.Uri.fromFile(java.io.File(message.filePath))))
            p.prepare()
            p.volume = 1f
            // Listener: Wiedergabe-Ende erkennen
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_ENDED) {
                        isPlaying  = false
                        playEnded  = true
                        progress   = 1f
                        onPlayStateChange(false)
                        onEnded()
                    }
                }
            })
        }
    }

    DisposableEffect(Unit) { onDispose { player?.release() } }

    // Auto-Start wenn von außen (Auto-Next) ausgelöst
    LaunchedEffect(isCurrentlyPlaying) {
        if (isCurrentlyPlaying && !isPlaying && player != null) {
            if (playEnded) {
                player.seekTo(0); player.prepare(); playEnded = false; progress = 0f
            }
            player.play(); isPlaying = true
        }
    }

    // Fortschritt-Polling
    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            val dur = player.duration.takeIf { it > 0 } ?: 1L
            durationMs = dur
            progress = player.currentPosition.toFloat() / dur
            kotlinx.coroutines.delay(100)
        }
    }

    fun togglePlay() {
        player ?: return
        if (isPlaying) {
            player.pause()
            isPlaying = false
            onPlayStateChange(false)
        } else {
            if (playEnded) {
                player.seekTo(0)
                player.prepare()
                playEnded = false
                progress  = 0f
            }
            player.play()
            isPlaying = true
            onPlayStateChange(true)
        }
    }

    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start) {

        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    if (isMe) AccentGreen.copy(.12f) else BgElevated,
                    RoundedCornerShape(18.dp, 18.dp,
                        if (isMe) 4.dp else 18.dp, if (isMe) 18.dp else 4.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (senderLabel != null) {
                Text(senderLabel, color = AccentPurple, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── Play/Pause ──────────────────────────────────────
                Box(
                    Modifier.size(36.dp)
                        .background(AccentGreen.copy(.2f), CircleShape)
                        .clickable { togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // ── Seek-Slider ─────────────────────────────────
                    androidx.compose.material3.Slider(
                        value = progress,
                        onValueChange = { pos ->
                            progress = pos
                            val seekMs = (pos * durationMs).toLong()
                            player?.seekTo(seekMs)
                            if (playEnded && pos < 1f) {
                                playEnded = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor         = AccentGreen,
                            activeTrackColor   = AccentGreen,
                            inactiveTrackColor = AccentGreen.copy(.25f)
                        )
                    )

                    // ── Zeit + Dauer ────────────────────────────────
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatDuration((progress * durationMs).toLong()),
                            color = TextDim, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                        Text(
                            formatDuration(durationMs),
                            color = TextDim, fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }

                // ── Lautstärke-Button ───────────────────────────────
                Box(
                    Modifier.size(28.dp)
                        .clickable { showVolume = !showVolume },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when {
                            volume == 0f  -> Icons.Default.VolumeOff
                            volume < 0.5f -> Icons.Default.VolumeDown
                            else          -> Icons.Default.VolumeUp
                        },
                        null, tint = AccentGreen.copy(.7f),
                        modifier = Modifier.size(16.dp))
                }
            }

            // ── Lautstärke-Slider (ein/ausklappbar) ─────────────────
            if (showVolume) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.VolumeDown, null, tint = TextDim,
                        modifier = Modifier.size(14.dp))
                    androidx.compose.material3.Slider(
                        value = volume,
                        onValueChange = { v ->
                            volume = v
                            player?.volume = v
                        },
                        modifier = Modifier.weight(1f).height(20.dp),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor         = Color(0xFF06B6D4),
                            activeTrackColor   = Color(0xFF06B6D4),
                            inactiveTrackColor = Color(0xFF06B6D4).copy(.25f)
                        )
                    )
                    Icon(Icons.Default.VolumeUp, null, tint = TextDim,
                        modifier = Modifier.size(14.dp))
                }
            }

            // ── Zeitstempel ──────────────────────────────────────────
            Row(
                Modifier.align(if (isMe) Alignment.End else Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(Icons.Default.Lock, null, tint = AccentGreen.copy(.6f),
                    modifier = Modifier.size(9.dp))
                Text(time, color = TextDim, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
                if (isMe) MessageStatusIcon(message.status)
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ─────────────────────────────────────────────
// Datei-Bubble (sonstige Dateien)
// ─────────────────────────────────────────────
@Composable
fun FileBubble(message: Message, groupMembers: List<com.torchat.app.model.GroupMember> = emptyList()) {
    val isMe = message.isOutgoing
    val time = remember(message.timestamp) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.timestamp)) }
    val senderLabel: String? = if (!isMe && message.senderOnion.isNotEmpty()) {
        val member = groupMembers.find { it.onionAddress == message.senderOnion }
        member?.displayName?.takeIf { it.isNotBlank() } ?: "Mitglied"
    } else null

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 260.dp)
            .background(if (isMe) AccentGreen.copy(.12f) else BgElevated,
                RoundedCornerShape(18.dp, 18.dp, if (isMe) 4.dp else 18.dp, if (isMe) 18.dp else 4.dp))
            .padding(12.dp)) {
            if (senderLabel != null) {
                Text(senderLabel, color = AccentPurple, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).background(Color(0xFF38bdf8).copy(.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.InsertDriveFile, null, tint = Color(0xFF38bdf8), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(message.fileName ?: message.content, color = TextPrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message.fileSize?.let { "${it/1024} KB" } ?: "", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Icon(Icons.Default.Lock, null, tint = AccentGreen.copy(.6f), modifier = Modifier.size(9.dp))
                    Text(time, color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
            }
        }
    }
}

/**
 * Status-Icon für ausgehende Nachrichten:
 * ⏳ SENDING  — Uhr (grau)
 * ✓  SENT     — 1 Häkchen (grau)
 * ✓✓ DELIVERED — 2 Häkchen (blau) ← Empfänger hat empfangen
 * ✓✓ READ     — 2 Häkchen (grün)  ← Empfänger hat gelesen
 * ✗  FAILED   — X (grau)
 * ✗  OFFLINE  — 1 Häkchen (rot)   ← Empfänger offline, in Queue
 */
@Composable
fun MessageStatusIcon(status: MessageStatus, size: androidx.compose.ui.unit.Dp = 13.dp) {
    when (status) {
        MessageStatus.SENDING   ->
            Icon(Icons.Default.Schedule, null,
                tint = TextDim, modifier = Modifier.size(size))
        MessageStatus.SENT      ->
            Icon(Icons.Default.Done, null,
                tint = TextDim.copy(.8f), modifier = Modifier.size(size))
        MessageStatus.DELIVERED ->
            Icon(Icons.Default.DoneAll, null,
                tint = Color(0xFF2196F3), modifier = Modifier.size(size)) // Blau
        MessageStatus.READ      ->
            Icon(Icons.Default.DoneAll, null,
                tint = AccentGreen, modifier = Modifier.size(size)) // Grün
        MessageStatus.OFFLINE   ->
            Icon(Icons.Default.Done, null,
                tint = Color(0xFFEF4444), modifier = Modifier.size(size)) // Rot
        MessageStatus.FAILED    ->
            Icon(Icons.Default.ErrorOutline, null,
                tint = Color(0xFFEF4444).copy(.7f), modifier = Modifier.size(size))
    }
}

@Composable
fun SystemMessage(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.background(BgElevated, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun MessageBubble(message: Message, groupMembers: List<com.torchat.app.model.GroupMember> = emptyList()) {
    val isMe = message.isOutgoing
    val time = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)) }

    // Absender-Anzeige: nur bei eingehenden Gruppenachrichten
    val senderLabel: String? = if (!isMe && message.senderOnion.isNotEmpty()) {
        val member = groupMembers.find { it.onionAddress == message.senderOnion }
        member?.displayName?.takeIf { it.isNotBlank() } ?: "Mitglied"
    } else null

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start) {
        Box(Modifier.widthIn(max = 300.dp)
            .background(if (isMe) AccentGreen.copy(.12f) else BgElevated,
                RoundedCornerShape(18.dp, 18.dp, if (isMe) 4.dp else 18.dp, if (isMe) 18.dp else 4.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp)) {
            Column {
                // Absender-Zeile über dem Text (nur bei eingehenden Gruppennachrichten)
                if (senderLabel != null) {
                    Text(
                        senderLabel,
                        color = AccentPurple,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                Text(message.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = AccentGreen.copy(.6f), modifier = Modifier.size(9.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(time, color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    if (isMe) {
                        Spacer(Modifier.width(4.dp))
                        MessageStatusIcon(message.status)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Settings Screen  –  3 Tabs
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, listVm: ChatListViewModel? = null, onBack: () -> Unit, onDiagnostics: () -> Unit = {}) {
    val s = AppStrings.current
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(s.tabGeneral, s.tabNetwork, s.tabPrivacy, s.tabContacts, s.tabLog)

    Scaffold(containerColor = BgDeep,
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().background(BgDeep)
                    .padding(start = 4.dp, top = 8.dp, end = 12.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
                    }
                    Text(s.settings, color = AccentGreen, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor   = BgDeep,
                    contentColor     = AccentGreen,
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = AccentGreen)
                    },
                    divider = { Divider(color = BorderColor) }
                ) {
                    tabs.forEachIndexed { i, title ->
                        Tab(
                            selected  = selectedTab == i,
                            onClick   = { selectedTab = i },
                            text = {
                                Text(title,
                                    color = if (selectedTab == i) AccentGreen else TextSecondary,
                                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal)
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> SettingsTabAllgemein(viewModel, padding, onDiagnostics)
            1 -> SettingsTabNetzwerk(viewModel, padding)
            2 -> SettingsTabDatenschutz(viewModel, padding)
            3 -> SettingsTabKontakte(listVm ?: return@Scaffold, padding)
            4 -> SettingsTabLog(padding)
        }
    }
}

// ═══════════════════════════════════════
// Tab 1: ALLGEMEIN  (Identität + Sicherheit + App-Info)
// ═══════════════════════════════════════
@Composable
fun SettingsTabAllgemein(
    viewModel: SettingsViewModel,
    padding: PaddingValues,
    onDiagnostics: () -> Unit
) {
    val s = AppStrings.current
    var showMyQr         by remember { mutableStateOf(false) }
    var showManualOnion  by remember { mutableStateOf(false) }
    var manualOnionInput by remember { mutableStateOf("") }
    val myOnion          by viewModel.myOnionAddressFlow.collectAsState()

    if (showMyQr) QrDialog("Meine Onion-Adresse",
        "torchat:$myOnion") { showMyQr = false }

    if (showManualOnion) {
        AlertDialog(
            onDismissRequest = { showManualOnion = false },
            containerColor   = BgCard,
            title = { Text(s.enterOnion, color = AccentGreen,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.onionAutoHint,
                        color = TextSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = manualOnionInput, onValueChange = { manualOnionInput = it },
                        placeholder = { Text(s.onionHint, color = TextDim,
                            fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = BorderColor, focusedBorderColor = AccentGreen.copy(.5f),
                            unfocusedContainerColor = BgElevated, focusedContainerColor = BgElevated,
                            unfocusedTextColor = AccentGreen, focusedTextColor = AccentGreen,
                            unfocusedLabelColor = TextSecondary, focusedLabelColor = AccentGreen),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (manualOnionInput.isNotBlank())
                        viewModel.updateOnionAddress(manualOnionInput.trim())
                    showManualOnion = false
                }) { Text(s.save, color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = { showManualOnion = false }) {
                    Text(s.cancel, color = TextSecondary)
                }
            }
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        item { Spacer(Modifier.height(4.dp)) }


        // Sprache
        item {
            Text(s.language, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item {
            val langState by AppStrings.language.collectAsState()
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🌐", fontSize = 20.sp)
                    Column(Modifier.weight(1f)) {
                        Text(s.language, color = TextPrimary, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text(s.languageSub, color = TextSecondary,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // DE Button
                        val deSelected = langState == AppLanguage.DE
                        Box(Modifier
                            .background(
                                if (deSelected) AccentGreen.copy(.2f) else BgElevated,
                                RoundedCornerShape(8.dp))
                            .clickable { viewModel.setLanguage(AppLanguage.DE) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("🇩🇪 DE",
                                color = if (deSelected) AccentGreen else TextSecondary,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace)
                        }
                        // EN Button
                        val enSelected = langState == AppLanguage.EN
                        Box(Modifier
                            .background(
                                if (enSelected) AccentGreen.copy(.2f) else BgElevated,
                                RoundedCornerShape(8.dp))
                            .clickable { viewModel.setLanguage(AppLanguage.EN) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("🇬🇧 EN",
                                color = if (enSelected) AccentGreen else TextSecondary,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Sicherheit (PIN)
        item {
            Text(s.sectionSecurity, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item { PinSettingsCard(viewModel) }

        // ── Erscheinungsbild ─────────────────────────────────────────
        item {
            Text("ERSCHEINUNGSBILD", color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item { BackgroundSettingsCard() }

        // Anonymität
        item {
            Text(s.sectionAnonymity, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth().background(AccentGreen.copy(.04f))
                    .padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🧅", fontSize = 20.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.torNetwork, color = AccentGreen, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text("Alle Verbindungen laufen über Tor.\nKeine IP-Adressen werden übertragen.\nKein DNS – nur .onion-Routing.",
                            color = TextSecondary, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                    }
                }
                Divider(color = BorderColor)
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🔑", fontSize = 20.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.keyManagement, color = TextPrimary, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text(s.keyManagementSub,
                            color = TextSecondary, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                    }
                }
            }
        }

        // App Info
        item {
            Text(s.sectionAppInfo, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item {
            SettingsCard {
                SettingsInfoRow("🧅", s.appName, "Version 1.0.0")
                Divider(color = BorderColor)
                SettingsInfoRow("🛡️", s.encryption, s.e2eSub)
                Divider(color = BorderColor)
                SettingsInfoRow("📖", s.license, s.licenseValue)
                Divider(color = BorderColor)
                Row(Modifier.fillMaxWidth().clickable { onDiagnostics() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("🔍", fontSize = 20.sp)
                    Column(Modifier.weight(1f)) {
                        Text(s.diagnostics, color = TextPrimary, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text(s.diagnosticsSub, color = TextSecondary,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = TextSecondary,
                        modifier = Modifier.size(16.dp))
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}
// ═══════════════════════════════════════
@Composable
fun SettingsTabNetzwerk(viewModel: SettingsViewModel, padding: PaddingValues) {
    val s = AppStrings.current
    val torStatus    by viewModel.torStatus.collectAsState()
    val bootstrapPct by viewModel.bootstrapPercent.collectAsState()
    var showMyQr         by remember { mutableStateOf(false) }
    var showManualOnion  by remember { mutableStateOf(false) }
    var manualOnionInput by remember { mutableStateOf("") }
    val myOnion          by viewModel.myOnionAddressFlow.collectAsState()

    if (showMyQr) QrDialog("Meine Onion-Adresse",
        "torchat:$myOnion") { showMyQr = false }

    if (showManualOnion) {
        AlertDialog(
            onDismissRequest = { showManualOnion = false },
            containerColor   = BgCard,
            title = { Text(s.enterOnion, color = AccentGreen,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.onionAutoHint,
                        color = TextSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = manualOnionInput, onValueChange = { manualOnionInput = it },
                        placeholder = { Text(s.onionHint, color = TextDim,
                            fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = BorderColor, focusedBorderColor = AccentGreen.copy(.5f),
                            unfocusedContainerColor = BgElevated, focusedContainerColor = BgElevated,
                            unfocusedTextColor = AccentGreen, focusedTextColor = AccentGreen),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (manualOnionInput.isNotBlank())
                        viewModel.updateOnionAddress(manualOnionInput.trim())
                    showManualOnion = false
                }) { Text(s.save, color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = { showManualOnion = false }) {
                    Text(s.cancel, color = TextSecondary)
                }
            }
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        item { Spacer(Modifier.height(4.dp)) }

        // Meine Identität
        item {
            Text(s.sectionIdentity, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item {
            val clipboardMgr = androidx.compose.ui.platform.LocalClipboardManager.current
            val ctx2 = androidx.compose.ui.platform.LocalContext.current
            Card(colors = CardDefaults.cardColors(containerColor = BgCard),
                border = androidx.compose.foundation.BorderStroke(1.dp,
                    if (myOnion.isNotEmpty()) AccentGreen.copy(.3f) else BorderColor),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.myOnion, color = TextDim, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    // Antippen → Adresse in Zwischenablage kopieren
                    if (myOnion.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(AccentGreen.copy(.05f), RoundedCornerShape(8.dp))
                                .clickable {
                                    clipboardMgr.setText(
                                        androidx.compose.ui.text.AnnotatedString(myOnion))
                                    android.widget.Toast.makeText(
                                        ctx2, "✅ Adresse kopiert", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(myOnion,
                                color = AccentGreen,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ContentCopy, null,
                                tint = AccentGreen.copy(.6f),
                                modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Text(s.torConnecting,
                            color = TextSecondary,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Row(Modifier.fillMaxWidth()
                            .background(AccentOrange.copy(.08f), RoundedCornerShape(8.dp))
                            .padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("📋", fontSize = 14.sp)
                            Text(s.torAddressHint,
                                color = AccentOrange, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                        }
                    }
                    // ── Anzeigename ──────────────────────────────────
                    Divider(color = BorderColor)
                    DisplayNameEditor(viewModel = viewModel)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showMyQr = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(.4f)),
                            shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.Default.QrCode, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.showQr, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        OutlinedButton(onClick = { showManualOnion = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentOrange),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentOrange.copy(.4f)),
                            shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.manualInput, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                }
            }
        }


        // Tor-Status
        item {
            Text(s.sectionTorStatus, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(AccentGreen.copy(.12f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        Text("🧅", fontSize = 22.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s.torNetwork, color = TextPrimary,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(when (torStatus) {
                            TorStatus.CONNECTED    -> "✅ Verbunden"
                            TorStatus.CONNECTING   -> if (bootstrapPct > 0) "⏳ Bootstrap $bootstrapPct%..."
                                                      else "⏳ Startet..."
                            TorStatus.ERROR        -> "⚠️ Fehler beim Starten"
                            TorStatus.DISCONNECTED -> "⭕ Nicht verbunden"
                        }, color = when (torStatus) {
                            TorStatus.CONNECTED  -> AccentGreen
                            TorStatus.CONNECTING -> Color(0xFFF59E0B)
                            else                 -> AccentOrange
                        }, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        if (torStatus == TorStatus.CONNECTING && bootstrapPct > 0) {
                            LinearProgressIndicator(
                                progress = bootstrapPct / 100f,
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = AccentGreen, trackColor = BgElevated)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (torStatus == TorStatus.CONNECTED) {
                            IconButton(onClick = { viewModel.newCircuit() },
                                modifier = Modifier.size(36.dp)
                                    .background(AccentGreen.copy(.1f), RoundedCornerShape(10.dp))) {
                                Icon(Icons.Default.Shuffle, s.newRoute,
                                    tint = AccentGreen, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = { viewModel.retryTorConnection() },
                            modifier = Modifier.size(36.dp)
                                .background(BgElevated, RoundedCornerShape(10.dp))) {
                            Icon(Icons.Default.Refresh, "Neu starten",
                                tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (myOnion.isNotEmpty()) {
                    Divider(color = BorderColor)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Link, null, tint = AccentGreen.copy(.5f),
                            modifier = Modifier.size(14.dp))
                        Text(myOnion, color = AccentGreen.copy(.7f),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Ports
        item {
            Text(s.sectionPorts, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item { PortSettingsCard(viewModel) }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ═══════════════════════════════════════
// Tab 3: DATENSCHUTZ
// ═══════════════════════════════════════
@Composable
fun SettingsTabDatenschutz(viewModel: SettingsViewModel, padding: PaddingValues) {
    val s = AppStrings.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var disappearing  by remember { mutableStateOf(
        com.torchat.app.data.SettingsManager.isDisappearingEnabled(ctx)) }
    var stealthMode   by remember { mutableStateOf(
        com.torchat.app.data.SettingsManager.isStealthEnabled(ctx)) }
    var notifications by remember { mutableStateOf(
        com.torchat.app.data.SettingsManager.isNotificationsEnabled(ctx)) }
    val pinActive     = viewModel.isPinActive

    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        item { Spacer(Modifier.height(4.dp)) }

        item {
            Text(s.sectionPrivacy, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        item {
            SettingsCard {
                SettingsToggleRow("🔒", s.e2eEncryption, s.e2eSub, true) {}
                Divider(color = BorderColor)
                SettingsToggleRow("💨", s.disappearing, s.disappearingSub,
                    disappearing) { disappearing = it;
                    com.torchat.app.data.SettingsManager.setDisappearing(ctx, it) }
                Divider(color = BorderColor)
                SettingsToggleRow("🕵️", s.stealth, s.stealthSub,
                    stealthMode) { stealthMode = it;
                    com.torchat.app.data.SettingsManager.setStealth(ctx, it) }
                Divider(color = BorderColor)
                SettingsToggleRow("🔔", s.notifications, s.notificationsSub,
                    checked  = if (pinActive) false else notifications,
                    enabled  = !pinActive
                ) { v ->
                    if (!pinActive) {
                        notifications = v
                        com.torchat.app.data.SettingsManager.setNotifications(ctx, v)
                    }
                }
                if (pinActive) {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 56.dp, end = 16.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Lock, null,
                            tint = Color(0xFFf59e0b), modifier = Modifier.size(12.dp))
                        Text(
                            "PIN aktiv — Benachrichtigungen automatisch deaktiviert",
                            color = Color(0xFFf59e0b), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Divider(color = BorderColor)
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("☁️", fontSize = 20.sp)
                    Column(Modifier.weight(1f)) {
                        Text(s.cloudBackup, color = TextPrimary, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text(s.cloudBackupSub,
                            color = AccentGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Icon(Icons.Default.Block, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ─────────────────────────────────────────────
// PIN Einstellungen Card
// ─────────────────────────────────────────────
@Composable
fun PinSettingsCard(viewModel: SettingsViewModel) {
    val s = AppStrings.current
    var showSetPin    by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var showRemovePin by remember { mutableStateOf(false) }
    var pinActive     by remember { mutableStateOf(viewModel.isPinActive) }

    // Dialog: PIN setzen
    if (showSetPin) {
        PinSetupDialog(
            title       = "PIN festlegen",
            confirmText = s.save,
            onConfirm   = { pin ->
                if (viewModel.setPin(pin)) { pinActive = true; showSetPin = false }
            },
            onDismiss = { showSetPin = false }
        )
    }
    // Dialog: PIN ändern
    if (showChangePin) {
        PinChangeDialog(
            onConfirm = { old, new ->
                if (viewModel.changePin(old, new)) showChangePin = false
            },
            onDismiss = { showChangePin = false }
        )
    }
    // Dialog: PIN entfernen
    if (showRemovePin) {
        PinVerifyDialog(
            title       = s.removePin,
            confirmText = "Entfernen",
            onConfirm   = { pin ->
                if (viewModel.removePin(pin)) { pinActive = false; showRemovePin = false }
            },
            onDismiss = { showRemovePin = false }
        )
    }

    SettingsCard {
        // PIN-Schutz Toggle
        Row(Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(Color(0xFFa855f7).copy(.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, null, tint = Color(0xFFa855f7), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("App-PIN", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (pinActive) "Aktiv – App ist geschützt" else "Deaktiviert",
                    color = if (pinActive) Color(0xFFa855f7) else TextSecondary,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Switch(pinActive,
                onCheckedChange = { enable ->
                    if (enable) showSetPin = true
                    else showRemovePin = true
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor  = Color(0xFFa855f7),
                    checkedTrackColor  = Color(0xFFa855f7).copy(.3f),
                    uncheckedThumbColor = TextDim,
                    uncheckedTrackColor = BgElevated))
        }
        if (pinActive) {
            Divider(color = BorderColor)
            Row(Modifier.fillMaxWidth().clickable { showChangePin = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🔑", fontSize = 18.sp)
                Text(s.changePin, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun PinSetupDialog(title: String, confirmText: String,
                    onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val s = AppStrings.current
    var pin1  by remember { mutableStateOf("") }
    var pin2  by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = BgCard,
        title = { Text(title, color = Color(0xFFa855f7), fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mindestens 4 Ziffern.", color = TextSecondary, fontSize = 12.sp)
                OutlinedTextField(pin1, { pin1 = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text(s.pinNew, color = TextSecondary) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = pinFieldColors())
                OutlinedTextField(pin2, { pin2 = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("PIN wiederholen", color = TextSecondary) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = pinFieldColors())
                if (error.isNotEmpty()) Text(error, color = Color(0xFFEF4444), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    pin1.length < 4         -> "PIN muss mind. 4 Ziffern haben"
                    pin1 != pin2            -> s.pinMismatch
                    else -> { onConfirm(pin1); return@TextButton }
                }
            }) { Text(confirmText, color = Color(0xFFa855f7)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel, color = TextSecondary) } }
    )
}

@Composable
fun PinVerifyDialog(title: String, confirmText: String,
                     onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val s = AppStrings.current
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = BgCard,
        title = { Text(title, color = Color(0xFFa855f7), fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pin, { pin = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text(s.pinCurrent, color = TextSecondary) },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = pinFieldColors())
                if (error) Text(s.pinWrong, color = Color(0xFFEF4444), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }) { Text(confirmText, color = Color(0xFFEF4444)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel, color = TextSecondary) } }
    )
}

@Composable
fun PinChangeDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val s = AppStrings.current
    var old   by remember { mutableStateOf("") }
    var new1  by remember { mutableStateOf("") }
    var new2  by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = BgCard,
        title = { Text(s.changePin, color = Color(0xFFa855f7), fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, setter, label) in listOf(
                    Triple(old,  { v: String -> old  = v }, s.pinCurrent),
                    Triple(new1, { v: String -> new1 = v }, s.pinNew),
                    Triple(new2, { v: String -> new2 = v }, "Neuer PIN (wiederholen)"))) {
                    OutlinedTextField(value, { setter(it.filter { c -> c.isDigit() }.take(6)) },
                        label = { Text(label, color = TextSecondary) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = pinFieldColors())
                }
                if (error.isNotEmpty()) Text(error, color = Color(0xFFEF4444), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    new1.length < 4 -> "Neuer PIN mind. 4 Ziffern"
                    new1 != new2    -> s.pinMismatch
                    else            -> { onConfirm(old, new1); return@TextButton }
                }
            }) { Text("Ändern", color = Color(0xFFa855f7)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel, color = TextSecondary) } }
    )
}

@Composable
fun pinFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = BorderColor, focusedBorderColor = Color(0xFFa855f7).copy(.5f),
    unfocusedContainerColor = BgElevated, focusedContainerColor = BgElevated,
    unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary,
    unfocusedLabelColor = TextSecondary, focusedLabelColor = Color(0xFFa855f7))

// ─────────────────────────────────────────────
// Port Einstellungen Card
// ─────────────────────────────────────────────
@Composable
fun PortSettingsCard(viewModel: SettingsViewModel) {
    data class PortEntry(val label: String, val desc: String,
                          val get: () -> Int, val set: (Int) -> Unit, val default: Int)

    val ports = listOf(
        PortEntry("SOCKS5-Port", "Tor SOCKS5-Proxy",
            { viewModel.embeddedSocksPort   }, { viewModel.embeddedSocksPort    = it }, 9151),
        PortEntry("Control-Port", "Tor Steuerungsport",
            { viewModel.embeddedControlPort }, { viewModel.embeddedControlPort  = it }, 9052),
        PortEntry("Hidden Service", "Eingehende Verbindungen (P2P)",
            { viewModel.embeddedHsPort      }, { viewModel.embeddedHsPort       = it }, 11009),
    )

    SettingsCard {
        // Hinweis
        Row(Modifier.fillMaxWidth().background(AccentGreen.copy(.05f)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ℹ️", fontSize = 14.sp)
            Text("Nur ändern wenn Ports bereits belegt sind. Restart erforderlich.",
                color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Divider(color = BorderColor)

        ports.forEachIndexed { i, entry ->
            PortRow(entry.label, entry.desc, entry.get(), entry.default,
                onSave = { newPort -> entry.set(newPort); viewModel.retryTorConnection() })
            if (i < ports.size - 1) Divider(color = BorderColor)
        }
    }
}

@Composable
fun PortRow(label: String, desc: String, currentPort: Int, defaultPort: Int,
             onSave: (Int) -> Unit) {
    val s = AppStrings.current
    var showDialog by remember { mutableStateOf(false) }
    var input      by remember { mutableStateOf(currentPort.toString()) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false }, containerColor = BgCard,
            title = { Text(label, color = AccentGreen, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Standard: $defaultPort  |  Bereich: 1024–65535",
                        color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { c -> c.isDigit() }.take(5) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = BorderColor, focusedBorderColor = AccentGreen.copy(.5f),
                            unfocusedContainerColor = BgElevated, focusedContainerColor = BgElevated,
                            unfocusedTextColor = AccentGreen, focusedTextColor = AccentGreen,
                            unfocusedLabelColor = TextSecondary, focusedLabelColor = AccentGreen))
                    TextButton(onClick = { input = defaultPort.toString() }) {
                        Text("Standard ($defaultPort) wiederherstellen", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = input.toIntOrNull()
                    if (p != null && p in 1024..65535) { onSave(p); showDialog = false }
                }) { Text("Speichern & Neu starten", color = AccentGreen) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(s.cancel, color = TextSecondary) }
            }
        )
    }

    Row(Modifier.fillMaxWidth().clickable { input = currentPort.toString(); showDialog = true }
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Text(currentPort.toString(), color = AccentGreen, fontSize = 14.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Icon(Icons.Default.Edit, null, tint = TextDim, modifier = Modifier.size(14.dp))
    }
}

// ═══════════════════════════════════════
// Tab 4: KONTAKTE (Block-Liste)
// ═══════════════════════════════════════
@Composable
fun SettingsTabKontakte(listVm: ChatListViewModel, padding: PaddingValues) {
    val s = AppStrings.current
    val blocked by listVm.blockedContacts.collectAsState()
    var showConfirmDelete by remember { mutableStateOf<com.torchat.app.model.Contact?>(null) }

    // Löschen-Bestätigung
    showConfirmDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { showConfirmDelete = null },
            containerColor   = BgCard,
            title = { Text(s.deleteContact, color = Color(0xFFEF4444),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text  = { Text("${contact.name} und alle Nachrichten werden endgültig gelöscht.",
                color = TextSecondary, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    listVm.deleteContact(contact)
                    showConfirmDelete = null
                }) { Text(s.delete, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = null }) {
                    Text(s.cancel, color = TextSecondary)
                }
            }
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        item { Spacer(Modifier.height(4.dp)) }

        item {
            Text(s.sectionBlocklist, color = TextDim, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp))
        }

        if (blocked.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth()
                    .background(BgCard, RoundedCornerShape(12.dp))
                    .padding(24.dp),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🚫", fontSize = 28.sp)
                        Text(s.noBlocked, color = TextDim,
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        } else {
            items(blocked) { contact ->
                Card(colors = CardDefaults.cardColors(containerColor = BgCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(.3f)),
                    shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {

                        // Avatar
                        Box(Modifier.size(40.dp)
                            .background(Color(0xFFEF4444).copy(.15f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text("🚫", fontSize = 18.sp)
                        }

                        // Info
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(contact.name, color = TextPrimary, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium)
                            Text(contact.onionAddress.take(24) + "...", color = TextDim,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }

                        // Aktionen
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Entsperren
                            IconButton(onClick = { listVm.unblockContact(contact) },
                                modifier = Modifier.size(36.dp)
                                    .background(AccentGreen.copy(.1f), RoundedCornerShape(8.dp))) {
                                Icon(Icons.Default.LockOpen, s.unblock,
                                    tint = AccentGreen, modifier = Modifier.size(18.dp))
                            }
                            // Löschen
                            IconButton(onClick = { showConfirmDelete = contact },
                                modifier = Modifier.size(36.dp)
                                    .background(Color(0xFFEF4444).copy(.1f), RoundedCornerShape(8.dp))) {
                                Icon(Icons.Default.DeleteForever, s.delete,
                                    tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ═══════════════════════════════════════
// Tab 5: LOG
// ═══════════════════════════════════════
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val s = AppStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var diagText by remember { mutableStateOf("🔄 Diagnose läuft...") }
    var isRunning by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val result = com.torchat.app.debug.StartupDiagnostics.run(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                diagText = result
                isRunning = false
            }
        }
    }

    Scaffold(containerColor = BgDeep,
        topBar = {
            Row(Modifier.fillMaxWidth().background(BgDeep).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = AccentGreen)
                }
                Text(s.diagnostics, color = AccentGreen, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f))
                if (isRunning) {
                    CircularProgressIndicator(color = AccentGreen,
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    // Erneut ausführen
                    IconButton(onClick = {
                        isRunning = true
                        diagText = "🔄 Diagnose läuft..."
                    }) {
                        Icon(Icons.Default.Refresh, null, tint = AccentGreen)
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Hinweis
            Row(Modifier.fillMaxWidth().background(AccentGreen.copy(.07f))
                .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("ℹ️", fontSize = 16.sp)
                Text(s.diagSend,
                    color = AccentGreen, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            }

            // Crash-Banner wenn vorhanden
            val lastCrash = remember {
                com.torchat.app.debug.CrashHandler.getLastCrash(context)
            }
            if (lastCrash != null) {
                Row(Modifier.fillMaxWidth().background(Color(0xFFEF4444).copy(.12f))
                    .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("💥", fontSize = 16.sp)
                    Column(Modifier.weight(1f)) {
                        Text("Letzter Crash gefunden!", color = Color(0xFFEF4444),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                        Text("Im Diagnose-Report unten sichtbar",
                            color = TextSecondary, fontSize = 10.sp)
                    }
                    TextButton(onClick = {
                        com.torchat.app.debug.CrashHandler.clearLastCrash(context)
                    }) { Text(s.delete, color = Color(0xFFEF4444), fontSize = 11.sp) }
                }
            }

            // Diagnose-Text
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = BgCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                        shape = RoundedCornerShape(12.dp)) {
                        Text(diagText,
                            color = AccentGreen,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════
// Tab 4: LOG  (Echtzeit Fehlerprotokoll)
// ═══════════════════════════════════════
@Composable
fun SettingsTabLog(padding: PaddingValues) {
    val s = AppStrings.current
    val entries by com.torchat.app.debug.TorChatLogger.entries.collectAsState()
    val listState = rememberLazyListState()

    // Automatisch zum neuesten Eintrag scrollen
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(padding)) {

        // Toolbar
        Row(Modifier.fillMaxWidth().background(BgDeep).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {

            // Echtzeit-Zähler
            Text("${entries.size} Einträge", color = TextSecondary,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f))

            // Löschen
            IconButton(onClick = { com.torchat.app.debug.TorChatLogger.clear() },
                modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteSweep, s.delete,
                    tint = AccentOrange, modifier = Modifier.size(18.dp))
            }
        }

        Divider(color = BorderColor)

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📋", fontSize = 32.sp)
                    Text("Noch keine Log-Einträge", color = TextDim,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()
                .background(BgDeep).padding(horizontal = 8.dp)) {
                items(entries) { entry ->
                    LogEntryRow(entry)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun LogEntryRow(entry: com.torchat.app.debug.TorChatLogger.LogEntry) {
    val color = when (entry.level) {
        com.torchat.app.debug.TorChatLogger.LogEntry.Level.DEBUG -> TextSecondary
        com.torchat.app.debug.TorChatLogger.LogEntry.Level.INFO  -> AccentGreen
        com.torchat.app.debug.TorChatLogger.LogEntry.Level.WARN  -> Color(0xFFF59E0B)
        com.torchat.app.debug.TorChatLogger.LogEntry.Level.ERROR -> Color(0xFFEF4444)
    }
    val bg = when (entry.level) {
        com.torchat.app.debug.TorChatLogger.LogEntry.Level.ERROR -> Color(0xFFEF4444).copy(.05f)
        com.torchat.app.debug.TorChatLogger.LogEntry.Level.WARN  -> Color(0xFFF59E0B).copy(.04f)
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(entry.timestamp, color = TextDim,
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(60.dp))
        Text(entry.level.symbol, fontSize = 10.sp,
            modifier = Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.tag, color = color.copy(.7f),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold)
            Text(entry.message, color = color,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp)
        }
    }
    Divider(color = BorderColor.copy(.3f))
}

// ─────────────────────────────────────────────
// Hintergrund-Einstellungen Card
// ─────────────────────────────────────────────
// ─────────────────────────────────────────────
// Anzeigename-Editor (eigener Composable → korrekter State-Scope)
// ─────────────────────────────────────────────
@Composable
fun DisplayNameEditor(viewModel: SettingsViewModel) {
    var displayName by remember { mutableStateOf(viewModel.myDisplayName) }
    var editing     by remember { mutableStateOf(false) }
    var nameInput   by remember { mutableStateOf(displayName) }

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("ANZEIGENAME", color = TextDim, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)

        if (editing) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("z.B. Max", color = TextDim,
                        fontFamily = FontFamily.Monospace) },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = AccentGreen.copy(.4f),
                        focusedBorderColor = AccentGreen,
                        unfocusedContainerColor = BgElevated,
                        focusedContainerColor = BgElevated,
                        unfocusedTextColor = AccentGreen,
                        focusedTextColor = AccentGreen),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    shape = RoundedCornerShape(10.dp)
                )
                Box(Modifier.size(42.dp)
                    .background(AccentGreen.copy(.15f), RoundedCornerShape(10.dp))
                    .clickable {
                        val trimmed = nameInput.trim().ifBlank { "TorChat User" }
                        viewModel.updateDisplayName(trimmed)
                        displayName = trimmed
                        editing = false
                    },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = AccentGreen,
                        modifier = Modifier.size(20.dp))
                }
                Box(Modifier.size(42.dp)
                    .background(BgElevated, RoundedCornerShape(10.dp))
                    .clickable { editing = false; nameInput = displayName },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, null, tint = TextDim,
                        modifier = Modifier.size(18.dp))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth()
                    .background(BgElevated, RoundedCornerShape(10.dp))
                    .clickable { nameInput = displayName; editing = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("👤", fontSize = 18.sp)
                Text(displayName.ifBlank { "TorChat User" },
                    color = AccentGreen, fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Default.Edit, null, tint = TextDim,
                    modifier = Modifier.size(16.dp))
            }
            Text("Dieser Name wird deinen Kontakten beim Chatten übermittelt.",
                color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
fun BackgroundSettingsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bgType     by remember { mutableStateOf(AppBackground.bgType.value) }
    var bgColorHex by remember { mutableStateOf(AppBackground.bgColorHex.value) }
    var bgImageUri by remember { mutableStateOf(AppBackground.bgImageUri.value) }

    // Vordefinierte Farben
    val presetColors = listOf(
        "#FF060810" to "Standard",
        "#FF0A0A0A" to "Schwarz",
        "#FF0D1B2A" to "Tiefblau",
        "#FF0F1C14" to "Dunkelgrün",
        "#FF1A0A1C" to "Dunkelviolett",
        "#FF1C0A0A" to "Dunkelrot",
        "#FF1A1005" to "Dunkelbraun",
        "#FF101020" to "Marineblau",
    )

    // Bildauswahl-Launcher
    val imageLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Dauerhaften Zugriff sichern
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            val uriStr = uri.toString()
            com.torchat.app.data.SettingsManager.setBgImage(context, uriStr)
            AppBackground.bgType.value     = "image"
            AppBackground.bgImageUri.value = uriStr
            bgType     = "image"
            bgImageUri = uriStr
        }
    }

    SettingsCard {
        // Titel
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🎨", fontSize = 20.sp)
            Column(Modifier.weight(1f)) {
                Text("App-Hintergrund ändern", color = TextPrimary, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
                Text("Farbe oder Bild auswählen", color = TextSecondary,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Divider(color = BorderColor)

        // Vorschau-Streifen des aktuellen Hintergrunds
        Box(
            Modifier.fillMaxWidth().height(56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            if (bgType == "image" && bgImageUri.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = android.net.Uri.parse(bgImageUri),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Box(Modifier.fillMaxSize()
                    .background(Color.Black.copy(.45f)))
                Text("Eigenes Bild aktiv", color = Color.White, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center))
            } else {
                val previewColor = try {
                    Color(android.graphics.Color.parseColor(bgColorHex))
                } catch (_: Exception) { BgDeep }
                Box(Modifier.fillMaxSize().background(previewColor))
                Text("Aktuelle Hintergrundfarbe", color = TextSecondary, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center))
            }
        }

        Divider(color = BorderColor)

        // Farbpalette
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Farbe wählen", color = TextDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(presetColors) { (hex, label) ->
                    val isSelected = bgType == "color" && bgColorHex == hex
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier.size(40.dp)
                                .clip(CircleShape)
                                .background(try { Color(android.graphics.Color.parseColor(hex)) }
                                    catch (_: Exception) { BgDeep })
                                .border(
                                    if (isSelected) 2.dp else 0.5.dp,
                                    if (isSelected) AccentGreen else BorderColor,
                                    CircleShape)
                                .clickable {
                                    com.torchat.app.data.SettingsManager.setBgColor(context, hex)
                                    AppBackground.bgType.value     = "color"
                                    AppBackground.bgColorHex.value = hex
                                    bgType     = "color"
                                    bgColorHex = hex
                                }
                        ) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, null,
                                    tint = AccentGreen,
                                    modifier = Modifier.size(18.dp).align(Alignment.Center))
                            }
                        }
                        Text(label, color = TextDim, fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Divider(color = BorderColor)

        // Eigenes Bild
        Row(
            Modifier.fillMaxWidth()
                .clickable { imageLauncher.launch("image/*") }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Image, null, tint = AccentPurple, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Eigenen Hintergrund auswählen", color = AccentPurple,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text("Foto aus Galerie als Hintergrund", color = TextDim,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextDim, modifier = Modifier.size(18.dp))
        }

        // Zurücksetzen
        if (bgType != "color" || bgColorHex != "#FF060810") {
            Divider(color = BorderColor)
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        com.torchat.app.data.SettingsManager.resetBg(context)
                        AppBackground.bgType.value     = "color"
                        AppBackground.bgColorHex.value = "#FF060810"
                        AppBackground.bgImageUri.value = ""
                        bgType     = "color"
                        bgColorHex = "#FF060810"
                        bgImageUri = ""
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.RestartAlt, null, tint = TextDim, modifier = Modifier.size(18.dp))
                Text("Standard wiederherstellen", color = TextDim,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = BgCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
        shape = RoundedCornerShape(16.dp)) { Column(content = content) }
}

@Composable
fun SettingsToggleRow(icon: String, title: String, sub: String, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(icon, fontSize = 20.sp, color = if (enabled) Color.Unspecified else TextDim.copy(.5f))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) TextPrimary else TextDim.copy(.5f),
                fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = if (enabled) TextSecondary else TextDim.copy(.4f),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Switch(checked, onCheckedChange = { if (enabled) onToggle(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
            checkedThumbColor = AccentGreen, checkedTrackColor = AccentGreen.copy(.3f),
            uncheckedThumbColor = TextDim,   uncheckedTrackColor = BgElevated))
    }
}

@Composable
fun SettingsInfoRow(icon: String, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(icon, fontSize = 20.sp)
        Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

package com.victorious.spoof.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.victorious.spoof.R
import com.victorious.spoof.audio.Clip
import com.victorious.spoof.audio.VoicePreset
import com.victorious.spoof.bridge.OpRegistry
import com.victorious.spoof.calling.CallerIdMode
import com.victorious.spoof.calling.LocalPrecheck
import com.victorious.spoof.calling.MAX_CALL_SECONDS
import kotlinx.coroutines.launch

@Composable
fun VictorSpoofRoot(startScreen: String?, vm: AppViewModel = hiltViewModel()) {
    val consent by vm.consent.collectAsState()
    val account by vm.accountId.collectAsState()
    LaunchedEffect(account) { if (account != null) vm.refreshMe() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            consent?.disclaimerAcceptedAt == null -> DisclaimerScreen(onAccept = vm::acceptDisclaimer)
            consent?.ageConfirmedAt == null -> AgeGateScreen(onConfirm = vm::confirmAge)
            account == null -> AuthScreen(vm)
            consent?.aupLoggedServerSide != true -> AupScreen(vm)
            else -> MainNav(vm, startScreen)
        }
    }
}

@Composable
fun AuthScreen(vm: AppViewModel) {
    var signup by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val msg by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(72.dp))
        Text(if (signup) "Create your account" else "Log in", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Your account keeps the call limits and your verified number tied to you. We never see your contacts.")
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text("Password (10+ characters)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { if (signup) vm.signup(email, pw) else vm.login(email, pw) }, enabled = !busy && email.contains('@') && pw.length >= (if (signup) 10 else 1),
            modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait…" else if (signup) "Sign up (I'm 18+ and accept the disclaimer)" else "Log in") }
        TextButton(onClick = { signup = !signup; vm.message.value = null }) { Text(if (signup) "I already have an account" else "Create a new account") }
    }
}

@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    val scroll = rememberScrollState()
    val reachedEnd = scroll.value >= scroll.maxValue && scroll.maxValue >= 0
    var everReachedEnd by remember { mutableStateOf(false) }
    if (reachedEnd && scroll.maxValue > 0) everReachedEnd = true
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Before you prank anyone", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            Text(DISCLAIMER_TEXT)
        }
        Text(if (everReachedEnd) "Thanks for reading." else "Scroll to the end to continue.", color = MaterialTheme.colorScheme.secondary)
        Button(onClick = onAccept, enabled = everReachedEnd || scroll.maxValue == 0, modifier = Modifier.fillMaxWidth()) { Text("I understand and agree") }
    }
}

@Composable
fun AgeGateScreen(onConfirm: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("18+ only", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("VictorSpoof is for adults. You must be 18 or older to use it.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { checked = it }); Text("I confirm I am 18 years of age or older.")
        }
        Button(onClick = onConfirm, enabled = checked) { Text("Continue") }
    }
}

@Composable
fun AupScreen(vm: AppViewModel) {
    val msg by vm.message.collectAsState()
    var agree by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Acceptable Use Policy ($AUP_VERSION)", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Text(AUP_TEXT) }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(agree, { agree = it }); Text("I accept the AUP") }
        Button(onClick = { vm.acceptAup() }, enabled = agree, modifier = Modifier.fillMaxWidth()) { Text("Accept (logged on our server)") }
    }
}

@Composable
fun MainNav(vm: AppViewModel, startScreen: String?) {
    val nav = rememberNavController()
    val start = startScreen?.takeIf { it in setOf("home", "help", "soundboard", "voice", "settings", "verify") } ?: "home"
    NavHost(nav, startDestination = start) {
        composable("home") { HomeScreen(vm, nav) }
        composable("voice") { VoiceChangerScreen(vm) }
        composable("soundboard") { SoundboardScreen(vm) }
        composable("verify") { VerifyNumberScreen(vm) }
        composable("reveal") { RevealScreen { vm.calls.reset(); nav.popBackStack("home", false) } }
        composable("help") { HelpScreen() }
        composable("settings") { SettingsScreen(vm, nav) }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(CallerIdMode.POOL) }
    var recording by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    val state by vm.calls.state.collectAsState()
    val left by vm.calls.remainingSeconds.collectAsState()
    val me by vm.me.collectAsState()
    val preset by vm.audio.preset.collectAsState()
    val ownVerified = me?.callerIdOptions?.contains("OWN_VERIFIED") == true
    LaunchedEffect(state) { if (state == "reveal") nav.navigate("reveal") }
    LaunchedEffect(ownVerified) { if (!ownVerified) mode = CallerIdMode.POOL }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val id = ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (id != null) ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id), null)
            ?.use { c -> if (c.moveToFirst()) target = c.getString(0) }
    }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) pickContact.launch(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) showConfirm = true
    }
    val inCall = state in setOf("authorizing", "ringing", "connected", "reconnecting", "revealing")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp)); Text("VictorSpoof", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text("Who are you pranking?")
        OutlinedTextField(target, { target = it }, label = { Text("Friend's number") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) pickContact.launch(null)
            else contactsPerm.launch(Manifest.permission.READ_CONTACTS)
        }) { Text("Pick from contacts") }

        Text("Caller ID (chosen by our server)", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == CallerIdMode.POOL, { mode = CallerIdMode.POOL }); Text("A VictorSpoof fun number") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == CallerIdMode.OWN_VERIFIED, { mode = CallerIdMode.OWN_VERIFIED }, enabled = ownVerified)
            Text(if (ownVerified) "My own verified number (${me?.verifiedNumberMasked})" else "My own number (verify it first)")
        }
        if (!ownVerified) TextButton(onClick = { nav.navigate("verify") }) { Text("Verify my own number…") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(recording, { recording = it }); Spacer(Modifier.width(8.dp)); Text("Record (everyone hears an announcement)")
        }
        Text("Voice: ${preset.label}", color = MaterialTheme.colorScheme.secondary)
        Button(onClick = {
            val needed = buildList {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (needed.isEmpty()) showConfirm = true else perms.launch(needed.toTypedArray())
        }, enabled = !inCall && target.isNotBlank() && !LocalPrecheck.obviouslyBlocked(target), modifier = Modifier.fillMaxWidth()) { Text("Call") }
        if (target.isNotBlank() && LocalPrecheck.obviouslyBlocked(target)) Text("That number can't be called with VictorSpoof.", color = MaterialTheme.colorScheme.error)
        Text("Status: $state" + (left?.let { " • ${it}s left" } ?: ""))
        if (inCall) {
            Button(onClick = { vm.hangUp() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Hang up + reveal") }
            Text("Live sounds", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Clip.AIR_HORN, Clip.RIMSHOT, Clip.SAD_TROMBONE).forEach { c -> OutlinedButton(onClick = { vm.play(c) }) { Text(c.label, fontSize = 12.sp) } }
            }
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nav.navigate("voice") }) { Text("Voice") }
            OutlinedButton(onClick = { nav.navigate("soundboard") }) { Text("Sounds") }
            OutlinedButton(onClick = { nav.navigate("help") }) { Text("Help") }
            OutlinedButton(onClick = { nav.navigate("settings") }) { Text("Settings") }
        }
    }

    if (showConfirm) AlertDialog(
        onDismissRequest = { showConfirm = false },
        title = { Text("Place this prank call?") },
        text = { Text("Call $target now? Max ${MAX_CALL_SECONDS / 60} minutes. ${if (recording) "Everyone on the call will hear that it is recorded. " else ""}Pranks must be harmless — no threats, no impersonating authorities, no fraud.") },
        confirmButton = { Button(onClick = { showConfirm = false; vm.startCall(target, mode, recording) }) { Text("Yes, call") } },
        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun VerifyNumberScreen(vm: AppViewModel) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val step by vm.verifyStep.collectAsState()
    val msg by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Verify your own number", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("We text a one-time code to prove this number is yours. Only a verified number can be used as your caller ID. You can't verify emergency, government, police, hospital or bank numbers.")
        OutlinedTextField(phone, { phone = it }, label = { Text("Your mobile number") }, singleLine = true, enabled = step == "enter",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        if (step == "enter") Button(onClick = { vm.verifyStart(phone) }, enabled = !busy && phone.count(Char::isDigit) >= 10) { Text("Text me a code") }
        if (step == "code") {
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(10) }, label = { Text("6-digit code") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.verifyCheck(phone, code) }, enabled = !busy && code.length >= 4) { Text("Verify") }
                TextButton(onClick = { vm.verifyStep.value = "enter" }) { Text("Change number") }
            }
        }
        msg?.let { Text(it, color = if (step == "done") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error) }
        Text("Limits: 3 codes per hour (5 per day), 5 code attempts per hour.", fontSize = 12.sp)
    }
}

@Composable
fun VoiceChangerScreen(vm: AppViewModel) {
    val selected by vm.audio.preset.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Voice changer", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Applied in real time to your outgoing call audio. You can switch presets mid-call. Your choice is saved.")
        Spacer(Modifier.height(8.dp))
        VoicePreset.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == p, { vm.setPreset(p) })
                Column { Text(p.label, fontWeight = FontWeight.SemiBold); Text(p.blurb, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun SoundboardScreen(vm: AppViewModel) {
    val state by vm.calls.state.collectAsState()
    val live = state == "connected"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Soundboard", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(if (live) "LIVE: sounds play into the call for your friend." else "Sounds play into the call once it's connected. All clips are generated on-device.")
        Clip.entries.forEach { c -> Button(onClick = { vm.play(c) }, enabled = live, modifier = Modifier.fillMaxWidth()) { Text(c.label) } }
    }
}

@Composable
fun RevealScreen(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("You've been VictorSpoofed!", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(12.dp))
        Text("The reveal sting plays to your friend at the end of every call so nobody is left fooled.")
        Spacer(Modifier.height(20.dp)); Button(onClick = onDone) { Text("Back") }
    }
}

@Composable
fun HelpScreen() {
    val ctx = LocalContext.current
    val manual = remember { runCatching { ctx.assets.open("MANUAL.md").bufferedReader().readText() }.getOrElse { "Manual unavailable." } }
    MarkdownView(manual)
}

@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavHostController) {
    val ext by vm.externalControl.collectAsState()
    val pairs by vm.pairings.collectAsState()
    val me by vm.me.collectAsState()
    var pkg by remember { mutableStateOf("") }
    var cert by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Account", fontWeight = FontWeight.Bold)
        Text("Own number: ${me?.verifiedNumberMasked ?: "not verified"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nav.navigate("verify") }) { Text("Verify number") }
            OutlinedButton(onClick = { vm.logout() }) { Text("Log out") }
        }
        HorizontalDivider()
        Text("Fleet node id: ${vm.settings.nodeId}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(ext, vm::setExternalControl); Spacer(Modifier.width(8.dp)); Text("Allow fleet external control (read-only ops + open app)")
        }
        Text("Remote ops this node exposes:", fontWeight = FontWeight.Bold)
        OpRegistry.ops.forEach { Text("• ${it.name} — ${it.kind}, remote ${if (it.remoteAllowed) "allowed" else "DENIED"}${if (it.requiresConfirm) ", needs confirmation" else ""}") }
        HorizontalDivider()
        Text("Paired fleet apps (package + signing cert SHA-256)", fontWeight = FontWeight.Bold)
        pairs.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${p.packageName}\n${p.certSha256.take(16)}…", Modifier.weight(1f)); TextButton({ vm.unpair(p) }) { Text("Remove") } } }
        OutlinedTextField(pkg, { pkg = it }, label = { Text("Package name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cert, { cert = it }, label = { Text("Cert SHA-256") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.pair(pkg, cert); pkg = ""; cert = "" }, enabled = pkg.isNotBlank() && cert.replace(":", "").length == 64) { Text("Pair") }
        HorizontalDivider()
        BackupSection(vm)
    }
}

@Composable
fun BackupSection(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val exported by vm.backupText.collectAsState()
    var usePassphrase by remember { mutableStateOf(true) }
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var bundle by remember { mutableStateOf("") }
    var importPass by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    Text("Encrypted settings backup (UI preferences only)", fontWeight = FontWeight.Bold)
    Text("Exports only look-and-feel preferences and your voice preset, encrypted with AES-256-GCM. Never credentials, numbers, accounts, pairings or fleet control.")
    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(usePassphrase, { usePassphrase = true }); Text("Passphrase (restore on any device)") }
    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!usePassphrase, { usePassphrase = false }); Text("This device's Keystore (restore here only)") }
    if (usePassphrase) {
        OutlinedTextField(pass1, { pass1 = it }, label = { Text("Passphrase (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass2, { pass2 = it }, label = { Text("Repeat passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.exportEncrypted(if (usePassphrase) pass1.toCharArray() else null); pass1 = ""; pass2 = "" },
            enabled = !usePassphrase || (pass1.length >= 8 && pass1 == pass2)) { Text("Export encrypted") }
        exported?.let { e -> TextButton(onClick = {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VictorSpoof backup", e))
        }) { Text("Copy") } }
    }
    exported?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
    OutlinedTextField(bundle, { bundle = it }, label = { Text("Paste an encrypted backup to import") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(importPass, { importPass = it }, label = { Text("Passphrase (leave empty for Keystore backups)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { scope.launch { result = vm.previewImport(bundle, importPass.takeIf { it.isNotEmpty() }?.toCharArray()) } }, enabled = bundle.isNotBlank()) { Text("Preview (dry run)") }
        Button(onClick = { confirm = true }, enabled = bundle.isNotBlank()) { Text("Import…") }
    }
    result?.let { Text(it) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Import these settings?") },
        text = { Text("Only UI preferences and the voice preset will change. Fleet external control will be turned OFF.") },
        confirmButton = { Button(onClick = { confirm = false; scope.launch { result = vm.applyImportConfirmed(bundle, importPass.takeIf { it.isNotEmpty() }?.toCharArray()) } }) { Text("Import") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

const val DISCLAIMER_TEXT = """VictorSpoof is a novelty app for harmless pranks between friends. It is for ENTERTAINMENT ONLY.

What VictorSpoof does NOT do:
• It never lets you type in or choose an arbitrary caller-ID number or name.
• Caller ID is either a VictorSpoof-owned number (with a fun name where carriers support it) or your OWN verified number. Our server picks it.
• It cannot call emergency numbers (911), the 988 crisis line, N11 services, or government, police, hospital or major bank numbers.
• It does not send SMS, does not auto-dial, and never reads your call log.

The law: in the United States the Truth in Caller ID Act makes it illegal to transmit misleading caller ID with intent to defraud, cause harm, or wrongfully obtain anything of value. Other countries have similar laws. Harassment, threats, stalking, fraud, impersonating officials or businesses, and repeated unwanted calls are illegal and banned.

Limits: about 10 calls per day, at most 3 per day to the same person, about 2 minutes per call. Every call ends with a "You've been VictorSpoofed!" reveal.

Recording is opt-in only, and everyone on the call hears an announcement that it is being recorded. Many places require all-party consent.

We log your acceptance of these terms and basic call metadata on our server to enforce the rules. Abuse gets your account suspended and may be reported to authorities when the law requires.

By continuing you confirm you will only prank people who will take it in good fun, and that you accept full responsibility for how you use VictorSpoof."""

const val AUP_TEXT = """1. Entertainment only. Harmless pranks on people who know you.
2. No fraud, threats, harassment, stalking, extortion, swatting, or hoaxes.
3. Never impersonate police, government, banks, hospitals, utilities or any business.
4. No calls to emergency, crisis or N11 numbers (they are blocked anyway).
5. Respect limits: ~10 calls/day, 3/day per person, ~2 minutes per call.
6. Recording only with the built-in announcement. Follow your local consent laws.
7. Stop immediately if someone asks you not to call them again.
8. We may suspend accounts and cooperate with lawful requests.
Your acceptance is logged on our server with a timestamp."""

package com.victorious.spoof.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.victorious.spoof.R
import com.victorious.spoof.bridge.OpRegistry
import com.victorious.spoof.calling.CallerIdMode
import com.victorious.spoof.calling.LocalPrecheck

@Composable
fun VictorSpoofRoot(startScreen: String?, vm: AppViewModel = hiltViewModel()) {
    val consent by vm.consent.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            consent?.disclaimerAcceptedAt == null -> DisclaimerScreen(onAccept = vm::acceptDisclaimer)
            consent?.ageConfirmedAt == null -> AgeGateScreen(onConfirm = vm::confirmAge)
            consent?.aupLoggedServerSide != true -> AupScreen(vm)
            else -> MainNav(vm, startScreen)
        }
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
    val start = startScreen?.takeIf { it in setOf("home", "help", "soundboard", "voice", "settings") } ?: "home"
    NavHost(nav, startDestination = start) {
        composable("home") { HomeScreen(vm, nav) }
        composable("voice") { VoiceChangerScreen() }
        composable("soundboard") { SoundboardScreen() }
        composable("reveal") { RevealScreen { nav.popBackStack("home", false) } }
        composable("help") { HelpScreen() }
        composable("settings") { SettingsScreen(vm) }
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
    LaunchedEffect(state) { if (state == "reveal") nav.navigate("reveal") }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val id = ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (id != null) ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id), null)
            ?.use { c -> if (c.moveToFirst()) target = c.getString(0) }
    }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) pickContact.launch(null) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp)); Text("VictorSpoof", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text("Who are you pranking?")
        OutlinedTextField(target, { target = it }, label = { Text("Friend's number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) pickContact.launch(null)
            else contactsPerm.launch(Manifest.permission.READ_CONTACTS)
        }) { Text("Pick from contacts") }

        Text("Caller ID (chosen by our server)", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == CallerIdMode.POOL, { mode = CallerIdMode.POOL }); Text("A VictorSpoof fun number") }
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == CallerIdMode.OWN_VERIFIED, { mode = CallerIdMode.OWN_VERIFIED }); Text("My own verified number") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(recording, { recording = it }); Spacer(Modifier.width(8.dp)); Text("Record (everyone hears an announcement)")
        }
        Button(onClick = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPerm.launch(Manifest.permission.RECORD_AUDIO)
            else showConfirm = true
        }, enabled = target.isNotBlank() && !LocalPrecheck.obviouslyBlocked(target), modifier = Modifier.fillMaxWidth()) { Text("Call") }
        if (target.isNotBlank() && LocalPrecheck.obviouslyBlocked(target)) Text("That number can't be called with VictorSpoof.", color = MaterialTheme.colorScheme.error)
        Text("Status: $state")
        if (state in setOf("ringing", "connected", "authorizing")) OutlinedButton(onClick = { vm.calls.hangUp() }) { Text("Hang up + reveal") }
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
        text = { Text("Call $target now? Max 2 minutes. ${if (recording) "Everyone on the call will hear that it is recorded. " else ""}Pranks must be harmless — no threats, no impersonating authorities, no fraud.") },
        confirmButton = { Button(onClick = { showConfirm = false; vm.startCall(target, mode, recording, VoicePresets.selected) }) { Text("Yes, call") } },
        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
    )
}

object VoicePresets {
    val all = listOf("Normal", "Chipmunk", "Deep", "Robot", "Echo")
    var selected by mutableStateOf("Normal")
}

@Composable
fun VoiceChangerScreen() {
    Column(Modifier.padding(20.dp)) {
        Text("Voice changer", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Preview is a stub; the effect is applied to your outgoing audio during the call.")
        VoicePresets.all.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(VoicePresets.selected == p, { VoicePresets.selected = p }); Text(p) }
        }
    }
}

@Composable
fun SoundboardScreen() {
    val sounds = listOf("Air horn", "Rimshot", "Sad trombone", "Crowd laugh", "Duck")
    var last by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Soundboard", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        sounds.forEach { s -> Button(onClick = { last = s }, modifier = Modifier.fillMaxWidth()) { Text(s) } }
        last?.let { Text("Played: $it (stub — audio injection lands with the call engine)") }
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(manual.lines()) { line ->
            when {
                line.startsWith("# ") -> Text(line.removePrefix("# "), fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                line.startsWith("## ") -> Text(line.removePrefix("## "), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp))
                line.startsWith("### ") -> Text(line.removePrefix("### "), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                line.startsWith("![") -> Unit
                else -> Text(line.replace("**", "").replace("`", ""))
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ext by vm.externalControl.collectAsState()
    val pairs by vm.pairings.collectAsState()
    var pkg by remember { mutableStateOf("") }
    var cert by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
    }
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

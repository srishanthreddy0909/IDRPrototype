package com.idr.prototype

import android.content.Intent
import android.content.ActivityNotFoundException
import android.Manifest
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.widget.Toast
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IDRApp(vm: NavigationViewModel = viewModel()) {
    val nav by vm.nav.collectAsState()
    val sensor by vm.sensor.collectAsState()
    val outage by vm.demoOutage.collectAsState()
    val recovering by vm.recovering.collectAsState()
    val demo by vm.isDemo.collectAsState()
    val route by vm.route.collectAsState()
    val guidance by vm.guidance.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val realGnss by vm.gnss.collectAsState()
    val outageTelemetry by vm.outageTelemetry.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showCallPermissionHelp by remember { mutableStateOf(false) }
    var callError by remember { mutableStateOf<String?>(null) }
    var pendingCallNumber by remember { mutableStateOf<String?>(null) }
    var pendingCallIsMulti by remember { mutableStateOf(false) }
    var pendingEmergencyContacts by remember { mutableStateOf<List<EmergencyContact>>(emptyList()) }
    var pendingLocationMessage by remember { mutableStateOf<String?>(null) }
    var shouldStartCallAfterSmsPermission by remember { mutableStateOf(false) }
    var showSmsPermissionHelp by remember { mutableStateOf(false) }
    var showMultiCallFlow by remember { mutableStateOf(false) }
    var dialerOpenedForCurrent by remember { mutableStateOf(false) }
    var showLocationFeedback by remember { mutableStateOf(false) }
    var locationFeedback by remember { mutableStateOf("") }
    var locationPermissionMissing by remember { mutableStateOf(false) }
    var multiCallIndex by remember { mutableIntStateOf(0) }
    var pickedContact by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editingContact by remember { mutableStateOf<EmergencyContact?>(null) }
    var showContactEditor by remember { mutableStateOf(false) }
    var fromMenu by remember { mutableStateOf(false) }
    var toMenu by remember { mutableStateOf(false) }
    var selectedDestination by remember { mutableStateOf(vm.destinations.first().id) }
    var destinationQuery by remember { mutableStateOf("") }
    val context = LocalContext.current

    fun placeCall(number: String, advanceMultiFlow: Boolean = false) {
        try {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
            showCallPermissionHelp = false
            callError = null
            if (advanceMultiFlow) {
                multiCallIndex = 1.coerceAtMost(contacts.size)
                dialerOpenedForCurrent = false
                showMultiCallFlow = contacts.size > 1
            }
        } catch (_: SecurityException) {
            showCallPermissionHelp = true
        } catch (_: ActivityNotFoundException) {
            callError = "No phone app is available to place this call."
        }
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val number = pendingCallNumber
        if (granted && number != null) placeCall(number, pendingCallIsMulti)
        else showCallPermissionHelp = true
    }

    fun callContact(number: String, partOfMultiCall: Boolean = false) {
        pendingCallNumber = number
        pendingCallIsMulti = partOfMultiCall
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            placeCall(number, partOfMultiCall)
        } else {
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    fun openSmsComposer(recipients: List<EmergencyContact>, message: String) {
        try {
            val addressList = recipients.joinToString(";") { PhoneNumberUtils.stripSeparators(it.phoneNumber) }
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", addressList, null)).putExtra("sms_body", message)
            context.startActivity(smsIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                context.startActivity(Intent.createChooser(shareIntent, "Share emergency location"))
            } catch (_: ActivityNotFoundException) {
                callError = "No SMS or sharing app is available. The location message was not sent."
            }
        }
    }

    fun submitLocationSms(recipients: List<EmergencyContact>, message: String) {
        val failed = mutableListOf<EmergencyContact>()
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: throw IllegalStateException("SMS service unavailable")
            recipients.forEach { contact ->
                try {
                    smsManager.sendTextMessage(PhoneNumberUtils.stripSeparators(contact.phoneNumber), null, message, null, null)
                } catch (_: Exception) {
                    failed += contact
                }
            }
        } catch (_: Exception) {
            failed += recipients
        }
        if (failed.isEmpty()) {
            Toast.makeText(context, "Location SMS submitted to Android; delivery depends on your mobile network.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Some SMS could not be submitted. A message draft will open.", Toast.LENGTH_LONG).show()
            openSmsComposer(failed, message)
        }
    }

    fun startFirstEmergencyCall(recipients: List<EmergencyContact>) {
        val first = recipients.firstOrNull() ?: return
        multiCallIndex = 0
        dialerOpenedForCurrent = false
        callContact(first.phoneNumber, partOfMultiCall = recipients.size > 1)
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val recipients = pendingEmergencyContacts
        val message = pendingLocationMessage
        val startCall = shouldStartCallAfterSmsPermission
        shouldStartCallAfterSmsPermission = false
        if (granted && message != null) {
            showSmsPermissionHelp = false
            submitLocationSms(recipients, message)
        }
        else if (message != null && startCall) {
            Toast.makeText(context, "SMS permission denied. A draft was opened; tap Send to share the location.", Toast.LENGTH_LONG).show()
            openSmsComposer(recipients, message)
            showSmsPermissionHelp = true
        }
        if (startCall) startFirstEmergencyCall(recipients)
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0).orEmpty()
                        val phone = cursor.getString(1).orEmpty()
                        if (name.isNotBlank() && phone.isNotBlank()) pickedContact = name to phone
                        else callError = "That contact did not include a usable phone number. You can enter one manually."
                    }
                } ?: run { callError = "Could not read the selected contact. You can enter the number manually." }
            } catch (_: SecurityException) {
                callError = "Android did not provide access to that selection. You can enter the number manually."
            } catch (_: Exception) {
                callError = "Could not read the selected contact. You can enter the number manually."
            }
        }
    }

    fun beginEmergencyProcess() {
        val recipients = contacts.toList()
        if (recipients.isEmpty()) {
            showContacts = true
            editingContact = null
            pickedContact = null
            showContactEditor = true
            return
        }
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locationPermissionMissing = !hasLocationPermission
        if (!hasLocationPermission) {
            locationFeedback = "Location permission is unavailable, so no location message can be prepared. The emergency call will still start. Grant permission in app settings before trying again."
            showLocationFeedback = true
            startFirstEmergencyCall(recipients)
            return
        }
        val ageMs = System.currentTimeMillis() - realGnss.timestampMs
        if (realGnss.lat == 0.0 && realGnss.lon == 0.0 || realGnss.timestampMs <= 0L || ageMs !in 0..15_000L) {
            locationFeedback = "A recent real GNSS fix is unavailable, so no location message can be prepared. The emergency call will still start. Turn on device location and wait for a fix before trying again. The demo/default position is never shared."
            showLocationFeedback = true
            startFirstEmergencyCall(recipients)
            return
        }
        val latitude = "%.6f".format(java.util.Locale.US, realGnss.lat)
        val longitude = "%.6f".format(java.util.Locale.US, realGnss.lon)
        val body = "EMERGENCY ALERT\n\nMy current location:\n$latitude, $longitude\n\nOpen location:\nhttps://maps.google.com/?q=$latitude,$longitude"
        pendingEmergencyContacts = recipients
        pendingLocationMessage = body
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            submitLocationSms(recipients, body)
            startFirstEmergencyCall(recipients)
        } else {
            shouldStartCallAfterSmsPermission = true
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    LaunchedEffect(Unit) { vm.start() }

    Scaffold(
        containerColor = Color(0xFFF8FAFC),
        topBar = {
            TopAppBar(
                title = {
            Column {
                        Text("IDR Navigation", fontWeight = FontWeight.Bold)
                    Text("Offline prototype • local demo routes", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        when (contacts.size) {
                            0 -> {
                                showContacts = true
                                editingContact = null
                                pickedContact = null
                                showContactEditor = true
                            }
                            else -> beginEmergencyProcess()
                        }
                    }) {
                        Icon(Icons.Default.ContactPhone, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Emergency Contact")
                    }
                    IconButton(onClick = { showContacts = true }) {
                        Icon(Icons.Default.ManageAccounts, contentDescription = "Manage emergency contacts")
                    }
                    IconButton(onClick = { showDiagnostics = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Sensor diagnostics")
                    }
                    IconButton(onClick = { showCalibration = true }) {
                        Icon(Icons.Default.Explore, contentDescription = "Sensor calibration")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = Color.White) {
                Button(
                    onClick = { if (demo) vm.stopDemo() else vm.startDemo() },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                ) {
                    Icon(if (demo) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (demo) "Stop Demo" else "Run Demo")
                }
                OutlinedButton(
                    onClick = { vm.toggleOutage() },
                    enabled = !demo,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                ) {
                    Icon(if (outage) Icons.Default.GpsFixed else Icons.Default.SignalWifiOff, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (outage) "Restore GNSS" else "Simulate Loss")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBanner(nav, outage, recovering, outageTelemetry)

            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = "Current location", onValueChange = {}, readOnly = true,
                            label = { Text("From") },
                            leadingIcon = { Icon(Icons.Default.MyLocation, null, tint = Color(0xFF2563EB)) },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                            modifier = Modifier.fillMaxWidth().clickable { fromMenu = true },
                            shape = RoundedCornerShape(14.dp), singleLine = true
                        )
                        DropdownMenu(expanded = fromMenu, onDismissRequest = { fromMenu = false }) {
                            DropdownMenuItem(text = { Text("Current location") }, leadingIcon = { Icon(Icons.Default.MyLocation, null) }, onClick = { fromMenu = false })
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        val destinationLabel = vm.destinations.firstOrNull { it.id == selectedDestination }?.name.orEmpty()
                        OutlinedTextField(
                            value = destinationLabel, onValueChange = {}, readOnly = true,
                            label = { Text("To") },
                            leadingIcon = { Icon(Icons.Default.Place, null, tint = Color(0xFFDC2626)) },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                            modifier = Modifier.fillMaxWidth().clickable { toMenu = true },
                            shape = RoundedCornerShape(14.dp), singleLine = true
                        )
                        DropdownMenu(expanded = toMenu, onDismissRequest = { toMenu = false }) {
                            OutlinedTextField(
                                value = destinationQuery,
                                onValueChange = { destinationQuery = it },
                                label = { Text("Search local destinations") },
                                singleLine = true,
                                modifier = Modifier.padding(horizontal = 10.dp).widthIn(min = 220.dp, max = 320.dp)
                            )
                            vm.destinations.filter { it.name.contains(destinationQuery, ignoreCase = true) }.forEach { destination ->
                                DropdownMenuItem(
                                    text = { Column { Text(destination.name); Text(destination.description, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B)) } },
                                    onClick = { selectedDestination = destination.id; destinationQuery = ""; toMenu = false }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.startNavigation(selectedDestination) }, modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Directions, null); Spacer(Modifier.width(6.dp)); Text("Start Navigation")
                        }
                        if (route != null) OutlinedButton(onClick = vm::clearRoute, modifier = Modifier.height(46.dp), shape = RoundedCornerShape(12.dp)) { Text("End route") }
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color(0xFFEFF3F6))
            ) {
                PrototypeMap(nav, route, outageTelemetry.trajectory)
                if (route != null) {
                    ManeuverCard(guidance, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp))
                }
                Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 3.dp) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text("CURRENT POSITION", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                        Text("%.5f, %.5f".format(nav.lat, nav.lon), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
            QuickStats(nav)
        }
    }

    if (showContacts) {
        EmergencyContactsDialog(
            contacts = contacts,
            onDismiss = { showContacts = false },
            onAdd = { editingContact = null; pickedContact = null; showContactEditor = true },
            onEdit = { editingContact = it; showContactEditor = true },
            onRemove = vm::removeEmergencyContact,
            onCall = { number -> callContact(number) }
        )
    }
    if (showContactEditor) {
        ContactEditorDialog(
            initial = editingContact,
            pickedContact = pickedContact,
            onChooseFromContacts = {
                try {
                    contactPickerLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                } catch (_: ActivityNotFoundException) {
                    callError = "No system contacts picker is available. You can enter the number manually."
                }
            },
            onDismiss = { showContactEditor = false },
            onSave = { id, name, phone -> vm.saveEmergencyContact(id, name, phone); pickedContact = null; showContactEditor = false }
        )
    }
    if (showDiagnostics) {
        ModalBottomSheet(onDismissRequest = { showDiagnostics = false }, containerColor = Color.White) {
            DiagnosticsPanel(nav, sensor, realGnss, outageTelemetry, outage || nav.mode == NavigationMode.DEAD_RECKONING)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showCalibration) {
        ModalBottomSheet(onDismissRequest = { showCalibration = false }, containerColor = Color.White) {
            CalibrationPanel(sensor, onReset = vm::resetSensorCalibration)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showCallPermissionHelp) {
        AlertDialog(
            onDismissRequest = { showCallPermissionHelp = false },
            title = { Text("Phone permission needed") },
            text = { Text("Allow Phone permission to place a direct call. You can grant it here, or open app settings if Android no longer shows the permission prompt.") },
            confirmButton = {
                TextButton(onClick = { callPermissionLauncher.launch(Manifest.permission.CALL_PHONE) }) { Text("Try again") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCallPermissionHelp = false
                    val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    try { context.startActivity(settingsIntent) } catch (_: ActivityNotFoundException) { callError = "Unable to open app settings on this device." }
                }) { Text("Open app settings") }
            }
        )
    } else if (showSmsPermissionHelp) {
        AlertDialog(
            onDismissRequest = { showSmsPermissionHelp = false },
            title = { Text("Location SMS permission") },
            text = { Text("SMS permission was denied. An addressed location-message draft was opened; tap Send in your SMS app. The emergency call is handled separately. You can grant SMS access now and submit the location message from here.") },
            confirmButton = { TextButton(onClick = { smsPermissionLauncher.launch(Manifest.permission.SEND_SMS) }) { Text("Grant and send") } },
            dismissButton = {
                TextButton(onClick = {
                    showSmsPermissionHelp = false
                    val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    try { context.startActivity(settingsIntent) } catch (_: ActivityNotFoundException) { callError = "Unable to open app settings on this device." }
                }) { Text("App settings") }
            }
        )
    } else if (showLocationFeedback) {
        AlertDialog(
            onDismissRequest = { showLocationFeedback = false },
            title = { Text("Current location") },
            text = { Text(locationFeedback) },
            confirmButton = { TextButton(onClick = { showLocationFeedback = false }) { Text("OK") } },
            dismissButton = {
                TextButton(onClick = {
                    showLocationFeedback = false
                    val action = if (locationPermissionMissing) Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    val settingsIntent = if (locationPermissionMissing) {
                        Intent(action, Uri.fromParts("package", context.packageName, null))
                    } else Intent(action)
                    try { context.startActivity(settingsIntent) } catch (_: ActivityNotFoundException) {
                        locationFeedback = "Unable to open location settings on this device."
                        showLocationFeedback = true
                    }
                }) { Text(if (locationPermissionMissing) "App permissions" else "Location settings") }
            }
        )
    } else if (showMultiCallFlow) {
        EmergencyMultiCallDialog(
            contacts = contacts,
            currentIndex = multiCallIndex,
            dialerOpened = dialerOpenedForCurrent,
            onOpenDialer = { contact ->
                try {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", PhoneNumberUtils.stripSeparators(contact.phoneNumber), null)))
                    dialerOpenedForCurrent = true
                } catch (_: ActivityNotFoundException) {
                    callError = "No phone dialer is available for the next contact."
                }
            },
            onContinue = { multiCallIndex = (multiCallIndex + 1).coerceAtMost(contacts.size); dialerOpenedForCurrent = false },
            onManage = { showMultiCallFlow = false; showContacts = true },
            onDismiss = { showMultiCallFlow = false }
        )
    }
    callError?.let { message ->
        AlertDialog(onDismissRequest = { callError = null }, title = { Text("Action unavailable") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { callError = null }) { Text("OK") } })
    }
}

@Composable
private fun StatusBanner(nav: NavState, outage: Boolean, recovering: Boolean, telemetry: OutageTelemetry) {
    val (label, color, icon) = when {
        recovering -> Triple("GNSS RECOVERY", Color(0xFF2563EB), Icons.Default.Sync)
        nav.mode == NavigationMode.GNSS_INS -> Triple("GNSS + INS", Color(0xFF16A34A), Icons.Default.GpsFixed)
        nav.mode == NavigationMode.DEAD_RECKONING -> Triple("DEAD RECKONING", Color(0xFFF59E0B), Icons.Default.Navigation)
        nav.mode == NavigationMode.RECOVERY -> Triple("GNSS RECOVERY", Color(0xFF2563EB), Icons.Default.Sync)
        else -> Triple("DEMO", Color(0xFF7C3AED), Icons.Default.PlayArrow)
    }
    Surface(color = color.copy(alpha = 0.10f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, color = color)
                Text(
                    when {
                        recovering -> "GNSS returned • reconciling DR trajectory"
                        outage -> "GNSS loss simulated • real sensors driving DR"
                        nav.mode == NavigationMode.DEAD_RECKONING -> "GNSS unavailable • continuing from IMU"
                        else -> "GNSS aiding inertial navigation"
                    },
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF475569)
                )
            }
            if (nav.mode == NavigationMode.DEAD_RECKONING) Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF7ED)) {
                Text("LOSS • ${telemetry.durationMs / 1000}s • ${telemetry.distanceM.roundToInt()} m", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFC2410C))
            }
        }
    }
}

@Composable
private fun ManeuverCard(guidance: RouteGuidance, modifier: Modifier = Modifier) {
    val arrow = when (guidance.direction) {
        ManeuverDirection.LEFT -> "←"
        ManeuverDirection.RIGHT -> "→"
        ManeuverDirection.ARRIVAL -> "⚑"
        ManeuverDirection.STRAIGHT -> "↑"
    }
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, color = Color(0xFF2563EB), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (guidance.arrived) "ARRIVED" else guidance.instruction, fontWeight = FontWeight.Bold)
                Text(if (guidance.arrived) guidance.destinationName else "${guidance.distanceToManeuverM.roundToInt()} m to turn • ${guidance.remainingDistanceM.roundToInt()} m remaining${guidance.etaSeconds?.let { " • ~${(it / 60).roundToInt()} min" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            }
        }
    }
}

@Composable
private fun QuickStats(nav: NavState) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Speed", "${(nav.speedMps * 3.6).roundToInt()} km/h", Modifier.weight(1f))
        StatCard("Heading", "${nav.headingDeg.roundToInt()}°", Modifier.weight(1f))
        StatCard("GNSS", if (nav.gnssAvailable) "FIX" else if (nav.mode == NavigationMode.DEAD_RECKONING) "LOST" else "WAIT", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DiagnosticsPanel(nav: NavState, s: SensorSnapshot, gnss: GnssSnapshot, outage: OutageTelemetry, outageActive: Boolean) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Live diagnostics", fontWeight = FontWeight.Bold)
            Text("Accelerometer  %.2f  %.2f  %.2f m/s²".format(s.ax, s.ay, s.az), style = MaterialTheme.typography.bodySmall)
            Text("Gyroscope      %.2f  %.2f  %.2f rad/s".format(s.gx, s.gy, s.gz), style = MaterialTheme.typography.bodySmall)
            Text("Magnetometer   %.2f  %.2f  %.2f µT".format(s.mx, s.my, s.mz), style = MaterialTheme.typography.bodySmall)
            Text(if (gnss.timestampMs > 0L) "GNSS %.6f, %.6f • accuracy %s • speed %.1f m/s".format(gnss.lat, gnss.lon, if (gnss.accuracyM.isFinite()) "%.1f m".format(gnss.accuracyM) else "not reported", gnss.speedMps) else "GNSS fix not available", style = MaterialTheme.typography.bodySmall)
            Text("Heading %.1f° • DR speed %.1f m/s".format(nav.headingDeg, nav.speedMps), style = MaterialTheme.typography.bodySmall)
            Text(if (outageActive) "Outage %.0f s • distance since loss %.1f m".format(outage.durationMs / 1000.0, outage.distanceM) else "No simulated outage active", style = MaterialTheme.typography.bodySmall)
            Text(s.orientationStatus, style = MaterialTheme.typography.labelSmall, color = Color(0xFF475569))
            Text("Mode: ${nav.mode} • position accuracy: ${if (nav.accuracyM.isFinite()) "%.1f m".format(nav.accuracyM) else "not estimated"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CalibrationPanel(sensor: SensorSnapshot, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sensor calibration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Place the phone steadily in its vehicle mount. Stationary bias estimates are learned from real sensor samples and saved on this device.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF475569))
        LinearProgressIndicator(progress = { sensor.calibrationProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        CalibrationRow("Accelerometer", sensor.accelerometerAvailable, sensor.accelerometerBiasCalibrated, "Stationary horizontal bias estimate")
        CalibrationRow("Gyroscope", sensor.gyroscopeAvailable, sensor.gyroBiasCalibrated, "Stationary angular-rate bias estimate")
        val magneticStatus = when (sensor.magnetometerAccuracy) {
            3 -> "Android reports high compass accuracy"
            2 -> "Android reports medium compass accuracy"
            1 -> "Move the phone in a figure eight; Android reports low accuracy"
            else -> "Android compass calibration is not confirmed"
        }
        CalibrationRow("Magnetometer", sensor.magnetometerAvailable, sensor.magnetometerAvailable && sensor.magnetometerAccuracy >= 2, magneticStatus)
        CalibrationRow("Orientation", sensor.rotationVectorAvailable || sensor.orientationValid, sensor.orientationValid, if (sensor.rotationVectorAvailable) "Android rotation-vector orientation" else "Gravity + magnetic fallback when available")
        Text("Calibration is a prototype bias estimate, not a measure of navigation accuracy. Phone-to-vehicle alignment still depends on mounting and GNSS motion heading.", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Reset learned sensor biases") }
    }
}

@Composable
private fun CalibrationRow(label: String, available: Boolean, ready: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ready) Icons.Default.CheckCircle else Icons.Default.Info, null, tint = if (ready) Color(0xFF16A34A) else Color(0xFFF59E0B))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(if (!available) "Unavailable on this device" else detail, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
        }
        Text(if (!available) "N/A" else if (ready) "Ready" else "Calibrating", style = MaterialTheme.typography.labelMedium, color = if (ready) Color(0xFF15803D) else Color(0xFFB45309))
    }
}

@Composable
private fun EmergencyContactsDialog(
    contacts: List<EmergencyContact>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (EmergencyContact) -> Unit,
    onRemove: (String) -> Unit,
    onCall: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency Contacts") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Saved on this device only", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
                Spacer(Modifier.height(8.dp))
                if (contacts.isEmpty()) Text("No emergency contacts yet. Add a name and phone number to set up emergency calling.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, fontWeight = FontWeight.SemiBold)
                                Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                            }
                            IconButton(onClick = { onCall(contact.phoneNumber) }) { Icon(Icons.Default.Call, "Call ${contact.name}") }
                            IconButton(onClick = { onEdit(contact) }) { Icon(Icons.Default.Edit, "Edit") }
                            IconButton(onClick = { onRemove(contact.id) }) { Icon(Icons.Default.Delete, "Remove ${contact.name}", tint = Color(0xFFDC2626)) }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("+ Add Emergency Contact")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun EmergencyMultiCallDialog(
    contacts: List<EmergencyContact>,
    currentIndex: Int,
    dialerOpened: Boolean,
    onOpenDialer: (EmergencyContact) -> Unit,
    onContinue: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    val current = contacts.getOrNull(currentIndex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency calling") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The first contact was called. Add each next contact through the Phone app. Android and your carrier control conference support; IDR cannot press Add call or Merge and cannot verify that anyone joined the conference.")
                if (current != null) {
                    Text("Next contact ${currentIndex + 1} of ${contacts.size}", style = MaterialTheme.typography.labelLarge, color = Color(0xFF2563EB), fontWeight = FontWeight.Bold)
                    Text(current.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(current.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF64748B))
                    Text(if (dialerOpened) "Use the Phone app to place this call and merge if supported. Return and continue only after Phone confirms the call was added or merged." else "Open the native dialer with this number, use Add call, then Merge if your phone offers those controls." , style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Every saved contact was offered in sequence. Check the native Phone app to confirm which calls, if any, are connected or merged.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (current != null && !dialerOpened) TextButton(onClick = { onOpenDialer(current) }) {
                Text("Open Phone for next contact")
            } else if (current != null) TextButton(onClick = onContinue) {
                Text("I added this contact — next")
            } else TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onManage) { Text("Manage contacts") } }
    )
}

@Composable
private fun ContactEditorDialog(
    initial: EmergencyContact?,
    pickedContact: Pair<String, String>?,
    onChooseFromContacts: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String?, String, String) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember(initial?.id) { mutableStateOf(initial?.phoneNumber.orEmpty()) }
    LaunchedEffect(pickedContact) {
        pickedContact?.let { (pickedName, pickedPhone) -> name = pickedName; phone = pickedPhone }
    }
    val validPhone = PhoneNumberUtils.isGlobalPhoneNumber(phone.trim()) && phone.count(Char::isDigit) in 3..15
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add emergency contact" else "Edit emergency contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it }, label = { Text("Phone number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true,
                    isError = phone.isNotBlank() && !validPhone,
                    supportingText = { if (phone.isNotBlank() && !validPhone) Text("Enter a valid number with 3–15 digits.") }
                )
                OutlinedButton(onClick = onChooseFromContacts, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Contacts, null); Spacer(Modifier.width(8.dp)); Text("Choose from Contacts")
                }
                Text("Or enter the name and number above. Contact details stay on this device.", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && validPhone, onClick = { onSave(initial?.id, name, phone) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PrototypeMap(nav: NavState, route: LocalRoute?, trajectory: List<RoutePoint>) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFFF1F5F4), Offset.Zero, Size(w, h))
        // Locally rendered neighborhood blocks and streets; no tile service.
        for (i in 0..7) {
            val x = w * i / 7f
            drawLine(Color(0xFFD7DEE3), Offset(x, 0f), Offset(x + 45f, h), 4f)
        }
        for (i in 0..6) {
            val y = h * i / 6f
            drawLine(Color(0xFFD7DEE3), Offset(0f, y), Offset(w, y + 22f), 4f)
        }
        drawRect(Color(0xFFE1EEDF), Offset(0f, 0f), Size(w * .20f, h * .20f))
        drawRect(Color(0xFFE1EEDF), Offset(w * .78f, h * .72f), Size(w * .22f, h * .28f))

        val points = route?.points.orEmpty()
        if (points.size >= 2 || trajectory.size >= 2) {
            val all = points + trajectory + RoutePoint(nav.lat, nav.lon)
            val minLat = all.minOf { it.latitude }; val maxLat = all.maxOf { it.latitude }
            val minLon = all.minOf { it.longitude }; val maxLon = all.maxOf { it.longitude }
            val latRange = (maxLat - minLat).coerceAtLeast(0.00001)
            val lonRange = (maxLon - minLon).coerceAtLeast(0.00001)
            fun position(point: RoutePoint): Offset = Offset(
                30f + ((point.longitude - minLon) / lonRange * (w - 60f)).toFloat(),
                30f + ((maxLat - point.latitude) / latRange * (h - 60f)).toFloat()
            )
            val path = Path().apply {
                points.forEachIndexed { index, point ->
                    val p = position(point)
                    if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            if (points.size >= 2) {
                drawPath(path, Color.White, style = Stroke(width = 15f, cap = StrokeCap.Round))
                drawPath(path, Color(0xFF2563EB), style = Stroke(width = 8f, cap = StrokeCap.Round))
            }
            if (trajectory.size >= 2) {
                val trail = Path().apply {
                    trajectory.forEachIndexed { index, point ->
                        val p = position(point)
                        if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                }
                drawPath(trail, Color(0xFFEA580C), style = Stroke(width = 5f, cap = StrokeCap.Round))
            }
            if (points.size >= 2) {
                val destination = position(points.last())
                drawCircle(Color(0xFFDC2626), 12f, destination)
                drawCircle(Color.White, 5f, destination)
            }
            val car = position(RoutePoint(nav.lat, nav.lon))
            drawCircle(Color.White, 17f, car)
            drawCircle(Color(0xFF2563EB), 12f, car)
            val bearing = Math.toRadians(nav.headingDeg)
            drawLine(Color.White, car, Offset(car.x + sin(bearing).toFloat() * 10f, car.y - cos(bearing).toFloat() * 10f), 4f, cap = StrokeCap.Round)
        } else {
            val cx = w * .5f; val cy = h * .52f
            drawCircle(Color.White, 20f, Offset(cx, cy)); drawCircle(Color(0xFF2563EB), 15f, Offset(cx, cy))
            val bearing = Math.toRadians(nav.headingDeg)
            drawLine(Color.White, Offset(cx, cy), Offset(cx + sin(bearing).toFloat() * 11f, cy - cos(bearing).toFloat() * 11f), 4f, cap = StrokeCap.Round)
        }
    }
}

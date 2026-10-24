package com.sonicdrop.ui.main

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicdrop.R
import com.sonicdrop.audio.AudioController
import com.sonicdrop.data.AppThemeMode
import com.sonicdrop.data.HistoryRepository
import com.sonicdrop.data.SettingsRepository
import com.sonicdrop.data.TransferRecord
import com.sonicdrop.theme.*
import com.sonicdrop.transfer.TransferManager
import com.sonicdrop.transfer.TransferPhase
import com.sonicdrop.transfer.TransferRole
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class AppTab {
    HOME, HISTORY, SETTINGS
}

@Composable
fun SonicDropApp() {
    val context = LocalContext.current
    val audioController = remember { AudioController(context) }
    val historyRepository = remember { HistoryRepository(context) }
    val settingsRepository = remember { SettingsRepository(context) }
    val transferManager = remember { TransferManager(context, audioController, historyRepository) }

    val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle()
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemDark
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    SonicDropTheme(darkTheme = isDark) {
        var currentTab by remember { mutableStateOf(AppTab.HOME) }
        val phase by transferManager.phase.collectAsStateWithLifecycle()

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                // Navigation bar shown when not in active transfer screen
                if (phase is TransferPhase.Idle) {
                    SonicBottomNavigation(
                        currentTab = currentTab,
                        onTabSelected = { currentTab = it }
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    // Active transfer workflows take precedence
                    phase !is TransferPhase.Idle -> {
                        TransferWorkflowView(
                            transferManager = transferManager,
                            audioController = audioController,
                            onExit = { transferManager.cancelTransfer() }
                        )
                    }
                    currentTab == AppTab.HOME -> {
                        HomeScreen(
                            transferManager = transferManager,
                            audioController = audioController,
                            historyRepository = historyRepository,
                            onNavigateToHistory = { currentTab = AppTab.HISTORY }
                        )
                    }
                    currentTab == AppTab.HISTORY -> {
                        HistoryScreen(historyRepository = historyRepository)
                    }
                    currentTab == AppTab.SETTINGS -> {
                        SettingsScreen(
                            settingsRepository = settingsRepository,
                            audioController = audioController
                        )
                    }
                }
            }
        }
    }
}

// Bottom Navigation
@Composable
fun SonicBottomNavigation(
    currentTab: AppTab,
    onTabSelected: (AppTab) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(
                label = "Home",
                glyph = "⌂",
                isSelected = currentTab == AppTab.HOME,
                onClick = { onTabSelected(AppTab.HOME) }
            )
            NavItem(
                label = "History",
                glyph = "≡",
                isSelected = currentTab == AppTab.HISTORY,
                onClick = { onTabSelected(AppTab.HISTORY) }
            )
            NavItem(
                label = "Settings",
                glyph = "⚙",
                isSelected = currentTab == AppTab.SETTINGS,
                onClick = { onTabSelected(AppTab.SETTINGS) }
            )
        }
    }
}

@Composable
fun NavItem(
    label: String,
    glyph: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp)
    ) {
        Text(
            text = glyph,
            fontSize = 20.sp,
            color = if (isSelected) activeColor else inactiveColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) activeColor else inactiveColor
        )
    }
}

// Home Screen
@Composable
fun HomeScreen(
    transferManager: TransferManager,
    audioController: AudioController,
    historyRepository: HistoryRepository,
    onNavigateToHistory: () -> Unit
) {
    val snr by audioController.currentSnr.collectAsStateWithLifecycle()
    val isListening by audioController.isListening.collectAsStateWithLifecycle()
    val history by historyRepository.records.collectAsStateWithLifecycle()

    var pendingSendUri by remember { mutableStateOf<Uri?>(null) }

    val senderPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val uri = pendingSendUri
        if (granted && uri != null) {
            transferManager.startSending(uri)
        }
        pendingSendUri = null
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (audioController.hasRecordPermission()) {
                transferManager.startSending(uri)
            } else {
                pendingSendUri = uri
                senderPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            transferManager.startReceiving()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Branding & Logo
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Image(
                painter = painterResource(id = R.drawable.ic_sonic_drop_logo),
                contentDescription = "Sonic-Drop Logo",
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Move files. No network needed.",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Transfer encrypted data device-to-device entirely over near-ultrasonic acoustic waves.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Audio Readiness Card
        item {
            AudioReadinessCard(
                isListening = isListening,
                snrDb = snr,
                hasPermission = audioController.hasRecordPermission()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Primary Actions (Send Files & Receive Files)
        item {
            Button(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "→  Send Files",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    if (audioController.hasRecordPermission()) {
                        transferManager.startReceiving()
                    } else {
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = "↓  Receive Files",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }

        // Recent Activity Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Activity",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (history.isNotEmpty()) {
                    Text(
                        text = "View All",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onNavigateToHistory() }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (history.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No transfers yet",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Transfers you send or receive via acoustic sound will appear here.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(history.take(3)) { record ->
                HistoryRowItem(record = record)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun AudioReadinessCard(
    isListening: Boolean,
    snrDb: Float,
    hasPermission: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (isListening) DarkSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🔊",
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isListening) "ULTRASONIC RX LIVE (18–20 kHz)" else "ACOUSTIC ENGINE READY",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isListening) DarkSuccess else MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (!hasPermission) "Microphone permission required for receiving"
                    else if (isListening) "Listening for incoming packet preambles"
                    else "48 kHz PCM hardware ready for transmission",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Active Transfer Workflow View
@Composable
fun TransferWorkflowView(
    transferManager: TransferManager,
    audioController: AudioController,
    onExit: () -> Unit
) {
    val phase by transferManager.phase.collectAsStateWithLifecycle()
    val role by transferManager.role.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        when (val currentPhase = phase) {
            is TransferPhase.ListeningForPeer -> {
                ListeningScreen(onCancel = onExit)
            }
            is TransferPhase.KeyExchange -> {
                KeyExchangeScreen()
            }
            is TransferPhase.AuthVerification -> {
                SessionAuthScreen(
                    sasCode = currentPhase.sasCode,
                    onConfirm = { transferManager.confirmAuthentication() },
                    onCancel = onExit
                )
            }
            is TransferPhase.InProgress -> {
                ActiveTransferScreen(
                    progress = currentPhase,
                    onCancel = onExit
                )
            }
            is TransferPhase.Success -> {
                CompletionSuccessScreen(
                    success = currentPhase,
                    onDone = onExit
                )
            }
            is TransferPhase.Failed -> {
                CompletionFailedScreen(
                    error = currentPhase.error,
                    onDismiss = onExit
                )
            }
            else -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ListeningScreen(onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = DarkSuccess,
            modifier = Modifier.size(54.dp),
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Listening for Sender",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Keep receiver speaker and microphone close to the transmitting phone (5 cm – 50 cm).",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Text("Stop Listening", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun KeyExchangeScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(50.dp),
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Performing X25519 Key Agreement",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Negotiating forward-secret ephemeral session keys over sound waves",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SessionAuthScreen(
    sasCode: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Authenticate Session",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Verify this Short Authentication String (SAS) matches the other device to protect against Man-in-the-Middle attacks:",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = sasCode,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 32.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reject", color = DarkError)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Confirm Match", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ActiveTransferScreen(
    progress: TransferPhase.InProgress,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = progress.fileName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { progress.percent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${(progress.percent * 100).toInt()}% (${progress.bytesTransferred} / ${progress.totalBytes} B)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Packet ${progress.packetIndex}/${progress.totalPackets}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkWarning
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Speed: ${progress.currentSpeedBps.toInt()} B/s",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "SNR: ${"%.1f".format(progress.snrDb)} dB",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel Transfer", color = DarkError)
            }
        }
    }
}

@Composable
fun CompletionSuccessScreen(
    success: TransferPhase.Success,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(DarkSuccess.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✓", fontSize = 32.sp, color = DarkSuccess, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Transfer Completed & Verified",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${success.fileName} (${success.totalBytes} bytes)",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))

        Row {
            if (success.filePath != null) {
                OutlinedButton(
                    onClick = {
                        try {
                            val file = File(success.filePath)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // File view intent fallback
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Open File")
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Done", color = Color.White)
            }
        }
    }
}

@Composable
fun CompletionFailedScreen(
    error: String,
    onDismiss: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(DarkError.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "⚠", fontSize = 32.sp, color = DarkError, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Transfer Failed",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = DarkError
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Dismiss", color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

// History Screen
@Composable
fun HistoryScreen(historyRepository: HistoryRepository) {
    val history by historyRepository.records.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Transfer History",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (history.isNotEmpty()) {
                Text(
                    text = "Clear All",
                    fontSize = 13.sp,
                    color = DarkError,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showClearDialog = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "📁", fontSize = 42.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "History is empty",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Completed air-gapped transfers will be logged here.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(history) { record ->
                    HistoryRowItem(record = record)
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History") },
            text = { Text("Are you sure you want to clear your transfer records? Actual saved files will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    historyRepository.clearHistory()
                    showClearDialog = false
                }) {
                    Text("Clear", color = DarkError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun HistoryRowItem(record: TransferRecord) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (record.isSender) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else DarkWarning.copy(alpha = 0.15f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (record.isSender) "↑" else "↓",
                    fontSize = 18.sp,
                    color = if (record.isSender) MaterialTheme.colorScheme.primary else DarkWarning,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$dateStr • ${record.fileSize} Bytes • ${"%.1f".format(record.durationSeconds)}s",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = if (record.isSuccess) "Verified" else "Failed",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (record.isSuccess) DarkSuccess else DarkError
            )
        }
    }
}

// Settings Screen
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    audioController: AudioController
) {
    val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle()
    val markFreq by settingsRepository.markFreq.collectAsStateWithLifecycle()
    val spaceFreq by settingsRepository.spaceFreq.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Appearance
        item {
            SettingsSectionHeader(title = "Appearance")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Theme Selection",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ThemeOptionButton(
                            label = "System",
                            isSelected = themeMode == AppThemeMode.SYSTEM,
                            onClick = { settingsRepository.setThemeMode(AppThemeMode.SYSTEM) }
                        )
                        ThemeOptionButton(
                            label = "Dark",
                            isSelected = themeMode == AppThemeMode.DARK,
                            onClick = { settingsRepository.setThemeMode(AppThemeMode.DARK) }
                        )
                        ThemeOptionButton(
                            label = "Light",
                            isSelected = themeMode == AppThemeMode.LIGHT,
                            onClick = { settingsRepository.setThemeMode(AppThemeMode.LIGHT) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Audio Configuration
        item {
            SettingsSectionHeader(title = "Audio Configuration")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsInfoRow(label = "Hardware Sample Rate", value = "48,000 Hz")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "Mark Frequency (Bit 1)", value = "${markFreq.toInt()} Hz")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "Space Frequency (Bit 0)", value = "${spaceFreq.toInt()} Hz")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "Modulation", value = "Continuous-Phase BFSK")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "Error Correction", value = "Reed-Solomon GF(2^8) + CRC16")
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Security & Privacy
        item {
            SettingsSectionHeader(title = "Security & Privacy")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsInfoRow(label = "Key Agreement", value = "Ephemeral X25519 (ECDH)")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "Payload Cipher", value = "ChaCha20-Poly1305 AEAD")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "Authentication", value = "Visual 6-Digit SAS Code")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "Network Connections", value = "Zero (Air-Gapped)")
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // About & Version
        item {
            SettingsSectionHeader(title = "About")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsInfoRow(label = "Version", value = "1.0.0 (Production Engine)")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "License", value = "MIT Open Source")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsInfoRow(label = "Repository", value = "github.com/Rohanjm911/Sonic-Drop")
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ThemeOptionButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

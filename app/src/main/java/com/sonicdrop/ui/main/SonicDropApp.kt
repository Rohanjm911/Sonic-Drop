package com.sonicdrop.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicdrop.R
import com.sonicdrop.audio.AudioController
import com.sonicdrop.theme.*
import com.sonicdrop.transfer.TransferManager
import com.sonicdrop.transfer.TransferPhase
import com.sonicdrop.transfer.TransferRole

@Composable
fun SonicDropApp() {
    val context = LocalContext.current
    val audioController = remember { AudioController(context) }
    val transferManager = remember { TransferManager(context, audioController) }

    val phase by transferManager.phase.collectAsStateWithLifecycle()
    val role by transferManager.role.collectAsStateWithLifecycle()
    val snr by audioController.currentSnr.collectAsStateWithLifecycle()
    val isListening by audioController.isListening.collectAsStateWithLifecycle()

    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }

    // File picker launcher
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            transferManager.startSending(uri)
        }
    }

    // Permission launcher for microphone
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            transferManager.startReceiving()
        }
    }

    Scaffold(
        containerColor = CharcoalBackground,
        contentColor = TextPrimary
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Branding Bar
                HeaderSection(isListening = isListening, snrDb = snr)

                Spacer(modifier = Modifier.height(24.dp))

                // Dynamic Stage Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when (val currentPhase = phase) {
                        is TransferPhase.Idle -> {
                            IdleActionView(
                                onSendClick = { filePicker.launch("*/*") },
                                onReceiveClick = {
                                    if (audioController.hasRecordPermission()) {
                                        transferManager.startReceiving()
                                    } else {
                                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            )
                        }
                        is TransferPhase.ListeningForPeer -> {
                            ListeningView(
                                onCancel = { transferManager.cancelTransfer() }
                            )
                        }
                        is TransferPhase.KeyExchange -> {
                            KeyExchangeView(role = role)
                        }
                        is TransferPhase.AuthVerification -> {
                            AuthVerificationView(
                                sasCode = currentPhase.sasCode,
                                onConfirm = { transferManager.confirmAuthentication() },
                                onCancel = { transferManager.cancelTransfer() }
                            )
                        }
                        is TransferPhase.InProgress -> {
                            TransferProgressView(
                                progress = currentPhase,
                                onCancel = { transferManager.cancelTransfer() }
                            )
                        }
                        is TransferPhase.Success -> {
                            SuccessView(
                                success = currentPhase,
                                onDone = { transferManager.cancelTransfer() }
                            )
                        }
                        is TransferPhase.Failed -> {
                            FailedView(
                                error = currentPhase.error,
                                onDismiss = { transferManager.cancelTransfer() }
                            )
                        }
                        else -> {
                            CircularProgressIndicator(color = ElectricBlue)
                        }
                    }
                }

                // Security & Channel Status Footer
                FooterSecurityStatus(phase = phase)
            }
        }
    }
}

@Composable
fun HeaderSection(isListening: Boolean, snrDb: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // App Logo
        Image(
            painter = painterResource(id = R.drawable.ic_sonic_drop_logo),
            contentDescription = "Sonic-Drop Logo",
            modifier = Modifier
                .size(110.dp)
                .clip(RoundedCornerShape(20.dp)),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            color = if (isListening) SonicGreen.copy(alpha = 0.15f) else ElectricBlue.copy(alpha = 0.15f),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isListening) SonicGreen.copy(alpha = 0.6f) else ElectricBlueLight.copy(alpha = 0.6f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (isListening) SonicGreen else ElectricBlueLight,
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isListening) "ULTRASONIC RX LIVE (18-20 kHz)" else "ACOUSTIC ENGINE READY",
                    color = if (isListening) SonicGreen else ElectricBlueLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun IdleActionView(onSendClick: () -> Unit, onReceiveClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Offline Air-Gapped Transfer",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Transfers files completely without Wi-Fi, Bluetooth, or cellular data using encrypted acoustic frequencies.",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(36.dp))

        Button(
            onClick = onSendClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("→  Send File", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedButton(
            onClick = onReceiveClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(CharcoalBorder)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("↓  Receive File", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SonicOrange)
        }
    }
}

@Composable
fun ListeningView(onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = SonicGreen,
            modifier = Modifier.size(56.dp),
            strokeWidth = 3.dp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Listening for Sender Beacon",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Keep receiver speaker and microphone close to the transmitting device.",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(10.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(CharcoalBorder))
        ) {
            Text("Stop Listening", color = TextSecondary)
        }
    }
}

@Composable
fun KeyExchangeView(role: TransferRole) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = ElectricBlue, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Performing X25519 Key Agreement...",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Negotiating forward-secret ephemeral session keys over sound",
            fontSize = 12.sp,
            color = TextMuted
        )
    }
}

@Composable
fun AuthVerificationView(
    sasCode: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CharcoalSurface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CharcoalBorder))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Authenticate Session",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Verify this Short Authentication String (SAS) matches the peer screen to prevent Man-in-the-Middle eavesdropping:",
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                color = CharcoalSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue)
            ) {
                Text(
                    text = sasCode,
                    color = ElectricBlueLight,
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
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Reject", color = SonicRed)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Confirm Match")
                }
            }
        }
    }
}

@Composable
fun TransferProgressView(
    progress: TransferPhase.InProgress,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CharcoalSurface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CharcoalBorder))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = progress.fileName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { progress.percent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = ElectricBlue,
                trackColor = CharcoalSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${(progress.percent * 100).toInt()}% (${progress.bytesTransferred} / ${progress.totalBytes} B)",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Text(
                    text = "Packet ${progress.packetIndex}/${progress.totalPackets}",
                    fontSize = 12.sp,
                    color = SonicOrange
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Speed: ${progress.currentSpeedBps.toInt()} B/s",
                    fontSize = 11.sp,
                    color = TextMuted
                )
                Text(
                    text = "SNR: ${"%.1f".format(progress.snrDb)} dB",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancel Transfer", color = SonicRed)
            }
        }
    }
}

@Composable
fun SuccessView(success: TransferPhase.Success, onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "✓",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = SonicGreen
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Transfer Verified & Completed",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "${success.fileName} (${success.totalBytes} bytes)",
            fontSize = 13.sp,
            color = TextSecondary
        )

        if (success.filePath != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Saved to: ${success.filePath}",
                fontSize = 11.sp,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Done")
        }
    }
}

@Composable
fun FailedView(error: String, onDismiss: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "⚠",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = SonicRed
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Transfer Failed",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SonicRed
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = error,
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = CharcoalSurfaceVariant),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Dismiss", color = TextPrimary)
        }
    }
}

@Composable
fun FooterSecurityStatus(phase: TransferPhase) {
    Surface(
        color = CharcoalSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CharcoalBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "🔒 ChaCha20-Poly1305 + RS(N,K)",
                fontSize = 11.sp,
                color = TextSecondary
            )

            Text(
                text = "Air-Gapped",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SonicOrange
            )
        }
    }
}

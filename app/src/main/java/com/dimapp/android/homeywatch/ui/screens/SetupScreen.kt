package com.dimapp.android.homeywatch.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.auth.HomeyAuthRepository
import com.dimapp.android.homeywatch.auth.PollResult
import com.dimapp.android.homeywatch.discovery.DiscoveredHomey
import com.dimapp.android.homeywatch.discovery.HomeyMdnsDiscovery
import com.dimapp.android.homeywatch.storage.TokenStorage
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import com.dimapp.android.homeywatch.ui.theme.TextPrimary
import com.dimapp.android.homeywatch.ui.theme.TextSecondary
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Setup and authentication screen for Wear OS.
 *
 * Supports:
 * - Automatic zero-configuration discovery of Homey Pro hubs via mDNS (_homey._tcp)
 * - Manual Homey ID entry with Gboard / voice dictation fallback (or "demo" for demo mode)
 * - Launching authorization on paired phone via RemoteActivityHelper
 * - Displaying QR Code on watch display
 */
@Composable
fun SetupScreen(
    authRepo: HomeyAuthRepository,
    storage: TokenStorage,
    onAuthSuccess: () -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val discovery = remember { HomeyMdnsDiscovery(context) }
    val discoveredHubs by discovery.discoveredHubs.collectAsState()
    val isSearching by discovery.isSearching.collectAsState()

    var homeyIdInput by remember { mutableStateOf(if (onCancel != null) "" else (storage.getSelectedHomeyId() ?: "")) }
    var isPairingActive by remember { mutableStateOf(false) }
    var isManualEntryExpanded by remember { mutableStateOf(false) }
    var authUrl by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showQrCode by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPhoneDispatched by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
    val refreshRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refresh_angle"
    )

    // Start discovery automatically on screen entry
    LaunchedEffect(Unit) {
        discovery.startDiscovery(timeoutMs = 12000L)
    }

    // Clean up discovery listeners when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            discovery.stopDiscovery()
        }
    }

    // Polling effect when pairing is active
    LaunchedEffect(isPairingActive) {
        if (!isPairingActive) return@LaunchedEffect

        discovery.stopDiscovery()

        val cleanId = homeyIdInput.trim()
        val sessionId = _generateSessionId()

        val url = authRepo.startCompanionAuth(cleanId, sessionId)
        if (url == null) {
            errorMessage = context.getString(R.string.setup_error_session_failed)
            isPairingActive = false
            return@LaunchedEffect
        }
        authUrl = url
        qrBitmap = _generateQrBitmap(url)

        // Automatically dispatch intent to phone
        val opened = authRepo.openAuthOnPhone(url)
        isPhoneDispatched = opened
        if (!opened) {
            showQrCode = true
        }

        // Poll every 3 seconds
        while (isPairingActive) {
            delay(3000)
            when (val poll = authRepo.pollCompanionAuth(cleanId, sessionId)) {
                is PollResult.Success -> {
                    isPairingActive = false
                    if (storage.isDemoMode()) {
                        storage.clearDemoMode()
                    }
                    com.dimapp.android.homeywatch.tile.TileSlotManager.syncSlots(context)
                    onAuthSuccess()
                    break
                }
                is PollResult.Expired -> {
                    errorMessage = context.getString(R.string.setup_error_session_expired)
                    isPairingActive = false
                    break
                }
                is PollResult.Error -> {
                    errorMessage = context.getString(R.string.setup_error_auth_failed)
                    isPairingActive = false
                    break
                }
                is PollResult.NetworkError -> {
                    // Temporary glitch, continue polling
                }
                is PollResult.Pending -> {
                    // Waiting for user
                }
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isPairingActive) {
            // ==================================================================
            // STATE 1: Pairing in Progress UI (Phone-first or direct QR Code)
            // ==================================================================
            if (authUrl == null) {
                // Initial session handshake with Homey
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(32.dp)
                            .padding(vertical = 12.dp),
                        strokeWidth = 3.dp,
                        indicatorColor = HomeyBlue
                    )
                }
                item {
                    Text(
                        text = stringResource(id = R.string.setup_waiting_auth),
                        style = MaterialTheme.typography.caption2,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else if (!isPhoneDispatched) {
                // 1. STANDALONE / NO PHONE DETECTED: Show QR Code directly as the hero element
                item {
                    Text(
                        text = stringResource(id = R.string.setup_no_phone_title),
                        style = MaterialTheme.typography.title3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 4.dp)
                    )
                }

                if (qrBitmap != null) {
                    item {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(136.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(4.dp)
                        )
                    }
                }

                item {
                    CompactChip(
                        onClick = {
                            isPairingActive = false
                            authUrl = null
                            qrBitmap = null
                            showQrCode = false
                            discovery.startDiscovery(timeoutMs = 12000L)
                        },
                        label = {
                            Text(
                                text = stringResource(id = R.string.setup_btn_cancel),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                // 2. PHONE-FIRST FLOW: Intent successfully sent to companion phone
                item {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_phone),
                        contentDescription = null,
                        tint = HomeyBlue,
                        modifier = Modifier
                            .size(32.dp)
                            .padding(bottom = 2.dp)
                    )
                }

                item {
                    Text(
                        text = stringResource(id = R.string.setup_auth_sent_title),
                        style = MaterialTheme.typography.title2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(28.dp)
                            .padding(vertical = 4.dp),
                        strokeWidth = 3.dp,
                        indicatorColor = HomeyBlue
                    )
                }

                item {
                    Text(
                        text = stringResource(id = R.string.setup_auth_sent_desc),
                        style = MaterialTheme.typography.caption2,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                // Re-open on phone button (only relevant when phone is connected)
                item {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val opened = authRepo.openAuthOnPhone(authUrl!!)
                                isPhoneDispatched = opened
                            }
                        },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(vertical = 4.dp)
                    ) {
                        Text(text = stringResource(id = R.string.setup_btn_reopen_phone))
                    }
                }

                // Backup QR code toggle for phone flow
                if (qrBitmap != null) {
                    item {
                        CompactChip(
                            onClick = { showQrCode = !showQrCode },
                            label = {
                                Text(
                                    text = stringResource(
                                        id = if (showQrCode) R.string.setup_btn_hide_qr else R.string.setup_btn_show_qr
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    if (showQrCode) {
                        item {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .size(136.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                                    .padding(4.dp)
                            )
                        }
                    }
                }

                item {
                    CompactChip(
                        onClick = {
                            isPairingActive = false
                            authUrl = null
                            qrBitmap = null
                            showQrCode = false
                            discovery.startDiscovery(timeoutMs = 12000L)
                        },
                        label = {
                            Text(
                                text = stringResource(id = R.string.setup_btn_cancel),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else if (isManualEntryExpanded) {
            // ==================================================================
            // STATE 2: Dedicated Manual Entry Screen (Replaces Discovery UI)
            // ==================================================================
            item {
                Icon(
                    painter = painterResource(id = R.drawable.ic_edit),
                    contentDescription = null,
                    tint = HomeyBlue,
                    modifier = Modifier.size(32.dp)
                )
            }

            item {
                Text(
                    text = stringResource(id = R.string.setup_enter_homey_id),
                    style = MaterialTheme.typography.title2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage!!,
                        color = Color.Red,
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }

            // Editable text input field with direct typing, ADB support, and soft keyboard
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E22))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_edit),
                            contentDescription = null,
                            tint = if (homeyIdInput.isNotBlank()) HomeyBlue else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (homeyIdInput.isEmpty()) {
                                Text(
                                    text = stringResource(id = R.string.setup_enter_homey_id),
                                    style = MaterialTheme.typography.body2,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = homeyIdInput,
                                onValueChange = {
                                    homeyIdInput = it.trim()
                                    errorMessage = null
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.body2.copy(color = TextPrimary),
                                cursorBrush = SolidColor(HomeyBlue),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                    }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (homeyIdInput.isNotBlank()) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = stringResource(id = R.string.setup_clear_id),
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    homeyIdInput = ""
                                    errorMessage = null
                                }
                        )
                    }
                }
            }

            // Explanatory hint about keyboard / demo mode
            item {
                Text(
                    text = stringResource(id = R.string.setup_input_hint),
                    style = MaterialTheme.typography.caption2,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            // Connect button
            item {
                Button(
                    onClick = {
                        errorMessage = null
                        if (homeyIdInput.trim().equals("demo", ignoreCase = true)) {
                            storage.setDemoMode(true)
                            com.dimapp.android.homeywatch.tile.TileSlotManager.syncSlots(context)
                            onAuthSuccess()
                        } else if (homeyIdInput.isNotBlank()) {
                            isPairingActive = true
                        } else {
                            errorMessage = context.getString(R.string.setup_error_empty_id)
                        }
                    },
                    colors = ButtonDefaults.primaryButtonColors(backgroundColor = HomeyBlue),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(top = 4.dp)
                ) {
                    Text(text = stringResource(id = R.string.setup_btn_connect))
                }
            }

            // Cancel button to return to Wi-Fi discovery
            item {
                CompactChip(
                    onClick = {
                        isManualEntryExpanded = false
                        errorMessage = null
                    },
                    label = {
                        Text(
                            text = stringResource(id = R.string.setup_btn_cancel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            // ==================================================================
            // STATE 3: Wi-Fi Discovery Screen (Default)
            // ==================================================================
            item {
                Icon(
                    painter = painterResource(id = R.drawable.ic_watch),
                    contentDescription = null,
                    tint = HomeyBlue,
                    modifier = Modifier.size(32.dp)
                )
            }

            item {
                Text(
                    text = stringResource(id = if (onCancel != null) R.string.setup_title_add_hub else R.string.setup_title),
                    style = MaterialTheme.typography.title1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            item {
                Text(
                    text = stringResource(id = R.string.setup_instruction),
                    style = MaterialTheme.typography.body2,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage!!,
                        color = Color.Red,
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Discovered Homey Hubs
            if (discoveredHubs.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.setup_found_homey_title),
                        style = MaterialTheme.typography.caption1,
                        color = HomeyBlue,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }

                discoveredHubs.forEach { hub ->
                    item {
                        Chip(
                            onClick = {
                                homeyIdInput = hub.id
                                errorMessage = null
                                isPairingActive = true
                            },
                            label = {
                                Text(
                                    text = hub.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            secondaryLabel = {
                                Text(
                                    text = stringResource(id = R.string.setup_btn_connect_discovered, hub.name),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_tab_home),
                                    contentDescription = null,
                                    modifier = Modifier.size(ChipDefaults.IconSize)
                                )
                            },
                            colors = ChipDefaults.primaryChipColors(
                                backgroundColor = HomeyBlue
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            } else if (!isSearching) {
                item {
                    Text(
                        text = stringResource(id = R.string.setup_no_homey_found),
                        style = MaterialTheme.typography.caption2,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }

            // Action Row: [Rotating Refresh Button] + [Enter ID Manually]
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .padding(vertical = 4.dp)
                ) {
                    // Refresh button: rotates and is disabled while searching
                    Button(
                        onClick = { discovery.startDiscovery(timeoutMs = 12000L) },
                        enabled = !isSearching,
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_refresh),
                            contentDescription = stringResource(id = R.string.setup_btn_rescan),
                            tint = if (isSearching) HomeyBlue else TextPrimary,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(if (isSearching) refreshRotation else 0f)
                        )
                    }

                    Spacer(modifier = Modifier.size(8.dp))

                    CompactChip(
                        onClick = {
                            discovery.stopDiscovery()
                            isManualEntryExpanded = true
                            errorMessage = null
                        },
                        label = {
                            Text(
                                text = stringResource(id = R.string.setup_manual_entry_toggle),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }

            if (onCancel != null) {
                item {
                    CompactChip(
                        onClick = onCancel,
                        label = {
                            Text(
                                text = stringResource(id = R.string.setup_btn_cancel),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Generates a 64-character unguessable cryptographic session ID.
 *
 * @private
 */
private fun _generateSessionId(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Generates an in-memory QR code bitmap optimized for Wear OS displays.
 * Uses a minimal 1-module margin and integer scaling to maximize the QR code area
 * while eliminating unwanted whitespace.
 *
 * @private
 * @param content The text/URL content to encode.
 * @param targetSize The desired pixel size of the generated bitmap.
 * @return Crisp, edge-to-edge [Bitmap] of the QR code.
 */
private fun _generateQrBitmap(content: String, targetSize: Int = 360): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val matrixWidth = matrix.width
    val matrixHeight = matrix.height

    val scale = maxOf(1, targetSize / matrixWidth)
    val bitmapWidth = matrixWidth * scale
    val bitmapHeight = matrixHeight * scale
    val pixels = IntArray(bitmapWidth * bitmapHeight)

    val black = android.graphics.Color.BLACK
    val white = android.graphics.Color.WHITE

    for (y in 0 until matrixHeight) {
        val rowOffset = y * scale * bitmapWidth
        for (x in 0 until matrixWidth) {
            val color = if (matrix.get(x, y)) black else white
            val baseX = x * scale
            for (dy in 0 until scale) {
                val subRowOffset = rowOffset + dy * bitmapWidth
                for (dx in 0 until scale) {
                    pixels[subRowOffset + baseX + dx] = color
                }
            }
        }
    }

    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.RGB_565)
    bitmap.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
    return bitmap
}

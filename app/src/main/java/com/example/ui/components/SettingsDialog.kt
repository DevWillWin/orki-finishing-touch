package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.example.R
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.VoiceOptions
import com.example.ui.theme.AmberPro
import com.example.ui.theme.CharcoalTextPrimary
import com.example.ui.theme.ForestGreenDeep
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.IvoryBackground
import com.example.ui.theme.PaleSage
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SageGreen
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmBorderSubtle

@Composable
fun SettingsDialog(
    initialScript: String,
    initialUiLang: String,
    initialName: String,
    initialPersona: String,
    initialVoice: String,
    currentPlan: String = "Free",
    activePlayingText: String? = null,
    onPreviewVoice: (String) -> Unit,
    onClearCache: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onUpgradeClick: (() -> Unit)? = null,
    onSave: (script: String, uiLang: String, name: String, persona: String, voice: String) -> Unit
) {
    var script by remember { mutableStateOf(initialScript) }
    var uiLang by remember { mutableStateOf(initialUiLang) }
    var name by remember { mutableStateOf(initialName) }
    var persona by remember { mutableStateOf(initialPersona) }
    var voice by remember { mutableStateOf(initialVoice) }
    var lastPreviewedVoiceId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    var imageEngine by remember { mutableStateOf(prefs.imageEnginePreference) }
    var ttsSpeed by remember { mutableFloatStateOf(prefs.ttsSpeed) }
    var ttsEngineMode by remember { mutableStateOf(prefs.ttsEngineMode) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val voicePackManager = remember { com.example.data.audio.BodoVoicePackManager(context) }
    var isVoicePackInstalled by remember {
        mutableStateOf(
            voicePackManager.isVoicePackInstalled() || run {
                val rootModel = java.io.File("/app/applet/model.onnx")
                val rootTokens = java.io.File("/app/applet/tokens.txt")
                if (rootModel.exists() && rootTokens.exists()) {
                    voicePackManager.installFromLocalFiles(rootModel, rootTokens)
                } else false
            }
        )
    }
    var isDownloadingVoicePack by remember { mutableStateOf(false) }
    var voicePackProgress by remember { mutableFloatStateOf(0f) }
    var voicePackStatusMessage by remember { mutableStateOf("") }
    var cacheResetMessage by remember { mutableStateOf<String?>(null) }
    var showPerchanceDebug by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = IvoryBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, WarmBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp)
                .padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Fixed Header: Refined Title, Glyphs, and Close Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PureWhite)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(PaleSage)
                                .border(1.dp, SageGreen, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = com.example.R.drawable.ic_orki_inapp_logo_circle,
                                contentDescription = "Orki AI Logo",
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Settings",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = CharcoalTextPrimary
                            )
                            Text(
                                text = "Preferences, speech voice & personalization",
                                fontSize = 12.sp,
                                color = SlateTextSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(PaleSage)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close settings",
                            tint = SlateTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Header Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(WarmBorder)
                )

                // Independently Scrollable Settings Content
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // SECTION 1: LANGUAGE & SCRIPT
                    SettingsSection(title = "LANGUAGE & SCRIPT") {
                        // Interface Language
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "App Interface Language",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = CharcoalTextPrimary
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PaleSage)
                                    .border(1.dp, WarmBorder, RoundedCornerShape(10.dp))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SegmentedPill(
                                    label = "ब Devanagari",
                                    selected = uiLang == "deva",
                                    modifier = Modifier.weight(1f)
                                ) { uiLang = "deva" }

                                SegmentedPill(
                                    label = "R Roman",
                                    selected = uiLang == "roman",
                                    modifier = Modifier.weight(1f)
                                ) { uiLang = "roman" }

                                SegmentedPill(
                                    label = "EN English",
                                    selected = uiLang == "en",
                                    modifier = Modifier.weight(1f)
                                ) { uiLang = "en" }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // AI Output Response Script
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "AI Response Script",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = CharcoalTextPrimary
                                )
                                Text(
                                    text = if (script == "deva") "बर' फरायनो" else "Roman script",
                                    fontSize = 11.sp,
                                    color = SlateTextSecondary
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PaleSage)
                                    .border(1.dp, WarmBorder, RoundedCornerShape(10.dp))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SegmentedPill(
                                    label = "ब Devanagari",
                                    selected = script == "deva",
                                    modifier = Modifier.weight(1f)
                                ) { script = "deva" }

                                SegmentedPill(
                                    label = "R Roman Bodo",
                                    selected = script == "roman",
                                    modifier = Modifier.weight(1f)
                                ) { script = "roman" }
                            }
                        }
                    }

                    // SECTION 2: VOICE & SPEECH (Premium Redesigned Cards)
                    SettingsSection(title = "VOICE & SPEECH") {
                        Text(
                            text = "Select the voice model used for audio playback & Live Talk.",
                            fontSize = 12.sp,
                            color = SlateTextSecondary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            VoiceOptions.ALL.forEach { profile ->
                                val isSelected = profile.id == voice
                                val isCurrentlyPlaying = (lastPreviewedVoiceId == profile.id && activePlayingText != null)

                                // Model Identifier tag (e.g. BRX_F, BRX_M)
                                val modelTag = when (profile.id) {
                                    "female_mainao" -> "BRX_F"
                                    "male_birphung" -> "BRX_M"
                                    else -> profile.id.take(5).uppercase()
                                }

                                VoiceOptionCard(
                                    profileName = profile.name,
                                    modelId = modelTag,
                                    profileDesc = profile.description,
                                    samplePhrase = profile.samplePhrase,
                                    isSelected = isSelected,
                                    isPlaying = isCurrentlyPlaying,
                                    onSelect = { voice = profile.id },
                                    onPlayPreview = {
                                        lastPreviewedVoiceId = profile.id
                                        onPreviewVoice(profile.id)
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Speech Engine Mode Selector & Offline Voice Pack Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PureWhite, RoundedCornerShape(12.dp))
                                .border(1.dp, WarmBorder, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "Speech Engine Mode",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CharcoalTextPrimary
                            )
                            Text(
                                text = "Select offline on-device synthesis or server synthesis.",
                                fontSize = 11.sp,
                                color = SlateTextSecondary,
                                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "auto" to "Automatic",
                                    "offline" to "Offline Only",
                                    "online" to "Online (AWS)"
                                ).forEach { (modeVal, label) ->
                                    val isCurrent = ttsEngineMode == modeVal
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isCurrent) ForestGreenPrimary else PaleSage)
                                            .border(1.dp, if (isCurrent) ForestGreenPrimary else WarmBorder, RoundedCornerShape(8.dp))
                                            .clickable { ttsEngineMode = modeVal }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isCurrent) Color.White else CharcoalTextPrimary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Offline Voice Pack Status Subcard
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(PaleSage.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .border(1.dp, SageGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Neural Model: ai4bharat/vits_rasa_13",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ForestGreenDeep
                                            )
                                            Text(
                                                text = if (isVoicePackInstalled)
                                                    "Installed (117 MB) • 24 kHz Neural CPU"
                                                else
                                                    "Not Installed (Required for Offline Mode)",
                                                fontSize = 10.sp,
                                                color = if (isVoicePackInstalled) ForestGreenPrimary else SlateTextSecondary
                                            )
                                        }

                                        if (!isVoicePackInstalled) {
                                            if (isDownloadingVoicePack) {
                                                Text(
                                                    text = "${(voicePackProgress * 100).toInt()}%",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ForestGreenPrimary
                                                )
                                            } else {
                                                Button(
                                                    onClick = {
                                                        isDownloadingVoicePack = true
                                                        voicePackStatusMessage = "Connecting..."
                                                        coroutineScope.launch {
                                                            val res = voicePackManager.downloadVoicePack { prog, msg ->
                                                                voicePackProgress = prog
                                                                voicePackStatusMessage = msg
                                                            }
                                                            isDownloadingVoicePack = false
                                                            if (res.isSuccess) {
                                                                isVoicePackInstalled = true
                                                                voicePackStatusMessage = "Voice pack installed!"
                                                            } else {
                                                                voicePackStatusMessage = "Error: ${res.exceptionOrNull()?.message}"
                                                            }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Download", fontSize = 10.sp)
                                                }
                                            }
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .background(ForestGreenPrimary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "READY",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ForestGreenPrimary
                                                )
                                            }
                                        }
                                    }

                                    if (isDownloadingVoicePack) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        androidx.compose.material3.LinearProgressIndicator(
                                            progress = { voicePackProgress },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                            color = ForestGreenPrimary,
                                            trackColor = WarmBorder
                                        )
                                        Text(
                                            text = voicePackStatusMessage,
                                            fontSize = 9.sp,
                                            color = SlateTextSecondary,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    } else if (voicePackStatusMessage.isNotBlank()) {
                                        Text(
                                            text = voicePackStatusMessage,
                                            fontSize = 9.sp,
                                            color = ForestGreenDeep,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Speech Speed / Rate Slider
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PureWhite, RoundedCornerShape(12.dp))
                                .border(1.dp, WarmBorder, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = ForestGreenPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Speech Speed & Pacing",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = CharcoalTextPrimary
                                    )
                                }
                                val formattedSpeed = String.format(java.util.Locale.US, "%.2fx", ttsSpeed)
                                Text(
                                    text = formattedSpeed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ForestGreenPrimary
                                )
                            }

                            Text(
                                text = "Slightly slower speech (0.90x–0.95x) delivers clearer enunciation for Bodo tones.",
                                fontSize = 11.sp,
                                color = SlateTextSecondary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                            )

                            Slider(
                                value = ttsSpeed,
                                onValueChange = { ttsSpeed = it },
                                valueRange = 0.75f..1.25f,
                                steps = 9,
                                colors = SliderDefaults.colors(
                                    thumbColor = ForestGreenPrimary,
                                    activeTrackColor = ForestGreenPrimary,
                                    inactiveTrackColor = WarmBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Speed preset quick chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(0.85f to "0.85x", 0.95f to "0.95x ⭐", 1.0f to "1.0x", 1.15f to "1.15x").forEach { (speedVal, label) ->
                                    val isCurrent = kotlin.math.abs(ttsSpeed - speedVal) < 0.04f
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isCurrent) ForestGreenPrimary else PaleSage)
                                            .border(1.dp, if (isCurrent) ForestGreenPrimary else WarmBorder, RoundedCornerShape(8.dp))
                                            .clickable { ttsSpeed = speedVal }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isCurrent) Color.White else CharcoalTextPrimary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Pronunciation & Normalizer Info Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PaleSage.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .border(1.dp, SageGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = ForestGreenDeep,
                                    modifier = Modifier.size(16.dp).padding(top = 1.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Bodo Phonetic Pre-Processor Active",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ForestGreenDeep
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "• English loanwords (e.g. Spanish, phone, AI) are phonetically mapped to Devanagari (स्पेनिस, फोन, एआइ).\n• Emojis and markdown formatting are stripped before speech synthesis.\n• Numbers (0–9) are spoken naturally in Bodo (से, नै, थाम).",
                                        fontSize = 11.sp,
                                        color = CharcoalTextPrimary.copy(alpha = 0.85f),
                                        lineHeight = 15.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Cache reset button
                            OutlinedButton(
                                onClick = {
                                    onClearCache?.invoke()
                                    cacheResetMessage = "Voice cache cleared! Next replay will synthesize fresh audio."
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, ForestGreenPrimary.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ForestGreenPrimary),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Reset Speech Audio Cache",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (cacheResetMessage != null) {
                                Text(
                                    text = cacheResetMessage!!,
                                    fontSize = 10.sp,
                                    color = ForestGreenDeep,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }

                    // SECTION 3: PERSONALIZATION
                    SettingsSection(title = "PERSONALIZATION") {
                        // User Name
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "What should Orki call you?",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = CharcoalTextPrimary
                            )
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                placeholder = {
                                    Text("e.g., Rahul, Priya, Bodo Friend…", fontSize = 13.sp, color = SlateTextSecondary)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = SlateTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ForestGreenPrimary,
                                    unfocusedBorderColor = WarmBorder,
                                    focusedTextColor = CharcoalTextPrimary,
                                    unfocusedTextColor = CharcoalTextPrimary,
                                    cursorColor = ForestGreenPrimary,
                                    focusedContainerColor = PureWhite,
                                    unfocusedContainerColor = PureWhite
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Custom Instructions / Persona
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Custom Instructions",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = CharcoalTextPrimary
                            )
                            Text(
                                text = "Provide background or guidelines Orki should follow when answering.",
                                fontSize = 11.sp,
                                color = SlateTextSecondary
                            )
                            OutlinedTextField(
                                value = persona,
                                onValueChange = { persona = it },
                                placeholder = {
                                    Text(
                                        "e.g., Speak like a friendly tutor, keep answers short and simple…",
                                        fontSize = 12.sp,
                                        color = SlateTextSecondary
                                    )
                                },
                                maxLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ForestGreenPrimary,
                                    unfocusedBorderColor = WarmBorder,
                                    focusedTextColor = CharcoalTextPrimary,
                                    unfocusedTextColor = CharcoalTextPrimary,
                                    cursorColor = ForestGreenPrimary,
                                    focusedContainerColor = PureWhite,
                                    unfocusedContainerColor = PureWhite
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // SECTION 4: AI IMAGE GENERATION ENGINE
                    SettingsSection(title = "AI IMAGE GENERATION ENGINE") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Option 1: Orki Image Studio (Flux / Turbo)
                            val isFastStudioSelected = imageEngine == "auto" || imageEngine == "pollinations"
                            EngineOptionRow(
                                title = "Orki Image Studio (Flux / Turbo)",
                                badgeText = "PRIMARY • UNLIMITED FREE",
                                description = "Ultra-fast high-definition 1024x1024 generation with zero wait and no quota limits",
                                isSelected = isFastStudioSelected,
                                onClick = { imageEngine = "auto" }
                            )

                            // Option 2: Cloudflare Workers AI
                            val isCfSelected = imageEngine == "cloudflare"
                            EngineOptionRow(
                                title = "Cloudflare Workers AI",
                                badgeText = "CUSTOM WORKER",
                                description = "Fast SDXL/Flux via orki-img-gen worker endpoint",
                                isSelected = isCfSelected,
                                onClick = { imageEngine = "cloudflare" }
                            )

                            // Option 3: Perchance AI (free, browser-based). Failures fall back to Orki Image
                            // Studio visibly; the "Perchance WebView Debugger" below explains connection problems.
                            val isPerchanceSelected = imageEngine == "perchance"
                            EngineOptionRow(
                                title = "Perchance AI",
                                badgeText = "FREE • IN-APP BROWSER",
                                description = "Free unlimited generation through an in-app browser (can take up to a minute). If Perchance is unreachable it tells you why and falls back to Orki Image Studio",
                                isSelected = isPerchanceSelected,
                                onClick = { imageEngine = "perchance" }
                            )

                            // Multi-Engine Routing Status Pill
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PaleSage)
                                    .border(1.dp, SageGreen, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = ForestGreenPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Automatic High-Speed Routing", fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = CharcoalTextPrimary)
                                    Text("Instant 2–4s generation with automatic seamless failover protection", fontSize = 10.sp, color = SlateTextSecondary)
                                }
                            }

                            // Diagnostics: Perchance Live WebView Debug Screen
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PureWhite)
                                    .border(1.dp, WarmBorder, RoundedCornerShape(8.dp))
                                    .clickable { showPerchanceDebug = true }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = "Debug",
                                        tint = SlateTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Perchance WebView Debugger", fontWeight = FontWeight.Medium, fontSize = 11.sp, color = CharcoalTextPrimary)
                                        Text("Network checks (DNS / TCP / TLS / HTTP), test generation, live WebView and JS console", fontSize = 10.sp, color = SlateTextSecondary)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(PaleSage)
                                        .border(1.dp, SageGreen, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Open", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ForestGreenPrimary)
                                }
                            }
                        }
                    }

                    // SECTION 5: ACCOUNT & SUBSCRIPTION
                    SettingsSection(title = "ACCOUNT & SUBSCRIPTION") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PureWhite)
                                .border(1.dp, WarmBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Current Plan:",
                                        fontSize = 12.sp,
                                        color = SlateTextSecondary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (currentPlan == "Pro") AmberPro.copy(alpha = 0.15f)
                                                else PaleSage
                                            )
                                            .border(
                                                1.dp,
                                                if (currentPlan == "Pro") AmberPro.copy(alpha = 0.4f) else SageGreen,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = currentPlan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (currentPlan == "Pro") AmberPro else ForestGreenPrimary
                                        )
                                    }
                                }
                                Text(
                                    text = if (currentPlan == "Pro") "Okafwr 2.1 Deep Reasoning Active"
                                    else if (currentPlan == "Plus") "Extended Context & Voice Access"
                                    else "Standard daily conversation quota",
                                    fontSize = 11.sp,
                                    color = SlateTextSecondary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            if (currentPlan != "Pro" && onUpgradeClick != null) {
                                OutlinedButton(
                                    onClick = {
                                        onDismiss()
                                        onUpgradeClick()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ForestGreenPrimary),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, ForestGreenPrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(
                                        text = "Upgrade",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Fixed Bottom Action Bar: Cancel & Save Changes
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PureWhite)
                        .border(
                            width = 1.dp,
                            color = WarmBorder,
                            shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, WarmBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SlateTextSecondary),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                        ) {
                            Text(text = "Cancel", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                prefs.imageEnginePreference = imageEngine
                                prefs.ttsSpeed = ttsSpeed
                                prefs.ttsEngineMode = ttsEngineMode
                                onSave(script, uiLang, name.trim(), persona.trim(), voice)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ForestGreenPrimary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.4f)
                                .height(42.dp)
                        ) {
                            Text(
                                text = "Save Changes",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPerchanceDebug) {
        PerchanceDebugDialog(
            onDismiss = { showPerchanceDebug = false }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, WarmBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = ForestGreenPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            content()
        }
    }
}

/**
 * Premium Voice Selection Card:
 * Clear visual hierarchy, voice name, short description, model ID tag,
 * properly aligned radio indicator, subtle pale-sage background with thin forest-green border when selected,
 * and compact Preview button with speaker icon / wave animation.
 */
@Composable
private fun VoiceOptionCard(
    profileName: String,
    modelId: String,
    profileDesc: String,
    samplePhrase: String,
    isSelected: Boolean,
    isPlaying: Boolean,
    onSelect: () -> Unit,
    onPlayPreview: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) PaleSage else PureWhite)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) ForestGreenPrimary else WarmBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Radio Indicator
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isSelected) 5.dp else 1.5.dp,
                        color = if (isSelected) ForestGreenPrimary else SlateTextSecondary,
                        shape = CircleShape
                    )
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profileName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CharcoalTextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) SageGreen.copy(alpha = 0.5f) else PaleSage)
                            .border(
                                1.dp,
                                if (isSelected) ForestGreenPrimary.copy(alpha = 0.3f) else WarmBorder,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = modelId,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ForestGreenPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "•  $samplePhrase",
                        fontSize = 10.sp,
                        color = SlateTextSecondary,
                        fontWeight = FontWeight.Normal
                    )
                }
                Text(
                    text = profileDesc,
                    fontSize = 11.sp,
                    color = SlateTextSecondary,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }

        // Compact Preview Button (Speaker Icon & Accessible Touch Target)
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isPlaying) ForestGreenPrimary.copy(alpha = 0.12f) else PaleSage)
                .border(
                    1.dp,
                    if (isPlaying) ForestGreenPrimary else SageGreen,
                    RoundedCornerShape(8.dp)
                )
                .clickable(onClick = onPlayPreview)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isPlaying) {
                    AudioWaveAnimation()
                } else {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Preview voice",
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = if (isPlaying) "Playing" else "Preview",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ForestGreenPrimary
                )
            }
        }
    }
}

@Composable
private fun EngineOptionRow(
    title: String,
    badgeText: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) PaleSage else PureWhite)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) ForestGreenPrimary else WarmBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(
                    width = if (isSelected) 5.dp else 1.5.dp,
                    color = if (isSelected) ForestGreenPrimary else SlateTextSecondary,
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CharcoalTextPrimary)
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(PaleSage)
                        .border(1.dp, SageGreen, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(badgeText, fontSize = 9.sp, color = ForestGreenPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(description, fontSize = 11.sp, color = SlateTextSecondary)
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = ForestGreenPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun AudioWaveAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_wave")
    val h1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(240, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h3"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(14.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(h1.dp)
                .clip(CircleShape)
                .background(ForestGreenPrimary)
        )
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(h2.dp)
                .clip(CircleShape)
                .background(ForestGreenPrimary)
        )
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(h3.dp)
                .clip(CircleShape)
                .background(ForestGreenPrimary)
        )
    }
}

@Composable
private fun SegmentedPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PureWhite else Color.Transparent)
            .border(
                1.dp,
                if (selected) SageGreen else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) ForestGreenPrimary else SlateTextSecondary
        )
    }
}

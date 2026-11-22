package com.example.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.EdgeAnchor
import com.example.data.GestureZoneConfig
import com.example.data.OcrScriptMode
import com.example.data.SupportedMlKitDynamicScripts
import com.example.data.SupportedTargetLanguages
import com.example.data.SupportedTesseractOcrPacks
import com.example.engine.ModelPackStatus
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.InterFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SpaceGroteskFamily

@Composable
fun DashboardScreen(
    config: GestureZoneConfig,
    isAccessibilityEnabled: Boolean,
    packStatuses: Map<String, ModelPackStatus>,
    packErrors: Map<String, String>,
    mlKitOcrStatuses: Map<String, ModelPackStatus>,
    mlKitOcrErrors: Map<String, String>,
    tessOcrStatuses: Map<String, ModelPackStatus>,
    tessOcrErrors: Map<String, String>,
    onOpenZoneEditor: () -> Unit,
    onOpenSandbox: () -> Unit,
    onToggleTriggerEnabled: (Boolean) -> Unit,
    onToggleHaptic: (Boolean) -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onSelectScriptMode: (OcrScriptMode) -> Unit,
    onDownloadLanguagePack: (String) -> Unit,
    onDeleteLanguagePack: (String) -> Unit,
    onDownloadMlKitOcrScript: (String) -> Unit,
    onPreloadAllMlKitOcrScripts: () -> Unit,
    onDownloadTesseractOcrPack: (String) -> Unit,
    onDeleteTesseractOcrPack: (String) -> Unit
) {
    val context = LocalContext.current
    var showPackManager by remember { mutableStateOf(false) }
    var showOcrPackManager by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF111119)),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(24.dp)) {
                                drawArc(
                                    color = Color.White,
                                    startAngle = -35f,
                                    sweepAngle = 295f,
                                    useCenter = false,
                                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                )
                                drawCircle(
                                    color = ElectricCyan,
                                    radius = 3.dp.toPx(),
                                    center = center
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "QuickCircle",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Offline OCR · Translate · Lens",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    StatusChip(isActive = isAccessibilityEnabled && config.triggerEnabled)
                }

                // Card 1: Instant Sandbox Hero (Zero setup required to test OCR/Translate/Lens)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(152.dp)
                        ) {
                            CompactMultiScriptPreview(modifier = Modifier.fillMaxSize())
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color(0x22000000),
                                                Color(0xDD0B0B0E)
                                            )
                                        )
                                    )
                            )
                            // Decorative minimal white circle overlay indicator
                            Canvas(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(14.dp)
                                    .size(44.dp)
                            ) {
                                drawCircle(
                                    color = Color.White,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "INSTANT TEST ARENA",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.5.sp,
                                    color = ElectricCyan
                                )
                                Text(
                                    text = "Circle text or objects right now",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                    color = Color.White
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onOpenSandbox,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("launch_sandbox_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CropFree,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Open Test Canvas",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Card 2: Customizable Gesture Area (Drag & Resize Editor)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniPhoneZonePreview(
                            config = config,
                            modifier = Modifier.size(width = 58.dp, height = 104.dp)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.TouchApp,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Gesture Launch Area",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = config.summaryLine(),
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )

                            Text(
                                text = "Drag, resize, and pick tap or swipe sensitivity.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Button(
                                onClick = onOpenZoneEditor,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("open_zone_editor_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Customize Zone",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Card 3: System-Wide Accessibility Trigger
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isAccessibilityEnabled) Icons.Rounded.CheckCircle else Icons.Rounded.AccessibilityNew,
                                    contentDescription = null,
                                    tint = if (isAccessibilityEnabled) EmeraldActive else AmberWarn,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "System-Wide Trigger",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isAccessibilityEnabled) {
                                            "Accessibility service connected"
                                        } else {
                                            "Enable in Accessibility Settings to launch over any app"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (isAccessibilityEnabled) {
                                Switch(
                                    checked = config.triggerEnabled,
                                    onCheckedChange = onToggleTriggerEnabled,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = ElectricCyan
                                    ),
                                    modifier = Modifier.testTag("system_trigger_switch")
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("open_accessibility_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isAccessibilityEnabled) {
                                    "Manage Accessibility Service"
                                } else {
                                    "Enable Accessibility Service"
                                },
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Text(
                            text = "Tip: You can also add the \"Circle Search\" Quick Settings tile from your notification shade. No network calls run until you tap Web Search, Google Lens, or download an offline language pack.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp
                        )
                    }
                }

                // Card 4: OCR & Translation Preferences
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val downloadedCount = SupportedTargetLanguages.count {
                            packStatuses[it.code] == ModelPackStatus.DOWNLOADED
                        }
                        val downloadingCount = packStatuses.values.count {
                            it == ModelPackStatus.DOWNLOADING
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "OCR & Translation Defaults",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Engine: Google ML Kit Offline (~30 MB/pack)",
                                        fontFamily = InterFamily,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (downloadingCount > 0) {
                                    ElectricCyan.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (downloadingCount > 0) ElectricCyan else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (downloadingCount > 0) {
                                        CircularProgressIndicator(
                                            color = ElectricCyan,
                                            strokeWidth = 1.8.dp,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                    Text(
                                        text = if (downloadingCount > 0) {
                                            "Downloading ($downloadingCount)…"
                                        } else {
                                            "$downloadedCount/${SupportedTargetLanguages.size} packs"
                                        },
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.5.sp,
                                        color = if (downloadingCount > 0) ElectricCyan else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "DEFAULT TRANSLATE TO",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SupportedTargetLanguages.forEach { lang ->
                                    val selected = lang.code == config.targetLanguageCode
                                    val packStatus = packStatuses[lang.code] ?: ModelPackStatus.NOT_DOWNLOADED
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .clickable { onSelectTargetLanguage(lang.code) }
                                            .testTag("pref_lang_${lang.code}")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            when (packStatus) {
                                                ModelPackStatus.DOWNLOADING -> {
                                                    CircularProgressIndicator(
                                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else ElectricCyan,
                                                        strokeWidth = 1.8.dp,
                                                        modifier = Modifier.size(10.dp)
                                                    )
                                                }
                                                ModelPackStatus.DOWNLOADED -> {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(7.dp)
                                                            .clip(CircleShape)
                                                            .background(EmeraldActive)
                                                    )
                                                }
                                                ModelPackStatus.ERROR -> {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(7.dp)
                                                            .clip(CircleShape)
                                                            .background(CoralError)
                                                    )
                                                }
                                                ModelPackStatus.NOT_DOWNLOADED -> Unit
                                            }
                                            Text(
                                                text = "${lang.shortCode} · ${lang.label}",
                                                fontFamily = InterFamily,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                fontSize = 12.5.sp,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Selected Target Pack Status & All-Pack Manager
                        val selectedLang = SupportedTargetLanguages.firstOrNull {
                            it.code == config.targetLanguageCode
                        } ?: SupportedTargetLanguages.first()
                        val selectedStatus = packStatuses[selectedLang.code] ?: ModelPackStatus.NOT_DOWNLOADED
                        val selectedError = packErrors[selectedLang.code]

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("offline_pack_status_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${selectedLang.label} (${selectedLang.shortCode}) Offline Pack",
                                            fontFamily = SpaceGroteskFamily,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = when (selectedStatus) {
                                                ModelPackStatus.DOWNLOADED -> "Ready offline via Google ML Kit"
                                                ModelPackStatus.DOWNLOADING -> "Downloading via Google Play Services…"
                                                ModelPackStatus.ERROR -> selectedError ?: "Download failed. Check network / Play Services."
                                                ModelPackStatus.NOT_DOWNLOADED -> "Not cached yet · Downloads on first use or tap Download"
                                            },
                                            fontFamily = InterFamily,
                                            fontSize = 11.5.sp,
                                            color = when (selectedStatus) {
                                                ModelPackStatus.DOWNLOADED -> EmeraldActive
                                                ModelPackStatus.DOWNLOADING -> ElectricCyan
                                                ModelPackStatus.ERROR -> CoralError
                                                ModelPackStatus.NOT_DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }

                                    when (selectedStatus) {
                                        ModelPackStatus.DOWNLOADED -> {
                                            OutlinedButton(
                                                onClick = { onDeleteLanguagePack(selectedLang.code) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag("delete_selected_pack_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteOutline,
                                                    contentDescription = "Remove offline pack",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text("Remove", fontSize = 12.sp)
                                            }
                                        }
                                        ModelPackStatus.DOWNLOADING -> {
                                            CircularProgressIndicator(
                                                color = ElectricCyan,
                                                strokeWidth = 2.2.dp,
                                                modifier = Modifier
                                                    .padding(horizontal = 12.dp)
                                                    .size(20.dp)
                                            )
                                        }
                                        ModelPackStatus.NOT_DOWNLOADED, ModelPackStatus.ERROR -> {
                                            Button(
                                                onClick = { onDownloadLanguagePack(selectedLang.code) },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag("download_selected_pack_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CloudDownload,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = if (selectedStatus == ModelPackStatus.ERROR) "Retry" else "Download",
                                                    fontSize = 12.sp,
                                                    fontFamily = SpaceGroteskFamily
                                                )
                                            }
                                        }
                                    }
                                }

                                if (selectedStatus == ModelPackStatus.DOWNLOADING) {
                                    LinearProgressIndicator(
                                        color = ElectricCyan,
                                        trackColor = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Text(
                                    text = "Offline translation needs both source & target ML Kit packs cached. If a pack is still downloading or fails, the overlay offers 1-tap Google Translate fallback (app or web).",
                                    fontFamily = InterFamily,
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { showPackManager = !showPackManager }
                                        .padding(horizontal = 4.dp, vertical = 6.dp)
                                        .testTag("toggle_pack_manager_button"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (showPackManager) {
                                            "Hide All 12 Offline Packs"
                                        } else {
                                            "Manage All 12 Offline Packs ($downloadedCount/${SupportedTargetLanguages.size} Ready)"
                                        },
                                        fontFamily = SpaceGroteskFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.5.sp,
                                        color = ElectricCyan
                                    )
                                    Icon(
                                        imageVector = if (showPackManager) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = null,
                                        tint = ElectricCyan
                                    )
                                }

                                AnimatedVisibility(visible = showPackManager) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        SupportedTargetLanguages.forEach { lang ->
                                            val st = packStatuses[lang.code] ?: ModelPackStatus.NOT_DOWNLOADED
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "${lang.label} (${lang.shortCode})",
                                                        fontFamily = InterFamily,
                                                        fontWeight = FontWeight.Medium,
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = when (st) {
                                                            ModelPackStatus.DOWNLOADED -> "Downloaded offline"
                                                            ModelPackStatus.DOWNLOADING -> "Downloading…"
                                                            ModelPackStatus.ERROR -> packErrors[lang.code] ?: "Failed"
                                                            ModelPackStatus.NOT_DOWNLOADED -> "Not downloaded"
                                                        },
                                                        fontFamily = JetBrainsMonoFamily,
                                                        fontSize = 10.5.sp,
                                                        color = when (st) {
                                                            ModelPackStatus.DOWNLOADED -> EmeraldActive
                                                            ModelPackStatus.DOWNLOADING -> ElectricCyan
                                                            ModelPackStatus.ERROR -> CoralError
                                                            ModelPackStatus.NOT_DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                                                        },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                when (st) {
                                                    ModelPackStatus.DOWNLOADED -> {
                                                        OutlinedButton(
                                                            onClick = { onDeleteLanguagePack(lang.code) },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                            modifier = Modifier
                                                                .heightIn(min = 48.dp)
                                                                .testTag("pack_delete_${lang.code}")
                                                        ) {
                                                            Text("Remove", fontSize = 11.5.sp)
                                                        }
                                                    }
                                                    ModelPackStatus.DOWNLOADING -> {
                                                        CircularProgressIndicator(
                                                            color = ElectricCyan,
                                                            strokeWidth = 2.dp,
                                                            modifier = Modifier
                                                                .padding(end = 10.dp)
                                                                .size(18.dp)
                                                        )
                                                    }
                                                    ModelPackStatus.NOT_DOWNLOADED, ModelPackStatus.ERROR -> {
                                                        OutlinedButton(
                                                            onClick = { onDownloadLanguagePack(lang.code) },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                            modifier = Modifier
                                                                .heightIn(min = 48.dp)
                                                                .testTag("pack_download_${lang.code}")
                                                        ) {
                                                            Text(
                                                                text = if (st == ModelPackStatus.ERROR) "Retry" else "Get",
                                                                fontSize = 11.5.sp
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "ON-DEVICE OCR ENGINE (MOE)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OcrScriptMode.entries.forEach { mode ->
                                    val selected = mode == config.scriptMode
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .clickable { onSelectScriptMode(mode) }
                                            .testTag("script_mode_${mode.name}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = mode.label,
                                                fontFamily = SpaceGroteskFamily,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                fontSize = 12.5.sp,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            val readyMlKitCount = SupportedMlKitDynamicScripts.count {
                                mlKitOcrStatuses[it.code] == ModelPackStatus.DOWNLOADED
                            }
                            val readyTessCount = SupportedTesseractOcrPacks.count {
                                tessOcrStatuses[it.code] == ModelPackStatus.DOWNLOADED
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { showOcrPackManager = !showOcrPackManager }
                                            .padding(horizontal = 4.dp, vertical = 4.dp)
                                            .testTag("toggle_ocr_pack_manager_button"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Dynamic OCR Script Experts",
                                                fontFamily = SpaceGroteskFamily,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "$readyMlKitCount/${SupportedMlKitDynamicScripts.size} ML Kit · $readyTessCount Arabic/Cyrillic/Extra active",
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 10.5.sp,
                                                color = ElectricCyan
                                            )
                                        }
                                        Icon(
                                            imageVector = if (showOcrPackManager) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                            contentDescription = null,
                                            tint = ElectricCyan
                                        )
                                    }

                                    AnimatedVisibility(visible = showOcrPackManager) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "ML KIT PLAY SERVICES EXPERTS (0 MB IN APK)",
                                                    fontFamily = JetBrainsMonoFamily,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedButton(
                                                    onClick = onPreloadAllMlKitOcrScripts,
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("preload_mlkit_ocr_button")
                                                ) {
                                                    Text("Preload All", fontSize = 11.5.sp)
                                                }
                                            }

                                            SupportedMlKitDynamicScripts.forEach { script ->
                                                val st = mlKitOcrStatuses[script.code] ?: ModelPackStatus.NOT_DOWNLOADED
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 48.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(MaterialTheme.colorScheme.surface)
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = script.label,
                                                            fontFamily = InterFamily,
                                                            fontWeight = FontWeight.Medium,
                                                            fontSize = 12.5.sp,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = when (st) {
                                                                ModelPackStatus.DOWNLOADED -> "Ready via Play Services"
                                                                ModelPackStatus.DOWNLOADING -> "Installing module…"
                                                                ModelPackStatus.ERROR -> mlKitOcrErrors[script.code] ?: "Play Services install error"
                                                                ModelPackStatus.NOT_DOWNLOADED -> "On-demand (${script.sizeHint})"
                                                            },
                                                            fontFamily = JetBrainsMonoFamily,
                                                            fontSize = 10.sp,
                                                            color = when (st) {
                                                                ModelPackStatus.DOWNLOADED -> EmeraldActive
                                                                ModelPackStatus.DOWNLOADING -> ElectricCyan
                                                                ModelPackStatus.ERROR -> CoralError
                                                                ModelPackStatus.NOT_DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                                                            },
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    when (st) {
                                                        ModelPackStatus.DOWNLOADED -> {
                                                            Icon(
                                                                imageVector = Icons.Rounded.CheckCircle,
                                                                contentDescription = "Ready",
                                                                tint = EmeraldActive,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                        ModelPackStatus.DOWNLOADING -> {
                                                            CircularProgressIndicator(
                                                                color = ElectricCyan,
                                                                strokeWidth = 2.dp,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                        ModelPackStatus.NOT_DOWNLOADED, ModelPackStatus.ERROR -> {
                                                            OutlinedButton(
                                                                onClick = { onDownloadMlKitOcrScript(script.code) },
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                                modifier = Modifier
                                                                    .heightIn(min = 48.dp)
                                                                    .testTag("mlkit_ocr_get_${script.code}")
                                                            ) {
                                                                Text(
                                                                    text = if (st == ModelPackStatus.ERROR) "Retry" else "Get",
                                                                    fontSize = 11.5.sp
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            Text(
                                                text = "ARABIC · CYRILLIC · EXTRA SCRIPTS (TESSERACT)",
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )

                                            SupportedTesseractOcrPacks.forEach { pack ->
                                                val st = tessOcrStatuses[pack.code] ?: ModelPackStatus.NOT_DOWNLOADED
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 48.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(MaterialTheme.colorScheme.surface)
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "${pack.label} (${pack.shortCode})",
                                                            fontFamily = InterFamily,
                                                            fontWeight = FontWeight.Medium,
                                                            fontSize = 12.5.sp,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = when (st) {
                                                                ModelPackStatus.DOWNLOADED -> "Active in Auto MoE (${pack.sizeHint})"
                                                                ModelPackStatus.DOWNLOADING -> "Downloading ${pack.sizeHint}…"
                                                                ModelPackStatus.ERROR -> tessOcrErrors[pack.code] ?: "Download failed"
                                                                ModelPackStatus.NOT_DOWNLOADED -> "Download (${pack.sizeHint})"
                                                            },
                                                            fontFamily = JetBrainsMonoFamily,
                                                            fontSize = 10.sp,
                                                            color = when (st) {
                                                                ModelPackStatus.DOWNLOADED -> EmeraldActive
                                                                ModelPackStatus.DOWNLOADING -> ElectricCyan
                                                                ModelPackStatus.ERROR -> CoralError
                                                                ModelPackStatus.NOT_DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                                                            },
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    when (st) {
                                                        ModelPackStatus.DOWNLOADED -> {
                                                            OutlinedButton(
                                                                onClick = { onDeleteTesseractOcrPack(pack.code) },
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                                modifier = Modifier
                                                                    .heightIn(min = 48.dp)
                                                                    .testTag("tess_ocr_delete_${pack.code}")
                                                            ) {
                                                                Text("Remove", fontSize = 11.5.sp)
                                                            }
                                                        }
                                                        ModelPackStatus.DOWNLOADING -> {
                                                            CircularProgressIndicator(
                                                                color = ElectricCyan,
                                                                strokeWidth = 2.dp,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                        ModelPackStatus.NOT_DOWNLOADED, ModelPackStatus.ERROR -> {
                                                            OutlinedButton(
                                                                onClick = { onDownloadTesseractOcrPack(pack.code) },
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                                modifier = Modifier
                                                                    .heightIn(min = 48.dp)
                                                                    .testTag("tess_ocr_get_${pack.code}")
                                                            ) {
                                                                Text(
                                                                    text = if (st == ModelPackStatus.ERROR) "Retry" else "Get",
                                                                    fontSize = 11.5.sp
                                                                )
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Haptic Tick on Trigger",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Subtle pulse when gesture zone activates",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.hapticFeedback,
                                onCheckedChange = onToggleHaptic,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = ElectricCyan
                                ),
                                modifier = Modifier.testTag("haptic_switch")
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(isActive: Boolean) {
    val dotColor = if (isActive) EmeraldActive else AmberWarn
    val label = if (isActive) "ACTIVE" else "IN-APP ONLY"
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun MiniPhoneZonePreview(
    config: GestureZoneConfig,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0E0F14),
        border = BorderStroke(1.5.dp, Color(0xFF343642)),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / 390f
            val scaleY = size.height / 844f
            val w = (config.widthDp * scaleX).coerceAtLeast(6.dp.toPx())
            val h = (config.heightDp * scaleY).coerceAtLeast(6.dp.toPx())
            val insetX = config.edgeInsetDp * scaleX
            val insetY = config.edgeInsetDp * scaleY

            val (left, top) = when (config.anchor) {
                EdgeAnchor.LEFT_EDGE -> insetX to ((size.height - h) * config.offsetYFraction)
                EdgeAnchor.RIGHT_EDGE -> (size.width - w - insetX) to ((size.height - h) * config.offsetYFraction)
                EdgeAnchor.BOTTOM_BAR -> ((size.width - w) * config.offsetYFraction) to (size.height - h - insetY)
                EdgeAnchor.FREE -> ((size.width - w) * config.offsetXFraction) to ((size.height - h) * config.offsetYFraction)
            }

            drawRoundRect(
                color = ElectricCyan.copy(alpha = 0.32f),
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

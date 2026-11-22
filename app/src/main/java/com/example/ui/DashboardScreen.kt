package com.example.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Minimal Brand Header + Status Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val brandAccent = MaterialTheme.colorScheme.primary
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF10131C)),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(20.dp)) {
                                drawArc(
                                    color = Color.White,
                                    startAngle = -35f,
                                    sweepAngle = 295f,
                                    useCenter = false,
                                    style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                                )
                                drawCircle(
                                    color = brandAccent,
                                    radius = 2.6.dp.toPx(),
                                    center = center
                                )
                            }
                        }
                        Text(
                            text = "QuickCircle",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    StatusChip(
                        isActive = isAccessibilityEnabled && config.triggerEnabled,
                        onClick = {
                            if (!isAccessibilityEnabled) {
                                openAccessibilitySettings()
                            }
                        }
                    )
                }

                // 2. Primary Trigger & Test Deck
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(
                                            (if (isAccessibilityEnabled) EmeraldActive else AmberWarn).copy(alpha = 0.14f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isAccessibilityEnabled) Icons.Rounded.CheckCircle else Icons.Rounded.AccessibilityNew,
                                        contentDescription = null,
                                        tint = if (isAccessibilityEnabled) EmeraldActive else AmberWarn,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = when {
                                            !isAccessibilityEnabled -> "Enable system trigger"
                                            config.triggerEnabled -> "Edge trigger"
                                            else -> "Edge trigger paused"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!isAccessibilityEnabled) {
                                        Text(
                                            text = "Required to circle any app",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            if (isAccessibilityEnabled) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { openAccessibilitySettings() },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("open_accessibility_settings_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Settings,
                                            contentDescription = "Accessibility settings",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    Switch(
                                        checked = config.triggerEnabled,
                                        onCheckedChange = onToggleTriggerEnabled,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier.testTag("system_trigger_switch")
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { openAccessibilitySettings() },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("open_accessibility_settings_button")
                                ) {
                                    Text(
                                        text = "Enable",
                                        fontFamily = SpaceGroteskFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 58.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onOpenZoneEditor() }
                                .testTag("open_zone_editor_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Trigger Zone",
                                        fontFamily = SpaceGroteskFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = config.summaryLine(),
                                        fontFamily = InterFamily,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = "Configure trigger zone",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Button(
                            onClick = onOpenSandbox,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
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
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.5.sp
                            )
                        }
                    }
                }

                // 3. Translation Target (flat inline pack status & drawer)
                val downloadedCount = SupportedTargetLanguages.count {
                    packStatuses[it.code] == ModelPackStatus.DOWNLOADED
                }
                val downloadingCount = packStatuses.values.count {
                    it == ModelPackStatus.DOWNLOADING
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(17.dp)
                                )
                                Text(
                                    text = "Translate Target",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (downloadingCount > 0) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 1.8.dp,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = if (downloadingCount > 0) {
                                        "Downloading $downloadingCount…"
                                    } else {
                                        "$downloadedCount/${SupportedTargetLanguages.size}"
                                    },
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.5.sp,
                                    color = if (downloadingCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

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
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSelectTargetLanguage(lang.code) }
                                        .testTag("pref_lang_${lang.code}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        PackDot(
                                            status = packStatus,
                                            spinnerColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "${lang.shortCode} · ${lang.label}",
                                            fontFamily = InterFamily,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 13.sp,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        val selectedLang = SupportedTargetLanguages.firstOrNull {
                            it.code == config.targetLanguageCode
                        } ?: SupportedTargetLanguages.first()
                        val selectedStatus = packStatuses[selectedLang.code] ?: ModelPackStatus.NOT_DOWNLOADED
                        val selectedError = packErrors[selectedLang.code]

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("offline_pack_status_card"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${selectedLang.label} pack",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = when (selectedStatus) {
                                        ModelPackStatus.DOWNLOADED -> "Ready offline (~30 MB)"
                                        ModelPackStatus.DOWNLOADING -> "Downloading…"
                                        ModelPackStatus.ERROR -> selectedError ?: "Download failed"
                                        ModelPackStatus.NOT_DOWNLOADED -> "Not downloaded"
                                    },
                                    fontFamily = InterFamily,
                                    fontSize = 12.sp,
                                    color = when (selectedStatus) {
                                        ModelPackStatus.DOWNLOADED -> EmeraldActive
                                        ModelPackStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                                        ModelPackStatus.ERROR -> CoralError
                                        ModelPackStatus.NOT_DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            when (selectedStatus) {
                                ModelPackStatus.DOWNLOADED -> {
                                    TextButton(
                                        onClick = { onDeleteLanguagePack(selectedLang.code) },
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
                                        Text("Remove", fontSize = 12.5.sp)
                                    }
                                }
                                ModelPackStatus.DOWNLOADING -> {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp)
                                            .size(20.dp)
                                    )
                                }
                                ModelPackStatus.NOT_DOWNLOADED, ModelPackStatus.ERROR -> {
                                    Button(
                                        onClick = { onDownloadLanguagePack(selectedLang.code) },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
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
                                            text = if (selectedStatus == ModelPackStatus.ERROR) "Retry" else "Get",
                                            fontSize = 12.5.sp,
                                            fontFamily = SpaceGroteskFamily
                                        )
                                    }
                                }
                            }
                        }

                        if (selectedStatus == ModelPackStatus.DOWNLOADING) {
                            LinearProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showPackManager = !showPackManager }
                                .testTag("toggle_pack_manager_button"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showPackManager) "Hide language packs" else "All language packs ($downloadedCount/${SupportedTargetLanguages.size})",
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (showPackManager) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(visible = showPackManager) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SupportedTargetLanguages.forEach { lang ->
                                    val st = packStatuses[lang.code] ?: ModelPackStatus.NOT_DOWNLOADED
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                                                    ModelPackStatus.DOWNLOADED -> "Ready offline"
                                                    ModelPackStatus.DOWNLOADING -> "Downloading…"
                                                    ModelPackStatus.ERROR -> packErrors[lang.code] ?: "Failed"
                                                    ModelPackStatus.NOT_DOWNLOADED -> "Not downloaded"
                                                },
                                                fontFamily = InterFamily,
                                                fontSize = 11.5.sp,
                                                color = when (st) {
                                                    ModelPackStatus.DOWNLOADED -> EmeraldActive
                                                    ModelPackStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                                                    ModelPackStatus.ERROR -> CoralError
                                                    ModelPackStatus.NOT_DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        when (st) {
                                            ModelPackStatus.DOWNLOADED -> {
                                                TextButton(
                                                    onClick = { onDeleteLanguagePack(lang.code) },
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("pack_delete_${lang.code}")
                                                ) {
                                                    Text("Remove", fontSize = 12.sp)
                                                }
                                            }
                                            ModelPackStatus.DOWNLOADING -> {
                                                CircularProgressIndicator(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier
                                                        .padding(end = 10.dp)
                                                        .size(18.dp)
                                                )
                                            }
                                            ModelPackStatus.NOT_DOWNLOADED, ModelPackStatus.ERROR -> {
                                                OutlinedButton(
                                                    onClick = { onDownloadLanguagePack(lang.code) },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("pack_download_${lang.code}")
                                                ) {
                                                    Text(
                                                        text = if (st == ModelPackStatus.ERROR) "Retry" else "Get",
                                                        fontSize = 12.sp
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

                // 4. OCR Scripts & Haptics (flattened)
                val readyMlKitCount = SupportedMlKitDynamicScripts.count {
                    mlKitOcrStatuses[it.code] == ModelPackStatus.DOWNLOADED
                }
                val readyTessCount = SupportedTesseractOcrPacks.count {
                    tessOcrStatuses[it.code] == ModelPackStatus.DOWNLOADED
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "OCR Engine",
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
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
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSelectScriptMode(mode) }
                                        .testTag("script_mode_${mode.name}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = mode.label,
                                            fontFamily = SpaceGroteskFamily,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 13.sp,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showOcrPackManager = !showOcrPackManager }
                                .testTag("toggle_ocr_pack_manager_button"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Script Experts",
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "$readyMlKitCount/${SupportedMlKitDynamicScripts.size} · +$readyTessCount",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (showOcrPackManager) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        AnimatedVisibility(visible = showOcrPackManager) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Play Services OCR",
                                        fontFamily = SpaceGroteskFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = onPreloadAllMlKitOcrScripts,
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("preload_mlkit_ocr_button")
                                    ) {
                                        Text("Preload All", fontSize = 12.sp)
                                    }
                                }

                                SupportedMlKitDynamicScripts.forEach { script ->
                                    val st = mlKitOcrStatuses[script.code] ?: ModelPackStatus.NOT_DOWNLOADED
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = script.label,
                                                fontFamily = InterFamily,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = when (st) {
                                                    ModelPackStatus.DOWNLOADED -> "Ready via Play Services"
                                                    ModelPackStatus.DOWNLOADING -> "Installing module…"
                                                    ModelPackStatus.ERROR -> mlKitOcrErrors[script.code] ?: "Play Services error"
                                                    ModelPackStatus.NOT_DOWNLOADED -> "On-demand (${script.sizeHint})"
                                                },
                                                fontFamily = InterFamily,
                                                fontSize = 11.5.sp,
                                                color = when (st) {
                                                    ModelPackStatus.DOWNLOADED -> EmeraldActive
                                                    ModelPackStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
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
                                                    color = MaterialTheme.colorScheme.primary,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            ModelPackStatus.NOT_DOWNLOADED, ModelPackStatus.ERROR -> {
                                                OutlinedButton(
                                                    onClick = { onDownloadMlKitOcrScript(script.code) },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("mlkit_ocr_get_${script.code}")
                                                ) {
                                                    Text(
                                                        text = if (st == ModelPackStatus.ERROR) "Retry" else "Get",
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "Extra Scripts (Tesseract)",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
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
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${pack.label} (${pack.shortCode})",
                                                fontFamily = InterFamily,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = when (st) {
                                                    ModelPackStatus.DOWNLOADED -> "Ready (${pack.sizeHint})"
                                                    ModelPackStatus.DOWNLOADING -> "Downloading ${pack.sizeHint}…"
                                                    ModelPackStatus.ERROR -> tessOcrErrors[pack.code] ?: "Download failed"
                                                    ModelPackStatus.NOT_DOWNLOADED -> "Download (${pack.sizeHint})"
                                                },
                                                fontFamily = InterFamily,
                                                fontSize = 11.5.sp,
                                                color = when (st) {
                                                    ModelPackStatus.DOWNLOADED -> EmeraldActive
                                                    ModelPackStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                                                    ModelPackStatus.ERROR -> CoralError
                                                    ModelPackStatus.NOT_DOWNLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        when (st) {
                                            ModelPackStatus.DOWNLOADED -> {
                                                TextButton(
                                                    onClick = { onDeleteTesseractOcrPack(pack.code) },
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("tess_ocr_delete_${pack.code}")
                                                ) {
                                                    Text("Remove", fontSize = 12.sp)
                                                }
                                            }
                                            ModelPackStatus.DOWNLOADING -> {
                                                CircularProgressIndicator(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            ModelPackStatus.NOT_DOWNLOADED, ModelPackStatus.ERROR -> {
                                                OutlinedButton(
                                                    onClick = { onDownloadTesseractOcrPack(pack.code) },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("tess_ocr_get_${pack.code}")
                                                ) {
                                                    Text(
                                                        text = if (st == ModelPackStatus.ERROR) "Retry" else "Get",
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onToggleHaptic(!config.hapticFeedback) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Haptic feedback",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = config.hapticFeedback,
                                onCheckedChange = onToggleHaptic,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.testTag("haptic_switch")
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PackDot(
    status: ModelPackStatus,
    spinnerColor: Color
) {
    when (status) {
        ModelPackStatus.DOWNLOADING -> {
            CircularProgressIndicator(
                color = spinnerColor,
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
}

@Composable
private fun StatusChip(
    isActive: Boolean,
    onClick: () -> Unit
) {
    val label = if (isActive) "Active" else "Setup"
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp)
            .testTag("header_status_text")
    ) {
        Text(
            text = label,
            fontFamily = SpaceGroteskFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp,
            color = if (isActive) EmeraldActive else AmberWarn
        )
    }
}

@Composable
fun MiniPhoneZonePreview(
    config: GestureZoneConfig,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = Color(0xFF0E1016),
        border = BorderStroke(1.2.dp, Color(0xFF343A4A)),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / 390f
            val scaleY = size.height / 844f
            val w = (config.widthDp * scaleX).coerceAtLeast(4.dp.toPx())
            val h = (config.heightDp * scaleY).coerceAtLeast(4.dp.toPx())
            val insetX = config.edgeInsetDp * scaleX
            val insetY = config.edgeInsetDp * scaleY

            val (left, top) = when (config.anchor) {
                EdgeAnchor.LEFT_EDGE -> insetX to ((size.height - h) * config.offsetYFraction)
                EdgeAnchor.RIGHT_EDGE -> (size.width - w - insetX) to ((size.height - h) * config.offsetYFraction)
                EdgeAnchor.BOTTOM_BAR -> ((size.width - w) * config.offsetYFraction) to (size.height - h - insetY)
                EdgeAnchor.FREE -> ((size.width - w) * config.offsetXFraction) to ((size.height - h) * config.offsetYFraction)
            }

            drawRoundRect(
                color = ElectricCyan.copy(alpha = 0.45f),
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.DashboardScreen
import com.example.ui.QuickCircleViewModel
import com.example.ui.SandboxScreen
import com.example.ui.ZoneEditorScreen
import com.example.ui.theme.QuickCircleTheme

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_ZONE_EDITOR = "zone_editor"
private const val ROUTE_SANDBOX = "sandbox"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuickCircleTheme {
                QuickCircleApp()
            }
        }
    }
}

@Composable
fun QuickCircleApp(
    viewModel: QuickCircleViewModel = viewModel()
) {
    val navController = rememberNavController()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val packStatuses by viewModel.packStatuses.collectAsStateWithLifecycle()
    val packErrors by viewModel.packErrors.collectAsStateWithLifecycle()
    val packProgress by viewModel.packProgress.collectAsStateWithLifecycle()
    val mlKitOcrStatuses by viewModel.mlKitOcrStatuses.collectAsStateWithLifecycle()
    val mlKitOcrErrors by viewModel.mlKitOcrErrors.collectAsStateWithLifecycle()
    val mlKitOcrProgress by viewModel.mlKitOcrProgress.collectAsStateWithLifecycle()
    val tessOcrStatuses by viewModel.tessOcrStatuses.collectAsStateWithLifecycle()
    val tessOcrErrors by viewModel.tessOcrErrors.collectAsStateWithLifecycle()
    val tessOcrProgress by viewModel.tessOcrProgress.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_DASHBOARD
    ) {
        composable(ROUTE_DASHBOARD) {
            DashboardScreen(
                config = config,
                isAccessibilityEnabled = isAccessibilityEnabled,
                packStatuses = packStatuses,
                packErrors = packErrors,
                packProgress = packProgress,
                mlKitOcrStatuses = mlKitOcrStatuses,
                mlKitOcrErrors = mlKitOcrErrors,
                mlKitOcrProgress = mlKitOcrProgress,
                tessOcrStatuses = tessOcrStatuses,
                tessOcrErrors = tessOcrErrors,
                tessOcrProgress = tessOcrProgress,
                onOpenZoneEditor = { navController.navigate(ROUTE_ZONE_EDITOR) },
                onOpenSandbox = { navController.navigate(ROUTE_SANDBOX) },
                onToggleTriggerEnabled = viewModel::setTriggerEnabled,
                onToggleHaptic = viewModel::setHapticFeedback,
                onSelectTargetLanguage = viewModel::setTargetLanguage,
                onSelectScriptMode = viewModel::setScriptMode,
                onDownloadLanguagePack = viewModel::downloadLanguagePack,
                onDeleteLanguagePack = viewModel::deleteLanguagePack,
                onDownloadMlKitOcrScript = viewModel::downloadMlKitOcrScript,
                onPreloadAllMlKitOcrScripts = viewModel::preloadAllMlKitOcrScripts,
                onDownloadTesseractOcrPack = viewModel::downloadTesseractOcrPack,
                onDeleteTesseractOcrPack = viewModel::deleteTesseractOcrPack
            )
        }

        composable(ROUTE_ZONE_EDITOR) {
            ZoneEditorScreen(
                config = config,
                onBack = { navController.popBackStack() },
                onSetAnchor = viewModel::setAnchor,
                onUpdateGeometry = viewModel::updateZoneGeometry,
                onSetTriggerMode = viewModel::setTriggerMode,
                onSetHandleOpacity = viewModel::setHandleOpacity,
                onSetEdgeInsetDp = viewModel::setEdgeInsetDp,
                onSetExcludeSystemBack = viewModel::setExcludeSystemBack,
                onSetTargetLanguage = viewModel::setTargetLanguage,
                onResetDefaults = viewModel::resetDefaults
            )
        }

        composable(ROUTE_SANDBOX) {
            SandboxScreen(
                config = config,
                onBack = { navController.popBackStack() },
                onSetTargetLanguage = viewModel::setTargetLanguage
            )
        }
    }
}

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
                onOpenZoneEditor = { navController.navigate(ROUTE_ZONE_EDITOR) },
                onOpenSandbox = { navController.navigate(ROUTE_SANDBOX) },
                onToggleTriggerEnabled = viewModel::setTriggerEnabled,
                onToggleHaptic = viewModel::setHapticFeedback,
                onSelectTargetLanguage = viewModel::setTargetLanguage,
                onSelectScriptMode = viewModel::setScriptMode
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

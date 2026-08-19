package com.mossgreen.sage

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mossgreen.sage.ui.CommandsScreen
import com.mossgreen.sage.ui.DashboardScreen
import com.mossgreen.sage.ui.KeysScreen
import com.mossgreen.sage.ui.MonitoredChatsScreen
import com.mossgreen.sage.ui.ScreenCapturesScreen
import com.mossgreen.sage.ui.SettingsScreen
import com.mossgreen.sage.ui.theme.SageTheme

enum class Tab(@param:StringRes val titleRes: Int, val icon: ImageVector) {
    Dashboard(R.string.dashboard_title, Icons.Default.Home),
    Keys(R.string.keys_title, Icons.Default.Lock),
    Commands(R.string.commands_title, Icons.AutoMirrored.Filled.List),
    Settings(R.string.settings_title, Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SageTheme {
                SageMainScreen()
            }
        }
    }
}

@Composable
fun SageMainScreen(vm: SageViewModel = viewModel()) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    var showMonitoredChats by rememberSaveable { mutableStateOf(false) }
    var showScreenCaptures by rememberSaveable { mutableStateOf(false) }

    // Request notification permission on first launch (Android 13+)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> // Result not needed — we just need to prompt once
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("notification_permission_requested", true).apply()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val alreadyRequested = prefs.getBoolean("notification_permission_requested", false)
            if (!alreadyRequested) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = stringResource(tab.titleRes)
                            )
                        },
                        label = null,
                        selected = selectedTab == tab,
                        onClick = {
                            if (selectedTab != tab) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedTab = tab
                                showMonitoredChats = false
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        val screens = remember {
            Tab.entries.associateWith { tab ->
                movableContentOf {
                    when (tab) {
                        Tab.Dashboard -> DashboardScreen(vm.keyManager, vm.commandManager, vm.statsManager)
                        Tab.Keys -> KeysScreen(vm.keyManager, vm.prefs)
                        Tab.Commands -> CommandsScreen(vm.commandManager)
                        Tab.Settings -> {
                            if (showMonitoredChats) {
                                MonitoredChatsScreen(
                                    manager = vm.monitoredChatsManager,
                                    onBack = { showMonitoredChats = false }
                                )
                            } else if (showScreenCaptures) {
                                ScreenCapturesScreen(
                                    manager = vm.screenCaptureManager,
                                    onBack = { showScreenCaptures = false }
                                )
                            } else {
                                SettingsScreen(
                                    commandManager = vm.commandManager,
                                    prefs = vm.prefs,
                                    monitoredChatsManager = vm.monitoredChatsManager,
                                    lastFmManager = vm.lastFmManager,
                                    screenCaptureManager = vm.screenCaptureManager,
                                    onNavigateToMonitoredChats = { showMonitoredChats = true },
                                    onNavigateToScreenCaptures = { showScreenCaptures = true }
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier.padding(innerPadding),
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal)
                    AnimatedContentTransitionScope.SlideDirection.Left
                else
                    AnimatedContentTransitionScope.SlideDirection.Right
                slideIntoContainer(direction, tween(250, easing = FastOutSlowInEasing)) togetherWith
                    slideOutOfContainer(direction, tween(250, easing = FastOutSlowInEasing))
            },
            label = "tab_transition"
        ) { tab ->
            screens[tab]?.invoke()
        }
    }
}

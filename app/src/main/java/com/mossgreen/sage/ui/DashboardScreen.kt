package com.mossgreen.sage.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mossgreen.sage.R
import com.mossgreen.sage.manager.CommandManager
import com.mossgreen.sage.manager.KeyManager
import com.mossgreen.sage.manager.StatsManager
import com.mossgreen.sage.manager.StoragePermissionManager
import com.mossgreen.sage.ui.components.ScreenTitle
import com.mossgreen.sage.ui.components.SlateCard
import com.mossgreen.sage.ui.components.SlateDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

private fun checkServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName
    }
}

@Composable
fun DashboardScreen(keyManager: KeyManager, commandManager: CommandManager, statsManager: StatsManager) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isServiceEnabled by remember { mutableStateOf(checkServiceEnabled(context)) }
    // Not seeded from keyManager.getKeys(): that decrypts through AndroidKeyStore on the main
    // thread. The LaunchedEffect below fills it in on the IO dispatcher, as it already did on
    // every subsequent resume.
    var keyCount by remember { mutableIntStateOf(0) }

    // Stats state
    var monthlyRequests by remember { mutableIntStateOf(statsManager.monthlyRequests) }
    var favoriteCommand by remember { mutableStateOf(statsManager.favoriteCommand) }
    var dailyCounts by remember { mutableStateOf(statsManager.dailyCounts()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    var isStorageGranted by remember { mutableStateOf(StoragePermissionManager.hasStoragePermission(context)) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        isStorageGranted = StoragePermissionManager.hasStoragePermission(context)
        if (isStorageGranted) {
            StoragePermissionManager.getSageDownloadDir()
        }
    }

    DisposableEffect(context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            isServiceEnabled = checkServiceEnabled(context)
        }
        am.addAccessibilityStateChangeListener(listener)
        onDispose { am.removeAccessibilityStateChangeListener(listener) }
    }

    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val (newEnabled, newKeyCount) = withContext(Dispatchers.IO) {
                Pair(checkServiceEnabled(context), keyManager.getKeys().size)
            }
            isServiceEnabled = newEnabled
            isStorageGranted = StoragePermissionManager.hasStoragePermission(context)
            if (isStorageGranted) {
                withContext(Dispatchers.IO) {
                    StoragePermissionManager.getSageDownloadDir()
                }
            }
            keyCount = newKeyCount
            monthlyRequests = statsManager.monthlyRequests
            favoriteCommand = statsManager.favoriteCommand
            dailyCounts = statsManager.dailyCounts()
        }
    }

    val noData = stringResource(R.string.dashboard_no_data)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        ScreenTitle(stringResource(R.string.dashboard_title))

        // Service status + API keys
        SlateCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isServiceEnabled) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isServiceEnabled) stringResource(R.string.service_status_active)
                        else stringResource(R.string.service_status_inactive),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!isServiceEnabled) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.service_enable))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SlateDivider()
            Spacer(modifier = Modifier.height(12.dp))

            if (!isStorageGranted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.storage_permission_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.storage_permission_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                StoragePermissionManager.requestStoragePermission(context)
                            } else {
                                permissionLauncher.launch(StoragePermissionManager.getRequiredPermissions())
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Text(stringResource(R.string.storage_permission_grant))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SlateDivider()
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.dashboard_api_keys_title),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.dashboard_keys_configured, keyCount),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (keyCount == 0) {
                Text(
                    text = stringResource(R.string.dashboard_add_key_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Usage statistics card
        SlateCard(modifier = Modifier.weight(1f)) {
            // Summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "$monthlyRequests",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.dashboard_monthly_requests),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = favoriteCommand ?: noData,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.dashboard_favorite_command),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SlateDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // 7-day bar chart
            Text(
                text = stringResource(R.string.dashboard_last_7_days),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            val maxCount = dailyCounts.maxOfOrNull { it.second } ?: 0
            val dayNameFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
            val dateParseFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                dailyCounts.forEach { (dateStr, count) ->
                    val dayLabel = try {
                        val date = dateParseFmt.parse(dateStr)
                        dayNameFmt.format(date!!).take(3)
                    } catch (_: Exception) { "?" }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (count > 0) "$count" else "",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(if (maxCount > 0) (count.toFloat() / maxCount).coerceAtLeast(if (count > 0) 0.05f else 0f) else 0f)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dayLabel,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

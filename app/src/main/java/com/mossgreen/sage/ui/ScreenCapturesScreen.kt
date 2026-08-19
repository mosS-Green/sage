package com.mossgreen.sage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mossgreen.sage.R
import com.mossgreen.sage.manager.ScreenCaptureManager
import com.mossgreen.sage.model.ScreenCaptureLog
import com.mossgreen.sage.ui.components.ScreenTitle
import com.mossgreen.sage.ui.components.SlateCard

@Composable
fun ScreenCapturesScreen(
    manager: ScreenCaptureManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var captures by remember { mutableStateOf(manager.getCaptures()) }
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBack()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.ss_back_desc),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            ScreenTitle(stringResource(R.string.ss_title))
            Spacer(modifier = Modifier.weight(1f))

            if (captures.isNotEmpty()) {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showClearDialog = true
                    }
                ) {
                    Text(
                        text = stringResource(R.string.ss_clear_all),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ss_desc),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (captures.isEmpty()) {
            SlateCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.ss_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(captures, key = { it.id }) { log ->
                    ScreenCaptureLogCard(
                        log = log,
                        onDelete = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            manager.deleteCapture(log.id)
                            captures = manager.getCaptures()
                        },
                        onCopyAll = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Sage SS Output", log.fullDump))
                            Toast.makeText(context, R.string.ss_copied, Toast.LENGTH_SHORT).show()
                        },
                        onCopyJson = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Sage SS JSON", log.jsonDump))
                            Toast.makeText(context, R.string.ss_json_copied, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.ss_clear_confirm_title)) },
            text = { Text(stringResource(R.string.ss_clear_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        manager.clearAll()
                        captures = emptyList()
                        showClearDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.ss_clear_all),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.commands_cancel))
                }
            }
        )
    }
}

@Composable
private fun ScreenCaptureLogCard(
    log: ScreenCaptureLog,
    onDelete: () -> Unit,
    onCopyAll: () -> Unit,
    onCopyJson: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isExpanded by remember { mutableStateOf(false) }

    SlateCard(modifier = Modifier.fillMaxWidth()) {
        // Header Row: App name + time + delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.primaryPackage,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${log.formattedTime} • ${log.nodeCount} nodes, ${log.windowCount} windows",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.ss_delete_desc),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick text summary preview
        if (log.textSummary.isNotBlank()) {
            val previewText = log.textSummary.lines().take(4).joinToString("\n")
            Text(
                text = previewText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 16.sp,
                maxLines = 4
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Expanded Hierarchy Tree view
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = "Accessibility Tree Hierarchy:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = log.treeDump,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onCopyAll,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(stringResource(R.string.ss_copy_all), fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onCopyJson,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(stringResource(R.string.ss_copy_json), fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.weight(1f))

            TextButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isExpanded = !isExpanded
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(
                    text = if (isExpanded) "Collapse" else "View Tree",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

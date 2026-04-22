package com.pratham.cloudstorage

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload

import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════════════
//  NODE STATUS SECTION — Top-level entry point for MainActivity
//
//  Handles all states: STOPPED (hidden), STARTING (boot animation),
//  ACTIVE (live metrics + transfer overlay), ERROR
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun NodeStatusSection(
    nodeStatus: NodeStatus,
    transferState: TransferCardState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = nodeStatus != NodeStatus.STOPPED,
        enter = fadeIn(tween(400)) + expandVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ),
        exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
    ) {
        // Track whether the boot sequence has been completed this session
        var bootComplete by remember { mutableStateOf(false) }

        // If the node goes back to STARTING, reset boot
        LaunchedEffect(nodeStatus) {
            if (nodeStatus == NodeStatus.STARTING) {
                bootComplete = false
            }
            // If we skip to ACTIVE without seeing STARTING (app reopened with node already running)
            if (nodeStatus == NodeStatus.ACTIVE && !bootComplete) {
                bootComplete = true
            }
        }

        Crossfade(
            targetState = when {
                nodeStatus == NodeStatus.STARTING && !bootComplete -> "booting"
                nodeStatus == NodeStatus.ERROR -> "error"
                else -> "active"
            },
            animationSpec = tween(500),
            label = "node_status_crossfade"
        ) { state ->
            when (state) {
                "booting" -> NodeStartupSequence(
                    onComplete = { bootComplete = true },
                    modifier = modifier
                )
                "error" -> ErrorCard(modifier = modifier)
                else -> ActiveNodeCard(
                    transferState = transferState,
                    modifier = modifier
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  BOOT ANIMATION — Terminal-style startup sequence
// ═══════════════════════════════════════════════════════════════════════════════

private data class BootStep(
    val tag: String,
    val label: String,
    val durationMs: Long
)

private enum class BootStepState { PENDING, RUNNING, DONE }

@Composable
private fun NodeStartupSequence(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableIntStateOf(0) }
    var allComplete by remember { mutableStateOf(false) }

    val bootSteps = remember {
        listOf(
            BootStep("INIT",  "Initializing Ktor server",     80L),
            BootStep("SAF",   "Mounting storage filesystem",  120L),
            BootStep("NET",   "Binding HTTP listener :8080",   90L),
            BootStep("REG",   "Registering node identity",     70L),
            BootStep("RELAY", "Connecting relay tunnel",      150L),
            BootStep("READY", "Node active — routing enabled", 60L),
        )
    }

    LaunchedEffect(Unit) {
        bootSteps.forEachIndexed { index, step ->
            currentStep = index
            delay(step.durationMs)
        }
        currentStep = bootSteps.size // Mark all done
        allComplete = true
        delay(600)
        onComplete()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0C14), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1C2035), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Terminal header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EASY STORAGE NODE",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 0.16.em,
                    color = Color(0xFF3A3F58)
                )
            )
            if (!allComplete) {
                BlinkingCursor()
            } else {
                Text(
                    text = "● LIVE",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = Color(0xFF22C55E),
                        letterSpacing = 0.1.em
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Boot steps
        bootSteps.forEachIndexed { index, step ->
            BootStepRow(
                step = step,
                state = when {
                    index < currentStep  -> BootStepState.DONE
                    index == currentStep -> BootStepState.RUNNING
                    else                 -> BootStepState.PENDING
                }
            )
        }
    }
}

@Composable
private fun BootStepRow(step: BootStep, state: BootStepState) {
    val alpha by animateFloatAsState(
        targetValue = if (state == BootStepState.PENDING) 0.3f else 1.0f,
        animationSpec = tween(200),
        label = "step_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tag badge
        Box(
            modifier = Modifier
                .width(44.dp)
                .background(
                    when (state) {
                        BootStepState.DONE    -> Color(0xFF22C55E).copy(alpha = 0.12f)
                        BootStepState.RUNNING -> Color(0xFF3B82F6).copy(alpha = 0.12f)
                        BootStepState.PENDING -> Color(0xFF1C2035)
                    },
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step.tag,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (state) {
                        BootStepState.DONE    -> Color(0xFF22C55E)
                        BootStepState.RUNNING -> Color(0xFF3B82F6)
                        BootStepState.PENDING -> Color(0xFF3A3F58)
                    }
                )
            )
        }

        // Label
        Text(
            text = step.label,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = when (state) {
                    BootStepState.DONE    -> Color(0xFF7A8099)
                    BootStepState.RUNNING -> Color(0xFFE2E5F0)
                    BootStepState.PENDING -> Color(0xFF3A3F58)
                }
            ),
            modifier = Modifier.weight(1f)
        )

        // Status indicator
        when (state) {
            BootStepState.DONE -> {
                Text(
                    text = "✓",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = Color(0xFF22C55E),
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
            BootStepState.RUNNING -> {
                RunningDots()
            }
            BootStepState.PENDING -> {
                Text(
                    text = "·",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = Color(0xFF3A3F58),
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  ACTIVE NODE CARD — Live metrics + transfer overlay
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ActiveNodeCard(
    transferState: TransferCardState,
    modifier: Modifier = Modifier
) {
    val isTransferActive = transferState is TransferCardState.Active
    val isExtracting = transferState is TransferCardState.Extracting
    val isCompleting = transferState is TransferCardState.Completing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0C14), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1C2035), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PulsingDot(color = Color(0xFF22C55E))
                Text(
                    text = "NODE ACTIVE",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF22C55E),
                        letterSpacing = 0.14.em
                    )
                )
            }
            Text(
                text = "ROUTING",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF3A3F58)
                )
            )
        }

        // Live metrics row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricChip(label = "PING", value = "42ms", color = Color(0xFF06B6D4))
            MetricChip(label = "STATUS", value = "ROUTING", color = Color(0xFF22C55E))
            MetricChip(label = "RELAY", value = "ACTIVE", color = Color(0xFF8B5CF6))
        }

        // Transfer activity — show when uploading
        AnimatedVisibility(
            visible = isTransferActive,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit  = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            val activeState = transferState as? TransferCardState.Active
            if (activeState != null) {
                TransferProgressRow(activeState)
            }
        }

        // Extraction activity — show when processing archive
        AnimatedVisibility(
            visible = isExtracting,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit  = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            val extractingState = transferState as? TransferCardState.Extracting
            if (extractingState != null) {
                TransferExtractingRow(extractingState)
            }
        }

        // Completion state — show green confirmation
        AnimatedVisibility(
            visible = isCompleting,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit  = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            val completingState = transferState as? TransferCardState.Completing
            if (completingState != null) {
                CompletionRow(completingState)
            }
        }
    }
}

@Composable
private fun TransferProgressRow(state: TransferCardState.Active) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progressPercent / 100f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF1C2035)))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedUploadIcon()
                Text(
                    text = state.fileName.ifBlank { "uploading..." },
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(0xFF7A8099)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.speedBytesPerSecond > 0) {
                    Text(
                        text = "${formatBytes(state.speedBytesPerSecond)}/s",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF06B6D4)
                        )
                    )
                }

                Text(
                    text = "${state.progressPercent}%",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3B82F6)
                    )
                )
            }
        }

        LinearProgressIndicator(
            progress = animatedProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp)),
            color = Color(0xFF3B82F6),
            trackColor = Color(0xFF1C2035),
        )

        // Bytes and multi-upload count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${formatBytes(state.bytesWritten)} / ${formatBytes(state.totalBytes)}",
                style = TextStyle(
                    fontSize = 9.sp,
                    color = Color(0xFF3A3F58),
                    fontFamily = FontFamily.Monospace
                )
            )
            if (state.activeCount > 1) {
                Text(
                    text = "+${state.activeCount - 1} more",
                    style = TextStyle(
                        fontSize = 9.sp,
                        color = Color(0xFF3A3F58),
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

@Composable
private fun TransferExtractingRow(state: TransferCardState.Extracting) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF1C2035)))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RunningDots() // Reusing the dots animation for extraction
                Text(
                    text = "Extracting ${state.fileName}",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(0xFFE2E5F0)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp)
                )
            }
        }

        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp)),
            color = Color(0xFF8B5CF6), // Purple color for extraction
            trackColor = Color(0xFF1C2035),
        )

        Text(
            text = "Processing archive contents — please wait...",
            style = TextStyle(
                fontSize = 9.sp,
                color = Color(0xFF3A3F58),
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@Composable
private fun CompletionRow(state: TransferCardState.Completing) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF1C2035)))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Upload Complete",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF22C55E)
                    )
                )
            }
            Text(
                text = formatBytes(state.totalBytes),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF3A3F58)
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  ERROR CARD
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ErrorCard(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A0A0A), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Color(0xFFEF4444), CircleShape)
        )
        Text(
            text = "NODE ERROR",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFEF4444),
                letterSpacing = 0.14.em
            )
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Failed to start",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF7A8099)
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  SHARED HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MetricChip(label: String, value: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = Color(0xFF3A3F58),
                letterSpacing = 0.12.em
            )
        )
        Text(
            text = value,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        )
    }
}

@Composable
private fun AnimatedUploadIcon() {
    val infiniteTransition = rememberInfiniteTransition(label = "upload_pulse")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_alpha"
    )

    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -2f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_offset"
    )

    Icon(
        imageVector = Icons.Rounded.CloudUpload,
        contentDescription = "Uploading",
        tint = Color(0xFF3B82F6).copy(alpha = alpha),
        modifier = Modifier
            .size(14.dp)
            .offset(y = offsetY.dp)
    )
}

@Composable
private fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_scale"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .scale(scale)
            .background(color, CircleShape)
    )
}

@Composable
private fun RunningDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotIndex by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dot_index"
    )

    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (0..2).forEach { i ->
            Text(
                text = "·",
                style = TextStyle(
                    fontSize = 14.sp,
                    color = Color(0xFF3B82F6).copy(
                        alpha = if (i.toFloat() < dotIndex) 1f else 0.25f
                    ),
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val visible by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_blink"
    )
    Text(
        text = "▋",
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF22C55E).copy(alpha = visible)
        )
    )
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

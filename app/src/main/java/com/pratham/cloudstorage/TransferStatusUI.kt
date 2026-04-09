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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TransferStatusCard(
    state: TransferCardState,
    modifier: Modifier = Modifier
) {
    // Animated height — never collapses to zero, smoothly expands/contracts
    val targetHeight = when (state) {
        is TransferCardState.Idle       -> 56.dp
        is TransferCardState.Active     -> 112.dp
        is TransferCardState.Completing -> 72.dp
    }
    
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "card_height"
    )

    // Border color animates between states
    val borderColor by animateColorAsState(
        targetValue = when (state) {
            is TransferCardState.Idle       -> Color(0xFF1C2035)
            is TransferCardState.Active     -> Color(0xFF3B82F6).copy(alpha = 0.4f)
            is TransferCardState.Completing -> Color(0xFF22C55E).copy(alpha = 0.4f)
        },
        animationSpec = tween(400),
        label = "border_color"
    )

    // Background color animates
    val bgColor by animateColorAsState(
        targetValue = when (state) {
            is TransferCardState.Idle       -> Color(0xFF0F1117)
            is TransferCardState.Active     -> Color(0xFF0A1628)
            is TransferCardState.Completing -> Color(0xFF052010)
        },
        animationSpec = tween(400),
        label = "bg_color"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        when (state) {
            is TransferCardState.Idle -> IdleContent()
            is TransferCardState.Active -> ActiveContent(state)
            is TransferCardState.Completing -> CompletingContent(state)
        }
    }
}

@Composable
private fun IdleContent() {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Pulsing green dot
        PulsingDot(color = Color(0xFF22C55E))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Node Active",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE2E5F0)
                )
            )
            Text(
                text = "Server is actively routing",
                style = TextStyle(
                    fontSize = 11.sp,
                    color = Color(0xFF7A8099)
                )
            )
        }
        
        // Checkmark icon
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF22C55E),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ActiveContent(state: TransferCardState.Active) {
    // Animated progress float for smooth bar movement
    val animatedProgress by animateFloatAsState(
        targetValue = state.progressPercent / 100f,
        animationSpec = tween(
            durationMillis = 600,
            easing = FastOutSlowInEasing
        ),
        label = "progress"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top row — icon, filename, percentage
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Animated upload icon
            AnimatedUploadIcon()

            // Filename — truncated
            Text(
                text = state.fileName,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE2E5F0),
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Percentage badge
            Box(
                modifier = Modifier
                    .background(
                        Color(0xFF3B82F6).copy(alpha = 0.15f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${state.progressPercent}%",
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3B82F6),
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }

        // Progress bar — smooth animated fill
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFF3B82F6),
                trackColor = Color(0xFF1C2035),
                strokeCap = StrokeCap.Round
            )

            // Bottom row — bytes and speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatBytes(state.bytesWritten)} / ${formatBytes(state.totalBytes)}",
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = Color(0xFF7A8099),
                        fontFamily = FontFamily.Monospace
                    )
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Speed
                    if (state.speedBytesPerSecond > 0) {
                        Text(
                            text = "${formatBytes(state.speedBytesPerSecond)}/s",
                            style = TextStyle(
                                fontSize = 10.sp,
                                color = Color(0xFF06B6D4),
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    
                    // Multiple uploads indicator
                    if (state.activeCount > 1) {
                        Text(
                            text = "+${state.activeCount - 1} more",
                            style = TextStyle(
                                fontSize = 10.sp,
                                color = Color(0xFF3A3F58),
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletingContent(state: TransferCardState.Completing) {
    // Fade in animation when this state first appears
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Green checkmark circle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        Color(0xFF22C55E).copy(alpha = 0.15f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Upload Complete",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF22C55E)
                    )
                )
                Text(
                    text = state.fileName,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = Color(0xFF7A8099),
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = formatBytes(state.totalBytes),
                style = TextStyle(
                    fontSize = 11.sp,
                    color = Color(0xFF3A3F58),
                    fontFamily = FontFamily.Monospace
                )
            )
        }
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
            .size(18.dp)
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

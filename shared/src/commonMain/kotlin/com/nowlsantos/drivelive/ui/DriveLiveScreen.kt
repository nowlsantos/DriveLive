package com.nowlsantos.drivelive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nowlsantos.drivelive.state.KaiSessionState
import com.nowlsantos.drivelive.state.KaiShortcutBridge
import com.nowlsantos.drivelive.presentation.DriveLiveViewModel
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs

private val OledBlack = Color(0xFF050505)
private val SurfaceDark = Color(0xFF121418)
private val KaiRed = Color(0xFFFF2A2A)
private val KaiAmber = Color(0xFFFF9100)
private val DriverCyan = Color(0xFF00E5FF)
private val TextPrimary = Color(0xFFF4F6F9)
private val TextSecondary = Color(0xFF9AA4B2)

@Composable
fun DriveLiveScreen(
    viewModel: DriveLiveViewModel = koinViewModel(),
    hasPermissions: Boolean = true,
    onRequestPermissions: () -> Unit = {}
) {
    val state by viewModel.sessionState.collectAsState()
    val pendingShortcutEngage by KaiShortcutBridge.pendingEngage.collectAsState()

    LaunchedEffect(hasPermissions, pendingShortcutEngage) {
        if (hasPermissions && pendingShortcutEngage && KaiShortcutBridge.consumeEngage()) {
            viewModel.engageFromShortcut()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusHeader(state = state)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                KaiLedEqualizer(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                TranscriptCard(state = state)
            }

            BottomControlBar(
                state = state,
                hasPermissions = hasPermissions,
                onRequestPermissions = onRequestPermissions,
                onToggleSession = { viewModel.toggleSession() },
                onInspectRoad = { viewModel.triggerManualCameraInspect() }
            )
        }

        val cameraBurst = state as? KaiSessionState.CameraBurst
        AnimatedVisibility(
            visible = cameraBurst != null,
            enter = fadeIn() + scaleIn(initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.88f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 20.dp)
        ) {
            if (cameraBurst != null) {
                CameraPipCard(
                    remainingSeconds = cameraBurst.remainingSeconds,
                    onCancelScan = { viewModel.cancelCameraBurst() }
                )
            }
        }
    }
}

@Composable
private fun CameraPipCard(
    remainingSeconds: Int,
    onCancelScan: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 136.dp, height = 184.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, KaiRed.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .clickable { onCancelScan() }
    ) {
        CameraPreviewOverlay(modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .background(OledBlack.copy(alpha = 0.72f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(KaiRed, CircleShape)
            )
            Text(
                text = "SCAN ${remainingSeconds}S",
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }

        Text(
            text = "TAP TO CLOSE",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(OledBlack.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StatusHeader(state: KaiSessionState) {
    val (badgeText, badgeColor) = when (state) {
        is KaiSessionState.Idle -> "STANDBY" to TextSecondary
        is KaiSessionState.Connecting -> "INITIALIZING KAI..." to KaiAmber
        is KaiSessionState.Listening -> "KAI // LISTENING" to DriverCyan
        is KaiSessionState.Speaking -> "KAI // SPEAKING" to KaiRed
        is KaiSessionState.ExecutingTool -> "EXECUTING // ${state.toolName.uppercase()}" to KaiAmber
        is KaiSessionState.CameraBurst -> "OPTICAL SCAN (${state.remainingSeconds}S)" to KaiRed
        is KaiSessionState.Reconnecting -> "RECONNECTING (${state.attempt})..." to KaiAmber
        is KaiSessionState.Error -> "SYSTEM ALERT" to KaiRed
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "DRIVELIVE",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = badgeText,
            color = badgeColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
private fun KaiLedEqualizer(
    state: KaiSessionState,
    modifier: Modifier = Modifier
) {
    val targetAmplitude = when (state) {
        is KaiSessionState.Speaking -> (state.voiceAmplitude * 3.2f).coerceIn(0.15f, 1f)
        is KaiSessionState.Listening -> (state.micAmplitude * 3.5f).coerceIn(0.08f, 1f)
        else -> 0.05f
    }

    val smoothAmplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(durationMillis = 70),
        label = "amplitude"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "scanner")
    val scannerPosition by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scannerPos"
    )

    val activeColor = when (state) {
        is KaiSessionState.Speaking,
        is KaiSessionState.CameraBurst -> KaiRed
        is KaiSessionState.Listening -> DriverCyan
        is KaiSessionState.Connecting,
        is KaiSessionState.ExecutingTool,
        is KaiSessionState.Reconnecting -> KaiAmber
        else -> Color(0xFF2A2E37)
    }

    Canvas(modifier = modifier) {
        val barCount = 11
        val centerIndex = barCount / 2
        val gap = 12.dp.toPx()
        val totalGaps = gap * (barCount - 1)
        val barWidth = ((size.width - totalGaps) / barCount).coerceAtLeast(8f)
        val maxBarHeight = size.height * 0.9f

        val isScanningMode = state is KaiSessionState.Connecting ||
                state is KaiSessionState.ExecutingTool ||
                state is KaiSessionState.Reconnecting

        for (i in 0 until barCount) {
            val heightRatio = if (isScanningMode) {
                val dist = abs(i - scannerPosition)
                (1f - (dist / 3.5f)).coerceIn(0.12f, 1f)
            } else {
                val distFromCenter = abs(i - centerIndex)
                val falloff = 1f - (distFromCenter * 0.14f)
                (smoothAmplitude * falloff).coerceIn(0.08f, 1f)
            }

            val barHeight = maxBarHeight * heightRatio
            val left = i * (barWidth + gap)
            val top = (size.height - barHeight) / 2f

            drawRoundRect(
                color = Color(0xFF14171D),
                topLeft = Offset(left, (size.height - maxBarHeight) / 2f),
                size = Size(barWidth, maxBarHeight),
                cornerRadius = CornerRadius(6.dp.toPx())
            )

            drawRoundRect(
                color = activeColor,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
        }
    }
}

@Composable
private fun TranscriptCard(state: KaiSessionState) {
    val displayText = when (state) {
        is KaiSessionState.Idle -> "Tap ENGAGE KAI to start your drive."
        is KaiSessionState.Connecting -> "Establishing secure voice link..."
        is KaiSessionState.Listening -> state.interimTranscript.ifBlank { "Go ahead, I'm listening." }
        is KaiSessionState.Speaking -> state.outputTranscript.ifBlank { "..." }
        is KaiSessionState.ExecutingTool -> state.summary
        is KaiSessionState.CameraBurst -> "Inspecting: ${state.reason}"
        is KaiSessionState.Reconnecting -> "Signal drop detected. Resuming session..."
        is KaiSessionState.Error -> state.message
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BottomControlBar(
    state: KaiSessionState,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleSession: () -> Unit,
    onInspectRoad: () -> Unit
) {
    val isInactive = state is KaiSessionState.Idle || state is KaiSessionState.Error

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isInactive) {
            OutlinedButton(
                onClick = onInspectRoad,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DriverCyan
                )
            ) {
                Text(
                    text = "LOOK AT ROAD (8S SCAN)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Button(
            onClick = {
                if (!hasPermissions) {
                    onRequestPermissions()
                } else {
                    onToggleSession()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isInactive) KaiRed else Color(0xFF232730),
                contentColor = TextPrimary
            )
        ) {
            Text(
                text = when {
                    !hasPermissions -> "GRANT MIC & LOCATION ACCESS"
                    isInactive -> "ENGAGE KAI"
                    else -> "END DRIVE SESSION"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp
            )
        }
    }
}
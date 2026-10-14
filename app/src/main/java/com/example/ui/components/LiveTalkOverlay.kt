package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodel.LiveTalkStatus

@Composable
fun LiveTalkOverlay(
    visible: Boolean,
    status: LiveTalkStatus,
    transcript: String,
    amplitude: Float,
    onOrbTapped: () -> Unit,
    onEndLiveTalk: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
        modifier = modifier
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "orbGlow")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        val rotationAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )

        // Reactive scale derived from mic amplitude
        val ampScale by animateFloatAsState(
            targetValue = (1f + (amplitude * 0.45f)).coerceIn(1f, 1.45f),
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
            label = "ampScale"
        )

        // Colors depending on state
        val (orbColors, statusText, statusBadgeBg, statusBadgeColor, glowColor) = when (status) {
            LiveTalkStatus.LISTENING -> Quintuple(
                listOf(Color(0xFF6EE7B7), Color(0xFF10B981), Color(0xFF047857)),
                "Listening",
                Color(0x3310B981),
                Color(0xFF6EE7B7),
                Color(0x4010B981)
            )
            LiveTalkStatus.THINKING -> Quintuple(
                listOf(Color(0xFFFDE68A), Color(0xFFF59E0B), Color(0xFFB45309)),
                "Thinking",
                Color(0x33F59E0B),
                Color(0xFFFDE68A),
                Color(0x40F59E0B)
            )
            LiveTalkStatus.SPEAKING -> Quintuple(
                listOf(Color(0xFFBAE6FD), Color(0xFF0EA5E9), Color(0xFF075985)),
                "Speaking",
                Color(0x330EA5E9),
                Color(0xFFBAE6FD),
                Color(0x450EA5E9)
            )
            else -> Quintuple(
                listOf(Color(0xFF10B981), Color(0xFF059669)),
                "Ready",
                Color(0x3310B981),
                Color(0xFF6EE7B7),
                Color(0x2010B981)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF50A0A0C))
                .statusBarsPadding()
                .drawBehind {
                    // Ambient radial glow behind orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor, Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.45f),
                            radius = size.width * 0.65f
                        )
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Orki Live",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFA1A1AA)
                    )

                    // Status Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(statusBadgeBg)
                            .border(1.dp, statusBadgeColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = statusText,
                            color = statusBadgeColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Center Orb and Transcript
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(240.dp)
                            .drawBehind {
                                // Rotating outer ring
                                drawCircle(
                                    color = statusBadgeColor.copy(alpha = 0.35f),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                    ) {
                        // Interactive Glowing Orb
                        Box(
                            modifier = Modifier
                                .size(160.dp)
                                .scale(if (status == LiveTalkStatus.LISTENING) ampScale else pulseScale)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(orbColors))
                                .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onOrbTapped
                                )
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Text(
                        text = if (status == LiveTalkStatus.LISTENING) "Tap orb to send speech immediately"
                        else if (status == LiveTalkStatus.SPEAKING) "Tap orb to interrupt & talk"
                        else "Orki is thinking…",
                        color = Color(0xFF71717A),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Transcript
                    Text(
                        text = transcript.ifEmpty { "…" },
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        textAlign = TextAlign.Center,
                        color = Color(0xFFE4E4E7),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // Bottom End Button
                IconButton(
                    onClick = onEndLiveTalk,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFDC2626))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "End Live Talk",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

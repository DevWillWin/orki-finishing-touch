package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.AmberPro
import com.example.ui.theme.CharcoalTextPrimary
import com.example.ui.theme.ForestGreenDeep
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.PaleSage
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SageGreen
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmBorderSubtle

@Composable
fun AttachmentPickerDialog(
    currentPlan: String,
    dailyUploadUsage: Int,
    dailyUploadLimit: Int,
    isThinkHarderActive: Boolean = false,
    onSelectCamera: () -> Unit,
    onSelectPhotos: () -> Unit,
    onSelectFiles: () -> Unit,
    onToggleThinkHarder: () -> Unit = {},
    onDismiss: () -> Unit,
    onUpgradeClick: () -> Unit
) {
    val remaining = (dailyUploadLimit - dailyUploadUsage).coerceAtLeast(0)
    val isLimitReached = dailyUploadUsage >= dailyUploadLimit

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = PureWhite,
                border = androidx.compose.foundation.BorderStroke(1.dp, WarmBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color(0x15000000),
                        spotColor = Color(0x20000000)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header with title and close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Attach to message",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = CharcoalTextPrimary
                            )
                            Text(
                                text = if (currentPlan == "Guest") "Guest: $remaining uploads left today" else "$currentPlan: $remaining/$dailyUploadLimit uploads left",
                                fontSize = 12.sp,
                                color = SlateTextSecondary
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(PaleSage)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = SlateTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // 4-item or 5-item Grid inspired by modern ChatGPT attachment popups
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Camera
                        AttachmentIconOption(
                            label = "Camera",
                            icon = Icons.Default.CameraAlt,
                            onClick = {
                                onDismiss()
                                onSelectCamera()
                            }
                        )

                        // 2. Photos (Gallery)
                        AttachmentIconOption(
                            label = "Photos",
                            icon = Icons.Default.Image,
                            onClick = {
                                onDismiss()
                                onSelectPhotos()
                            }
                        )

                        // 3. Files (SAF Docs)
                        AttachmentIconOption(
                            label = "Files",
                            icon = Icons.Default.Description,
                            onClick = {
                                onDismiss()
                                onSelectFiles()
                            }
                        )

                        // 4. Think harder (Reasoning toggle)
                        AttachmentIconOption(
                            label = "Think harder",
                            icon = Icons.Default.Psychology,
                            isActive = isThinkHarderActive,
                            onClick = {
                                onToggleThinkHarder()
                                onDismiss()
                            }
                        )
                    }

                    // Upload Quota or Upgrade hint
                    if (isLimitReached) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFEF3C7))
                                .border(1.dp, AmberPro.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .clickable {
                                    onDismiss()
                                    onUpgradeClick()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Daily upload limit reached. Tap here to upgrade for unlimited uploads.",
                                fontSize = 12.sp,
                                color = AmberPro,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentIconOption(
    label: String,
    icon: ImageVector,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // Circular icon container
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(if (isActive) ForestGreenPrimary else PaleSage)
                .border(
                    width = 1.dp,
                    color = if (isActive) ForestGreenDeep else SageGreen,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) PureWhite else ForestGreenPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) ForestGreenPrimary else CharcoalTextPrimary,
            textAlign = TextAlign.Center
        )
    }
}

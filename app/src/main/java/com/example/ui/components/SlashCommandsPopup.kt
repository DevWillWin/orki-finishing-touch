package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CharcoalTextPrimary
import com.example.ui.theme.ForestGreenDeep
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.PaleSage
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SageGreen
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.WarmBorder

enum class ActiveGenerationMode {
    NONE,
    IMAGE
}

data class SlashCommandItem(
    val command: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val mode: ActiveGenerationMode
)

val AvailableSlashCommands = listOf(
    SlashCommandItem(
        command = "/image",
        title = "Create Image",
        description = "Generate photorealistic art from text prompts",
        icon = Icons.Default.AutoAwesome,
        mode = ActiveGenerationMode.IMAGE
    )
)

@Composable
fun SlashCommandsPopup(
    inputText: String,
    visible: Boolean,
    onSelectCommand: (SlashCommandItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val query = inputText.trim().lowercase()
    val filtered = AvailableSlashCommands.filter { item ->
        query.startsWith("/") && (item.command.startsWith(query) || query == "/" || item.command.contains(query.removePrefix("/")))
    }

    AnimatedVisibility(
        visible = visible && filtered.isNotEmpty(),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = PureWhite,
            border = androidx.compose.foundation.BorderStroke(1.dp, WarmBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(18.dp), ambientColor = Color(0x1A000000), spotColor = Color(0x1A000000))
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Header label
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GENERATION COMMANDS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreenPrimary,
                        letterSpacing = 0.5.sp
                    )
                }

                filtered.forEach { item ->
                    SlashCommandRow(
                        item = item,
                        onClick = { onSelectCommand(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SlashCommandRow(
    item: SlashCommandItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular icon container
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(PaleSage)
                .border(1.dp, SageGreen, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = ForestGreenPrimary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.command,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ForestGreenDeep
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(PaleSage)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.title,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForestGreenPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.description,
                fontSize = 11.sp,
                color = SlateTextSecondary
            )
        }
    }
}

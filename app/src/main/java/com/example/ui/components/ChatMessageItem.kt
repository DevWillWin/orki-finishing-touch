package com.example.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.local.ChatMessageEntity
import com.example.data.network.ImageGenerationService
import com.example.ui.theme.CharcoalTextPrimary
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.IvoryBackground
import com.example.ui.theme.PaleSage
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SageGreen
import com.example.ui.theme.SlateTextSecondary
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmBorderSubtle
import com.example.util.formatFileSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChatMessageItem(
    message: ChatMessageEntity,
    onPlayTts: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }

    if (isUser) {
        // User message: Sleek right-aligned rounded bubble with Pale Sage surface (#EEF3EB)
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                    .background(PaleSage)
                    .border(1.dp, SageGreen, RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // If attachment is present, render attachment header
                if (message.attachmentName != null) {
                    AttachmentBubbleCard(
                        name = message.attachmentName,
                        type = message.attachmentType,
                        size = message.attachmentSize,
                        uri = message.attachmentUri
                    )
                    if (message.text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = CharcoalTextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    } else {
        // Assistant message: Clean editorial layout displayed unboxed on ivory background
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Header: Minimalist Avatar Badge + Orki AI Label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(PaleSage)
                        .border(1.dp, SageGreen, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = com.example.R.drawable.ic_orki_inapp_logo_circle,
                        contentDescription = "Orki AI",
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "Orki AI",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CharcoalTextPrimary
                )

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(PaleSage)
                        .border(1.dp, SageGreen.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Assistant",
                        color = ForestGreenPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // If assistant generated an image, display the artwork card
            if (message.attachmentUri != null && (message.attachmentType == "image" || message.attachmentType == "generated_image")) {
                GeneratedImageAssistantCard(
                    imageUri = message.attachmentUri,
                    prompt = message.text
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Assistant response text with structured paragraphs, code blocks, lists, and multilingual support
            if (message.text.isNotBlank()) {
                AssistantFormattedContent(
                    text = message.text,
                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                )

                // Compact Listen and Copy Action Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    // Compact Listen Button (Icon-Only with 48dp touch target)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PureWhite)
                            .border(1.dp, WarmBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                onPlayTts(message.ttsText ?: message.text)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Listen to response",
                            tint = SlateTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Compact Copy Button (Icon-Only with 48dp touch target)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PureWhite)
                            .border(
                                1.dp,
                                if (isCopied) ForestGreenPrimary else WarmBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                clipboardManager.setText(AnnotatedString(message.text))
                                isCopied = true
                                scope.launch {
                                    delay(2000)
                                    isCopied = false
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy text",
                            tint = if (isCopied) ForestGreenPrimary else SlateTextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Brief Feedback Indicator
                    AnimatedVisibility(
                        visible = isCopied,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PaleSage)
                                .border(1.dp, SageGreen, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Copied",
                                color = ForestGreenPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Editorial formatted content for assistant responses.
 * Breaks down paragraphs, handles code blocks, markdown headings, lists,
 * and maintains generous line height (24.sp) for comfortable reading and Devanagari/Bodo rendering.
 */
@Composable
fun AssistantFormattedContent(
    text: String,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Handle code blocks vs text paragraphs
    val parts = remember(text) { parseMessageBlocks(text) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parts.forEach { part ->
            when (part) {
                is MessageBlock.CodeBlock -> {
                    var codeCopied by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PaleSage.copy(alpha = 0.5f))
                            .border(1.dp, WarmBorder, RoundedCornerShape(10.dp))
                    ) {
                        // Header bar with language & copy button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PaleSage)
                                .border(
                                    width = 1.dp,
                                    color = WarmBorderSubtle,
                                    shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
                                )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = part.language.ifEmpty { "code" },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SlateTextSecondary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(part.code))
                                        codeCopied = true
                                        scope.launch {
                                            delay(1800)
                                            codeCopied = false
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (codeCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy code",
                                    tint = if (codeCopied) ForestGreenPrimary else SlateTextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (codeCopied) "Copied" else "Copy",
                                    fontSize = 11.sp,
                                    color = if (codeCopied) ForestGreenPrimary else SlateTextSecondary
                                )
                            }
                        }

                        // Code snippet body
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = part.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = CharcoalTextPrimary
                            )
                        }
                    }
                }

                is MessageBlock.Paragraph -> {
                    val rawParagraph = part.content.trim()
                    if (rawParagraph.isNotEmpty()) {
                        when {
                            rawParagraph.startsWith("### ") -> {
                                Text(
                                    text = formatMarkdownInline(rawParagraph.removePrefix("### ")),
                                    fontSize = 16.sp,
                                    lineHeight = 23.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CharcoalTextPrimary,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                )
                            }
                            rawParagraph.startsWith("## ") -> {
                                Text(
                                    text = formatMarkdownInline(rawParagraph.removePrefix("## ")),
                                    fontSize = 17.sp,
                                    lineHeight = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CharcoalTextPrimary,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                )
                            }
                            rawParagraph.startsWith("# ") -> {
                                Text(
                                    text = formatMarkdownInline(rawParagraph.removePrefix("# ")),
                                    fontSize = 18.sp,
                                    lineHeight = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CharcoalTextPrimary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                            rawParagraph.startsWith("- ") || rawParagraph.startsWith("* ") -> {
                                Row(
                                    modifier = Modifier.padding(start = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "•",
                                        fontSize = 15.sp,
                                        lineHeight = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ForestGreenPrimary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = formatMarkdownInline(rawParagraph.drop(2)),
                                        fontSize = 15.sp,
                                        lineHeight = 24.sp,
                                        color = CharcoalTextPrimary
                                    )
                                }
                            }
                            else -> {
                                Text(
                                    text = formatMarkdownInline(rawParagraph),
                                    fontSize = 15.sp,
                                    lineHeight = 24.sp,
                                    color = CharcoalTextPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface MessageBlock {
    data class Paragraph(val content: String) : MessageBlock
    data class CodeBlock(val language: String, val code: String) : MessageBlock
}

private fun parseMessageBlocks(raw: String): List<MessageBlock> {
    val blocks = mutableListOf<MessageBlock>()
    val lines = raw.lines()
    var inCodeBlock = false
    var codeLang = ""
    val currentCode = StringBuilder()
    val currentParagraph = StringBuilder()

    fun flushParagraph() {
        if (currentParagraph.isNotBlank()) {
            blocks.add(MessageBlock.Paragraph(currentParagraph.toString().trim()))
            currentParagraph.clear()
        }
    }

    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            if (inCodeBlock) {
                // End of code block
                blocks.add(MessageBlock.CodeBlock(codeLang, currentCode.toString().trimEnd()))
                currentCode.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                // Start of code block
                flushParagraph()
                inCodeBlock = true
                codeLang = line.trimStart().removePrefix("```").trim()
            }
        } else if (inCodeBlock) {
            currentCode.append(line).append("\n")
        } else {
            if (line.isBlank()) {
                flushParagraph()
            } else {
                if (currentParagraph.isNotEmpty()) currentParagraph.append("\n")
                currentParagraph.append(line)
            }
        }
    }

    if (inCodeBlock) {
        blocks.add(MessageBlock.CodeBlock(codeLang, currentCode.toString().trimEnd()))
    } else {
        flushParagraph()
    }

    return if (blocks.isEmpty()) listOf(MessageBlock.Paragraph(raw)) else blocks
}

/**
 * Inline markdown parser: **bold**, `code`, *italic*
 */
fun formatMarkdownInline(raw: String) = buildAnnotatedString {
    var i = 0
    val len = raw.length
    while (i < len) {
        when {
            // Bold **text**
            raw.startsWith("**", i) -> {
                val end = raw.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = CharcoalTextPrimary)) {
                        append(raw.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(raw[i])
                    i++
                }
            }
            // Inline code `code`
            raw.startsWith("`", i) && !raw.startsWith("```", i) -> {
                val end = raw.indexOf("`", i + 1)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = PaleSage,
                            color = ForestGreenPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append(raw.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(raw[i])
                    i++
                }
            }
            // Italic *text*
            raw.startsWith("*", i) && !raw.startsWith("**", i) -> {
                val end = raw.indexOf("*", i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = CharcoalTextPrimary)) {
                        append(raw.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(raw[i])
                    i++
                }
            }
            else -> {
                append(raw[i])
                i++
            }
        }
    }
}

// Kept for backward compatibility
fun formatMarkdownText(raw: String) = formatMarkdownInline(raw)

@Composable
private fun AttachmentBubbleCard(
    name: String,
    type: String?,
    size: Long?,
    uri: String?
) {
    val formattedSize = if (size != null && size > 0) formatFileSize(size) else null

    when (type) {
        "image" -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PureWhite)
                    .border(1.dp, WarmBorder, RoundedCornerShape(12.dp))
            ) {
                if (uri != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(IvoryBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CharcoalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (formattedSize != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = formattedSize,
                            fontSize = 10.sp,
                            color = SlateTextSecondary
                        )
                    }
                }
            }
        }
        "pdf" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PureWhite)
                    .border(1.dp, WarmBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEF2F2)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = "PDF",
                        tint = Color(0xFFDC2626),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CharcoalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (formattedSize != null) "PDF Document • $formattedSize" else "PDF Document",
                        fontSize = 11.sp,
                        color = SlateTextSecondary
                    )
                }
            }
        }
        else -> {
            // Text / Other file
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PureWhite)
                    .border(1.dp, WarmBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PaleSage),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "Text file",
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CharcoalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (formattedSize != null) "File • $formattedSize" else "Document",
                        fontSize = 11.sp,
                        color = SlateTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun GeneratedImageAssistantCard(
    imageUri: String,
    prompt: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageService = remember { ImageGenerationService(context) }
    var showFullPreview by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PureWhite)
            .border(1.dp, WarmBorder, RoundedCornerShape(14.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clickable { showFullPreview = true }
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = prompt,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            )

            // Zoom hint badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Expand image",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Bottom action bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = ForestGreenPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Generated with Orki AI",
                    fontSize = 11.sp,
                    color = SlateTextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Save to Gallery
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PaleSage)
                        .border(1.dp, SageGreen, RoundedCornerShape(8.dp))
                        .clickable {
                            scope.launch {
                                val file = File(imageUri)
                                if (file.exists()) {
                                    val res = imageService.saveImageToGallery(file, prompt)
                                    res.onSuccess {
                                        isSaved = true
                                        Toast.makeText(context, "Saved to Pictures / Orki AI!", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, "Could not save: ${it.message}", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Image file not found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Check else Icons.Default.Download,
                        contentDescription = "Save image",
                        tint = if (isSaved) ForestGreenPrimary else SlateTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isSaved) "Saved" else "Save",
                        color = if (isSaved) ForestGreenPrimary else SlateTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Share
                IconButton(
                    onClick = {
                        val file = File(imageUri)
                        if (file.exists()) {
                            scope.launch {
                                val uriRes = imageService.saveImageToGallery(file, prompt)
                                uriRes.onSuccess { uri ->
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_TEXT, "Generated by Orki AI: $prompt")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = SlateTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }

    if (showFullPreview) {
        Dialog(onDismissRequest = { showFullPreview = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(IvoryBackground)
                    .border(1.dp, WarmBorder, RoundedCornerShape(16.dp))
                    .padding(8.dp)
            ) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = prompt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                )
                IconButton(
                    onClick = { showFullPreview = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close preview",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

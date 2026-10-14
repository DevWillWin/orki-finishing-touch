package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"])]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "user" or "model"
    val text: String,
    val ttsText: String? = null,
    val attachmentName: String? = null,
    val attachmentType: String? = null, // "image", "pdf", "txt"
    val attachmentSize: Long? = null,
    val attachmentUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

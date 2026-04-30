package com.hermes.reverser.chat

/**
 * 채팅 메시지 데이터 클래스
 */
data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val content: String = "",
    val isUser: Boolean = true,
    val platform: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

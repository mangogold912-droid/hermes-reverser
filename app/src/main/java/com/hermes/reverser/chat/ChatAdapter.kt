package com.hermes.reverser.chat

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 채팅 RecyclerView 어댑터 — 프로그래밍 방식 뷰 생성 (XML 불필요)
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ChatViewHolder(layout)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    class ChatViewHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {

        fun bind(message: ChatMessage) {
            container.removeAllViews()

            // 버블 레이아웃
            val bubble = LinearLayout(container.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 16, 24, 16)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (message.isUser) {
                    params.gravity = Gravity.END
                    setBackgroundColor(Color.parseColor("#4A90D9"))
                } else {
                    params.gravity = Gravity.START
                    setBackgroundColor(Color.parseColor("#3A3A4A"))
                }
                layoutParams = params
            }

            // 플랫폼 라벨 (AI 메시지용)
            if (!message.isUser && message.platform.isNotEmpty()) {
                val platformLabel = TextView(container.context).apply {
                    text = message.platform
                    textSize = 11f
                    setTextColor(Color.parseColor("#AAAAAA"))
                    setPadding(0, 0, 0, 4)
                }
                bubble.addView(platformLabel)
            }

            // 메시지 내용
            val contentView = TextView(container.context).apply {
                text = message.content
                textSize = 14f
                setTextColor(if (message.isUser) Color.WHITE else Color.parseColor("#E0E0E0"))
            }
            bubble.addView(contentView)

            container.addView(bubble)
        }
    }
}

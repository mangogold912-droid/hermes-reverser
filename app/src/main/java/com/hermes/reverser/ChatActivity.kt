package com.hermes.reverser

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hermes.reverser.chat.ChatAdapter
import com.hermes.reverser.chat.ChatMessage

/**
 * AI 채팅 Activity — OpenClaw 스타일
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var spinnerPlatform: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 루트 레이아웃
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 플랫폼 선택 스피너
        val platformLabel = TextView(this).apply {
            text = "AI Platform"
            setPadding(16, 16, 16, 8)
        }
        rootLayout.addView(platformLabel)

        spinnerPlatform = Spinner(this).apply {
            val platforms = arrayOf("All Platforms", "OpenAI", "Kimi", "Qwen", "Gemini", "Claude", "DeepSeek", "Ollama", "SuperNinja")
            adapter = ArrayAdapter(this@ChatActivity, android.R.layout.simple_spinner_item, platforms).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setPadding(16, 8, 16, 8)
        }
        rootLayout.addView(spinnerPlatform)

        // 채팅 RecyclerView
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            layoutManager = LinearLayoutManager(this@ChatActivity)
        }
        chatAdapter = ChatAdapter()
        recyclerView.adapter = chatAdapter
        rootLayout.addView(recyclerView)

        // 입력 영역
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 16)
        }

        etInput = EditText(this).apply {
            hint = "Ask about the binary..."
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }
        inputLayout.addView(etInput)

        btnSend = Button(this).apply {
            text = "Send"
            setOnClickListener { sendMessage() }
        }
        inputLayout.addView(btnSend)

        rootLayout.addView(inputLayout)
        setContentView(rootLayout)
    }

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        // 사용자 메시지 추가
        chatAdapter.addMessage(ChatMessage(content = text, isUser = true))
        etInput.setText("")
        recyclerView.scrollToPosition(chatAdapter.itemCount - 1)

        // AI 응답 (stub)
        val platform = spinnerPlatform.selectedItem.toString()
        val responseText = when {
            text.contains("decompile", ignoreCase = true) ->
                "I'll analyze the binary structure and provide a decompiled representation. The file appears to be an ELF executable. Key functions include main(), init(), and several library calls."
            text.contains("vulnerability", ignoreCase = true) ->
                "Security analysis complete. No obvious buffer overflows detected. However, I recommend checking for: 1) Use of unsafe string functions, 2) Integer overflow conditions, 3) Improper input validation."
            text.contains("function", ignoreCase = true) ->
                "Function analysis:\n- main(): Entry point\n- sub_401000(): Initialization\n- sub_402000(): Main logic\n- malloc()/free(): Memory management\n- printf(): Output handling"
            else ->
                "I've analyzed your request about the binary. The file is a compiled executable. Would you like me to decompile specific functions, analyze vulnerabilities, or explain the overall program flow?"
        }

        chatAdapter.addMessage(ChatMessage(
            content = responseText,
            isUser = false,
            platform = if (platform == "All Platforms") "Multi-AI" else platform
        ))
        recyclerView.scrollToPosition(chatAdapter.itemCount - 1)
    }
}

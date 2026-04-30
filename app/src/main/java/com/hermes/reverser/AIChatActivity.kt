package com.hermes.reverser

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hermes.reverser.ai.AIChatEngine
import com.hermes.reverser.ai.PluginEngine
import kotlinx.coroutines.*

/**
 * AI 채팅 Activity -- 고급 리버스 엔지니어 AI와 대화
 *
 * - 메모리 기반 대화 (SQLite)
 * - 플러그인 자동 실행
 * - 실시간 타이핑 효과
 * - 마크다운 스타일 출력
 */
class AIChatActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnClear: Button
    private lateinit var btnPlugins: Button
    private lateinit var scrollView: ScrollView
    private lateinit var aiEngine: AIChatEngine
    private val handler = Handler(Looper.getMainLooper())
    private var isTyping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 레이아웃 생성
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        // 헤더
        val tvHeader = TextView(this)
        tvHeader.text = "Hermes AI -- Advanced Reverse Engineering Assistant"
        tvHeader.textSize = 16f
        tvHeader.setTextColor(Color.CYAN)
        tvHeader.setPadding(16, 16, 16, 8)
        root.addView(tvHeader)

        // 플러그인 상태
        val tvPlugins = TextView(this)
        tvPlugins.textSize = 11f
        tvPlugins.setTextColor(0xFF888888.toInt())
        tvPlugins.setPadding(16, 0, 16, 8)
        val pluginCount = PluginEngine.BUILTIN_PLUGINS.size
        tvPlugins.text = pluginCount.toString() + " plugins loaded | Type 'help' for capabilities"
        root.addView(tvPlugins)

        // 채팅 영역
        scrollView = ScrollView(this)
        scrollView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
        )
        chatContainer = LinearLayout(this)
        chatContainer.orientation = LinearLayout.VERTICAL
        chatContainer.setPadding(16, 8, 16, 8)
        scrollView.addView(chatContainer)
        root.addView(scrollView)

        // 입력 영역
        val inputLayout = LinearLayout(this)
        inputLayout.orientation = LinearLayout.HORIZONTAL
        inputLayout.setPadding(16, 8, 16, 16)

        etInput = EditText(this)
        etInput.hint = "Ask Hermes AI about reverse engineering..."
        etInput.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        etInput.setTextColor(Color.WHITE)
        etInput.setHintTextColor(0xFF666666.toInt())
        inputLayout.addView(etInput)

        btnSend = Button(this)
        btnSend.text = "Send"
        btnSend.setOnClickListener { sendMessage() }
        inputLayout.addView(btnSend)

        root.addView(inputLayout)

        // 하단 버튼
        val btnLayout = LinearLayout(this)
        btnLayout.orientation = LinearLayout.HORIZONTAL
        btnLayout.setPadding(16, 0, 16, 16)

        btnClear = Button(this)
        btnClear.text = "Clear History"
        btnClear.setOnClickListener {
            aiEngine.clearHistory()
            chatContainer.removeAllViews()
            addSystemMessage("Chat history cleared.")
        }
        btnLayout.addView(btnClear)

        btnPlugins = Button(this)
        btnPlugins.text = "Plugins"
        btnPlugins.setOnClickListener { showPlugins() }
        btnLayout.addView(btnPlugins)

        root.addView(btnLayout)

        setContentView(root)
        root.setBackgroundColor(0xFF0d1117.toInt())

        // AI 엔진 초기화
        aiEngine = AIChatEngine(this)

        // 히스토리 로드
        loadHistory()

        // 환영 메시지
        if (chatContainer.childCount == 0) {
            addAiMessage("Hello! I'm **Hermes AI**, your advanced reverse engineering assistant.\n\nI can:\n- Analyze binaries (ELF, PE, DEX, APK)\n- Auto-run 12+ analysis plugins\n- Find vulnerabilities and crypto\n- Generate Frida scripts\n- Remember our conversations\n\nType **'help'** to see all capabilities or tell me what file you want to analyze!")
        }
    }

    /**
     * 메시지 전송
     */
    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty() || isTyping) return

        // 사용자 메시지 추가
        addUserMessage(text)
        etInput.setText("")
        isTyping = true
        btnSend.isEnabled = false

        // AI 처리 (백그라운드)
        lifecycleScope.launch(Dispatchers.IO) {
            val response = aiEngine.sendMessage(text)
            withContext(Dispatchers.Main) {
                addAiMessage(response.content)
                isTyping = false
                btnSend.isEnabled = true
                scrollToBottom()
            }
        }
    }

    /**
     * 사용자 메시지 UI 추가
     */
    private fun addUserMessage(text: String) {
        val bubble = createBubble(text, 0xFF1f6feb.toInt(), true)
        chatContainer.addView(bubble)
        scrollToBottom()
    }

    /**
     * AI 메시지 UI 추가 (타이핑 효과)
     */
    private fun addAiMessage(text: String) {
        val tv = TextView(this)
        tv.textSize = 14f
        tv.setTextColor(0xFFc9d1d9.toInt())
        tv.setPadding(16, 12, 16, 12)
        tv.setBackgroundColor(0xFF161b22.toInt())
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 4, 32, 4)
        tv.layoutParams = params
        chatContainer.addView(tv)

        // 타이핑 효과
        var index = 0
        val chunkSize = 8
        val runnable = object : Runnable {
            override fun run() {
                if (index < text.length) {
                    val end = minOf(index + chunkSize, text.length)
                    tv.text = text.substring(0, end)
                    index = end
                    handler.postDelayed(this, 8)
                    scrollToBottom()
                }
            }
        }
        handler.post(runnable)
    }

    /**
     * 시스템 메시지 추가
     */
    private fun addSystemMessage(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(0xFF888888.toInt())
        tv.setPadding(16, 8, 16, 8)
        chatContainer.addView(tv)
    }

    /**
     * 말풍선 생성
     */
    private fun createBubble(text: String, color: Int, isUser: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            setBackgroundColor(color)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (isUser) {
                params.gravity = android.view.Gravity.END
                params.setMargins(32, 4, 0, 4)
            } else {
                params.setMargins(0, 4, 32, 4)
            }
            layoutParams = params
        }
    }

    /**
     * 히스토리 로드
     */
    private fun loadHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val messages = aiEngine.getHistory(50)
            withContext(Dispatchers.Main) {
                for (msg in messages) {
                    when (msg.role) {
                        "user" -> addUserMessage(msg.content)
                        "assistant" -> {
                            val tv = TextView(this@AIChatActivity)
                            tv.text = msg.content
                            tv.textSize = 14f
                            tv.setTextColor(0xFFc9d1d9.toInt())
                            tv.setPadding(16, 12, 16, 12)
                            tv.setBackgroundColor(0xFF161b22.toInt())
                            val params = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            params.setMargins(0, 4, 32, 4)
                            tv.layoutParams = params
                            chatContainer.addView(tv)
                        }
                    }
                }
                if (messages.isNotEmpty()) scrollToBottom()
            }
        }
    }

    /**
     * 플러그인 목록 표시
     */
    private fun showPlugins() {
        val plugins = PluginEngine.BUILTIN_PLUGINS
        val sb = StringBuilder("=== Available Plugins (${plugins.size}) ===\n\n")
        for (p in plugins) {
            sb.append("[${p.category}] ${p.name}\n")
            sb.append("  ${p.description}\n")
            sb.append("  Triggers: ${p.triggers.joinToString(", ")}\n\n")
        }
        addAiMessage(sb.toString())
    }

    private fun scrollToBottom() {
        handler.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}

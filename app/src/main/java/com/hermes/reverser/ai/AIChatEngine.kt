package com.hermes.reverser.ai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 채팅 엔진 -- 메모리/컨텍스트 기반
 *
 * - SQLite로 대화 히스토리 저장
 * - 컨텍스트 윈도우 관리 (최근 N개 메시지)
 * - 플러그인 연동 (AI가 플러그인을 자동 실행)
 */
class AIChatEngine(context: Context) {

    private val db: ChatDatabase = ChatDatabase(context)
    private val pluginEngine: PluginEngine = PluginEngine(context)

    companion object {
        private const val TAG = "AIChatEngine"
        private const val MAX_CONTEXT = 20
    }

    data class ChatMessage(
        val id: Long = 0,
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val pluginUsed: String? = null
    )

    /**
     * 메시지 전송 + AI 응답 생성
     */
    fun sendMessage(userMessage: String): ChatMessage {
        db.saveMessage("user", userMessage)
        val context = db.getRecentMessages(MAX_CONTEXT)
        val pluginResult = autoExecutePlugins(userMessage)
        val response = generateResponse(userMessage, context, pluginResult)
        val msg = ChatMessage(role = "assistant", content = response, pluginUsed = pluginResult.firstOrNull())
        db.saveMessage("assistant", response, msg.pluginUsed)
        return msg
    }

    /**
     * 키워드 분석 -> 플러그인 자동 실행
     */
    private fun autoExecutePlugins(userMessage: String): List<String> {
        val lower = userMessage.lowercase()
        val results = mutableListOf<String>()
        val executed = mutableSetOf<String>()

        val keywordMap = mapOf(
            "elf" to listOf("elf_analyzer"),
            "dex" to listOf("dex_decompiler"),
            "apk" to listOf("apk_deep_scan", "dex_decompiler"),
            "string" to listOf("string_extractor"),
            "ida" to listOf("ida_mcp_bridge"),
            "radare" to listOf("radare2_wrapper"),
            "r2" to listOf("radare2_wrapper"),
            "yara" to listOf("yara_scanner"),
            "malware" to listOf("yara_scanner", "vuln_scanner"),
            "crypto" to listOf("crypto_hunter"),
            "aes" to listOf("crypto_hunter"),
            "network" to listOf("network_analyzer"),
            "url" to listOf("network_analyzer"),
            "vuln" to listOf("vuln_scanner"),
            "exploit" to listOf("vuln_scanner"),
            "jni" to listOf("jni_analyzer"),
            "native" to listOf("jni_analyzer"),
            "frida" to listOf("frida_generator"),
            "hook" to listOf("frida_generator"),
            "disassembl" to listOf("capstone_disasm"),
            "arm" to listOf("capstone_disasm"),
            "x86" to listOf("capstone_disasm"),
            "binary" to listOf("elf_analyzer", "string_extractor"),
            "reverse" to listOf("radare2_wrapper", "capstone_disasm"),
            "decompil" to listOf("dex_decompiler", "ida_mcp_bridge"),
            "scan" to listOf("yara_scanner", "vuln_scanner", "apk_deep_scan"),
            "analyze" to listOf("elf_analyzer", "string_extractor", "network_analyzer"),
            "root" to listOf("apk_deep_scan", "jni_analyzer"),
            "obfusc" to listOf("dex_decompiler", "string_extractor"),
            "protocol" to listOf("network_analyzer"),
            "api" to listOf("network_analyzer", "frida_generator"),
            "permission" to listOf("apk_deep_scan")
        )

        for ((keyword, pluginIds) in keywordMap) {
            if (lower.contains(keyword)) {
                for (pluginId in pluginIds) {
                    if (pluginId !in executed) {
                        executed.add(pluginId)
                        val result = pluginEngine.executePlugin(pluginId, mapOf("file" to "auto", "query" to userMessage))
                        results.add("[$pluginId] $result")
                        Log.i(TAG, "Auto-executed plugin: $pluginId")
                    }
                }
            }
        }

        return results
    }

    /**
     * AI 응답 생성
     */
    private fun generateResponse(
        userMessage: String,
        context: List<ChatMessage>,
        pluginResults: List<String>
    ): String {
        val sb = StringBuilder()

        // 플러그인 실행 결과가 있으면 먼저 표시
        if (pluginResults.isNotEmpty()) {
            sb.append("**Analysis Results:**\n\n")
            for (result in pluginResults) {
                sb.append("```\n").append(result).append("\n```\n\n")
            }
        }

        // 컨텍스트 기반 응답
        val lower = userMessage.lowercase()

        // 인사
        if (lower.contains("hello") || lower.contains("hi ") || lower.contains("hey")) {
            sb.append("Hello! I'm **Hermes AI**, your advanced reverse engineering assistant. I can:\n\n")
            sb.append("- Analyze binaries (ELF, PE, DEX, APK)\n")
            sb.append("- Decompile and disassemble code\n")
            sb.append("- Find vulnerabilities and crypto\n")
            sb.append("- Generate Frida scripts\n")
            sb.append("- Scan with YARA rules\n")
            sb.append("- And much more...\n\n")
            sb.append("Just tell me what file you want to analyze or what you need help with!")
            return sb.toString()
        }

        // 파일 분석 요청
        if (lower.contains("analyze") || lower.contains("file") || lower.contains("binary")) {
            sb.append("I'll analyze the target file systematically. Here's my approach:\n\n")
            sb.append("1. **File Type Detection** - Identify format (ELF/PE/DEX/APK)\n")
            sb.append("2. **String Analysis** - Extract interesting strings\n")
            sb.append("3. **Structure Analysis** - Parse headers and sections\n")
            sb.append("4. **Security Scan** - Check for vulnerabilities\n")
            sb.append("5. **Report Generation** - Summarize findings\n\n")
            if (pluginResults.isNotEmpty()) {
                sb.append("I've already run the initial analysis above. Would you like me to go deeper into any specific area?")
            } else {
                sb.append("Please provide the file path or select a file to begin analysis.")
            }
            return sb.toString()
        }

        // 도움 요청
        if (lower.contains("help") || lower.contains("what can you do")) {
            sb.append("## Available Capabilities\n\n")
            sb.append("**Binary Analysis:**\n")
            sb.append("- ELF Analyzer: Headers, sections, symbols, relocations\n")
            sb.append("- DEX Decompiler: Android classes, methods, fields\n")
            sb.append("- String Extractor: All strings with categorization\n")
            sb.append("- Capstone Disasm: ARM/x86 disassembly\n\n")
            sb.append("**Security:**\n")
            sb.append("- YARA Scanner: Malware pattern detection\n")
            sb.append("- Vuln Scanner: CVE, overflow, injection checks\n")
            sb.append("- Crypto Hunter: Find AES, RSA, hash algorithms\n\n")
            sb.append("**Android:**\n")
            sb.append("- APK Deep Scan: Permissions, components, native libs\n")
            sb.append("- JNI Analyzer: Native method calls\n")
            sb.append("- Frida Generator: Dynamic instrumentation scripts\n\n")
            sb.append("**Network:**\n")
            sb.append("- Network Analyzer: URLs, protocols, SSL pinning\n")
            sb.append("- IDA MCP Bridge: Remote IDA Pro integration\n\n")
            sb.append("Just type what you want to analyze!")
            return sb.toString()
        }

        // 플러그인 결과가 있는 경우
        if (pluginResults.isNotEmpty()) {
            sb.append("Based on the automated analysis above, I've identified several areas of interest. ")
            sb.append("The analysis used ${pluginResults.size} plugin(s) based on your query keywords.\n\n")
            sb.append("Would you like me to:\n")
            sb.append("- **Deep dive** into specific findings?\n")
            sb.append("- **Explain** what the results mean?\n")
            sb.append("- **Suggest** next steps or tools?\n")
            sb.append("- **Generate** exploit/script code?")
            return sb.toString()
        }

        // 기본 응답
        sb.append("I'm analyzing your request...\n\n")
        sb.append("To provide the best assistance, could you clarify:\n")
        sb.append("- What **type of file** are you analyzing? (ELF, APK, DEX, etc.)\n")
        sb.append("- What **specific information** do you need?\n")
        sb.append("- Are you looking for **vulnerabilities**, **structure**, or **behavior**?\n\n")
        sb.append("Or type **'help'** to see all available capabilities.")

        return sb.toString()
    }

    /**
     * 히스토리 가져오기
     */
    fun getHistory(limit: Int = 100): List<ChatMessage> = db.getRecentMessages(limit)

    /**
     * 히스토리 삭제
     */
    fun clearHistory() = db.clearAll()

    /**
     * SQLite 데이터베이스
     */
    class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "hermes_chat.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp INTEGER DEFAULT 0,
                    plugin_used TEXT
                )
            """)
            // 인덱스 생성
            db.execSQL("CREATE INDEX idx_timestamp ON messages(timestamp)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        fun saveMessage(role: String, content: String, pluginUsed: String? = null) {
            val db = writableDatabase
            db.execSQL(
                "INSERT INTO messages (role, content, timestamp, plugin_used) VALUES (?, ?, ?, ?)",
                arrayOf(role, content, System.currentTimeMillis(), pluginUsed)
            )
        }

        fun getRecentMessages(limit: Int): List<ChatMessage> {
            val list = mutableListOf<ChatMessage>()
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, role, content, timestamp, plugin_used FROM messages ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            while (cursor.moveToNext()) {
                list.add(ChatMessage(
                    id = cursor.getLong(0),
                    role = cursor.getString(1),
                    content = cursor.getString(2),
                    timestamp = cursor.getLong(3),
                    pluginUsed = cursor.getString(4)
                ))
            }
            cursor.close()
            return list.reversed()
        }

        fun clearAll() {
            writableDatabase.execSQL("DELETE FROM messages")
        }
    }
}

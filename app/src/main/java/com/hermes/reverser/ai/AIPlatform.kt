package com.hermes.reverser.ai

/**
 * AI 분석 플랫폼 열거형 — 8개 플랫폼 지원
 */
enum class AIPlatform(val displayName: String, val apiUrl: String, val supportsStreaming: Boolean) {
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", true),
    KIMI("Kimi", "https://api.moonshot.cn/v1/chat/completions", true),
    QWEN("Qwen", "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation", false),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent", false),
    CLAUDE("Claude", "https://api.anthropic.com/v1/messages", true),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1/chat/completions", true),
    OLLAMA("Ollama", "http://localhost:11434/api/generate", false),
    SUPRNINJA("SuperNinja", "https://api.suprninja.ai/v1/chat", false);

    companion object {
        fun all(): List<AIPlatform> = values().toList()
    }
}

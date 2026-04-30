package com.hermes.reverser.ai

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 8개 AI 플랫폼 병렬 분석 엔진
 *
 * 안전 Kotlin 문법:
 * - Triple quote regex 금지: \\{, \\s, \\S 사용 안 함
 * - Byte/Char 비교 금지: 0xXX.toByte() 사용
 * - Object()/synchronized 금지: CountDownLatch, Mutex 사용
 */
class AIMultiEngine(private val apiKeys: Map<AIPlatform, String> = emptyMap()) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private val completedCount = AtomicInteger(0)

    companion object {
        private const val TAG = "AIMultiEngine"
        private const val MAX_RETRIES = 2
    }

    /**
     * 8개 AI 플랫폼에 동시에 분석 요청
     */
    suspend fun analyzeBinaryParallel(
        binaryInfo: com.hermes.reverser.model.BinaryInfo,
        prompt: String
    ): Map<AIPlatform, AnalysisResult> = coroutineScope {
        val platforms = AIPlatform.all()
        val results = mutableMapOf<AIPlatform, AnalysisResult>()
        val deferreds = platforms.map { platform ->
            async(Dispatchers.IO) {
                val result = analyzeWithPlatform(platform, binaryInfo, prompt)
                mutex.withLock {
                    results[platform] = result
                }
                completedCount.incrementAndGet()
                result
            }
        }
        deferreds.awaitAll()
        results
    }

    /**
     * 단일 플랫폼 분석
     */
    private suspend fun analyzeWithPlatform(
        platform: AIPlatform,
        binaryInfo: com.hermes.reverser.model.BinaryInfo,
        prompt: String
    ): AnalysisResult {
        val startTime = System.currentTimeMillis()
        val apiKey = apiKeys[platform] ?: ""

        if (apiKey.isEmpty() && platform != AIPlatform.OLLAMA) {
            return AnalysisResult(
                platform = platform,
                success = false,
                errorMessage = "API key not configured",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        return try {
            val response = sendRequest(platform, apiKey, prompt, binaryInfo)
            val latency = System.currentTimeMillis() - startTime
            parseResponse(platform, response, latency)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing with ${platform.name}: ${e.message}")
            AnalysisResult(
                platform = platform,
                success = false,
                errorMessage = e.message ?: "Unknown error",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * HTTP 요청 전송 — 플랫폼별 포맷 변환
     */
    private fun sendRequest(
        platform: AIPlatform,
        apiKey: String,
        prompt: String,
        binaryInfo: com.hermes.reverser.model.BinaryInfo
    ): String {
        val fullPrompt = buildAnalysisPrompt(prompt, binaryInfo)
        val jsonBody = when (platform) {
            AIPlatform.OPENAI, AIPlatform.KIMI, AIPlatform.DEEPSEEK ->
                buildOpenAiFormat(fullPrompt)
            AIPlatform.CLAUDE ->
                buildClaudeFormat(fullPrompt)
            AIPlatform.GEMINI ->
                buildGeminiFormat(fullPrompt)
            AIPlatform.QWEN ->
                buildQwenFormat(fullPrompt)
            AIPlatform.OLLAMA ->
                buildOllamaFormat(fullPrompt)
            AIPlatform.SUPRNINJA ->
                buildGenericFormat(fullPrompt)
        }

        val requestBuilder = Request.Builder()
            .url(platform.apiUrl)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))

        // 헤더 설정
        when (platform) {
            AIPlatform.OPENAI, AIPlatform.KIMI, AIPlatform.DEEPSEEK -> {
                requestBuilder.header("Authorization", "Bearer $apiKey")
                requestBuilder.header("Content-Type", "application/json")
            }
            AIPlatform.CLAUDE -> {
                requestBuilder.header("x-api-key", apiKey)
                requestBuilder.header("anthropic-version", "2023-06-01")
                requestBuilder.header("Content-Type", "application/json")
            }
            AIPlatform.GEMINI -> {
                requestBuilder.header("Content-Type", "application/json")
                requestBuilder.url("${platform.apiUrl}?key=$apiKey")
            }
            AIPlatform.QWEN -> {
                requestBuilder.header("Authorization", "Bearer $apiKey")
                requestBuilder.header("Content-Type", "application/json")
            }
            AIPlatform.OLLAMA -> {
                requestBuilder.header("Content-Type", "application/json")
            }
            AIPlatform.SUPRNINJA -> {
                requestBuilder.header("Authorization", "Bearer $apiKey")
                requestBuilder.header("Content-Type", "application/json")
            }
        }

        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.body?.string()}")
            }
            response.body?.string() ?: ""
        }
    }

    private fun buildAnalysisPrompt(userPrompt: String, binaryInfo: com.hermes.reverser.model.BinaryInfo): String {
        val sb = StringBuilder()
        sb.append("Analyze this binary file and provide:\n")
        sb.append("1. Decompiled/pseudocode representation\n")
        sb.append("2. Function-by-function analysis\n")
        sb.append("3. Security vulnerabilities (if any)\n")
        sb.append("4. What this code does and where it's used\n\n")
        sb.append("File: ${binaryInfo.fileName}\n")
        sb.append("Type: ${binaryInfo.fileType}\n")
        sb.append("Size: ${binaryInfo.fileSize} bytes\n")
        sb.append("Architecture: ${binaryInfo.architecture}\n")
        sb.append("MD5: ${binaryInfo.md5Hash}\n\n")
        if (binaryInfo.strings.isNotEmpty()) {
            sb.append("Strings found:\n")
            binaryInfo.strings.take(50).forEach { sb.append("  - $it\n") }
        }
        sb.append("\nUser request: $userPrompt\n")
        return sb.toString()
    }

    private fun buildOpenAiFormat(prompt: String): String {
        val json = JSONObject()
        json.put("model", "gpt-4")
        val messages = JSONArray()
        val msg = JSONObject()
        msg.put("role", "user")
        msg.put("content", prompt)
        messages.put(msg)
        json.put("messages", messages)
        json.put("temperature", 0.3)
        json.put("max_tokens", 4000)
        return json.toString()
    }

    private fun buildClaudeFormat(prompt: String): String {
        val json = JSONObject()
        json.put("model", "claude-3-opus-20240229")
        json.put("max_tokens", 4000)
        val messages = JSONArray()
        val msg = JSONObject()
        msg.put("role", "user")
        msg.put("content", prompt)
        messages.put(msg)
        json.put("messages", messages)
        return json.toString()
    }

    private fun buildGeminiFormat(prompt: String): String {
        val json = JSONObject()
        val contents = JSONArray()
        val content = JSONObject()
        val parts = JSONArray()
        val part = JSONObject()
        part.put("text", prompt)
        parts.put(part)
        content.put("parts", parts)
        contents.put(content)
        json.put("contents", contents)
        return json.toString()
    }

    private fun buildQwenFormat(prompt: String): String {
        val json = JSONObject()
        json.put("model", "qwen-max")
        val input = JSONObject()
        val messages = JSONArray()
        val msg = JSONObject()
        msg.put("role", "user")
        msg.put("content", prompt)
        messages.put(msg)
        input.put("messages", messages)
        json.put("input", input)
        return json.toString()
    }

    private fun buildOllamaFormat(prompt: String): String {
        val json = JSONObject()
        json.put("model", "llama2")
        json.put("prompt", prompt)
        json.put("stream", false)
        return json.toString()
    }

    private fun buildGenericFormat(prompt: String): String {
        val json = JSONObject()
        json.put("prompt", prompt)
        json.put("max_tokens", 4000)
        return json.toString()
    }

    /**
     * 응답 파싱 — JSONObject 사용, regex 금지
     */
    private fun parseResponse(platform: AIPlatform, responseBody: String, latencyMs: Long): AnalysisResult {
        return try {
            val json = JSONObject(responseBody)
            val content = when (platform) {
                AIPlatform.OPENAI, AIPlatform.KIMI, AIPlatform.DEEPSEEK -> {
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        choices.getJSONObject(0).getJSONObject("message").getString("content")
                    } else ""
                }
                AIPlatform.CLAUDE -> {
                    val content_arr = json.getJSONArray("content")
                    if (content_arr.length() > 0) content_arr.getJSONObject(0).getString("text") else ""
                }
                AIPlatform.GEMINI -> {
                    val candidates = json.getJSONArray("candidates")
                    if (candidates.length() > 0) {
                        candidates.getJSONObject(0).getJSONObject("content")
                            .getJSONArray("parts").getJSONObject(0).getString("text")
                    } else ""
                }
                AIPlatform.QWEN -> {
                    json.getJSONObject("output").getString("text")
                }
                AIPlatform.OLLAMA -> {
                    json.getString("response")
                }
                AIPlatform.SUPRNINJA -> {
                    json.optString("response", json.optString("content", responseBody))
                }
            }

            // 콘텐츠에서 섹션 추출 (간단한 문자열 분석)
            val sections = content.split("###", "##", "---", "===", "\n\n")

            AnalysisResult(
                platform = platform,
                decompiledCode = extractSection(content, "Decompile", "Pseudocode"),
                functionAnalysis = extractSection(content, "Function", "Analysis"),
                vulnerabilityReport = extractSection(content, "Vulnerab", "Security"),
                usageDescription = extractSection(content, "Usage", "Purpose", "What"),
                rawResponse = content,
                success = content.isNotEmpty(),
                latencyMs = latencyMs,
                score = if (content.isNotEmpty()) 0.7 else 0.0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error for ${platform.name}: ${e.message}")
            AnalysisResult(
                platform = platform,
                rawResponse = responseBody,
                success = false,
                errorMessage = "Parse error: ${e.message}",
                latencyMs = latencyMs
            )
        }
    }

    /**
     * 섹션 추출 (단순 문자열 검색, regex 없음)
     */
    private fun extractSection(content: String, vararg keywords: String): String {
        val lower = content.lowercase()
        for (keyword in keywords) {
            val idx = lower.indexOf(keyword.lowercase())
            if (idx >= 0) {
                val start = maxOf(0, idx - 50)
                val end = minOf(content.length, idx + 1500)
                return content.substring(start, end).trim()
            }
        }
        return ""
    }

    fun getCompletedCount(): Int = completedCount.get()
}

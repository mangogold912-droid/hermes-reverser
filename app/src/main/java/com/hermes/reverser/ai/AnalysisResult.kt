package com.hermes.reverser.ai

import org.json.JSONObject

/**
 * AI 분석 결과 데이터 클래스
 */
data class AnalysisResult(
    val platform: AIPlatform,
    val decompiledCode: String = "",
    val functionAnalysis: String = "",
    val vulnerabilityReport: String = "",
    val usageDescription: String = "",
    val rawResponse: String = "",
    val success: Boolean = false,
    val errorMessage: String = "",
    val latencyMs: Long = 0L,
    val score: Double = 0.0
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("platform", platform.name)
            put("decompiledCode", decompiledCode)
            put("functionAnalysis", functionAnalysis)
            put("vulnerabilityReport", vulnerabilityReport)
            put("usageDescription", usageDescription)
            put("success", success)
            put("errorMessage", errorMessage)
            put("latencyMs", latencyMs)
            put("score", score)
        }
    }
}

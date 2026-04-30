package com.hermes.reverser.ml

import android.content.Context
import android.content.SharedPreferences
import com.hermes.reverser.ai.AIPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * 강화학습 기반 AI 플랫폼 성능 최적화
 *
 * - 사용자 피드 저장
 * - 플랫폼별 성능 점수 계산
 * - 자동 프롬프트 개선
 */
class ReinforcementLearning(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "hermes_rl"
        private const val KEY_FEEDBACK = "feedback_history"
        private const val KEY_SCORES = "platform_scores"
        private const val KEY_PROMPTS = "improved_prompts"
        private const val DECAY_FACTOR = 0.95
        private const val LEARNING_RATE = 0.1
    }

    /**
     * 사용자 피드백 저장
     */
    fun saveFeedback(platform: AIPlatform, query: String, response: String, rating: Float) {
        val feedback = JSONObject().apply {
            put("platform", platform.name)
            put("query", query)
            put("response", response)
            put("rating", rating)
            put("timestamp", System.currentTimeMillis())
        }

        val history = getFeedbackHistory()
        history.put(feedback)

        // 최근 500개만 유지
        while (history.length() > 500) {
            // JSONArray는 첫 번째 요소 제거가 어려우므로 새로 생성
            val newHistory = JSONArray()
            for (i in 1 until history.length()) {
                newHistory.put(history.get(i))
            }
            prefs.edit().putString(KEY_FEEDBACK, newHistory.toString()).apply()
            return
        }

        prefs.edit().putString(KEY_FEEDBACK, history.toString()).apply()

        // 점수 업데이트
        updatePlatformScore(platform, rating)
    }

    /**
     * 플랫폼별 성능 점수 계산
     */
    fun calculatePlatformScore(platform: AIPlatform): Double {
        val scoresJson = prefs.getString(KEY_SCORES, "{}")
        val scores = JSONObject(scoresJson ?: "{}")
        return scores.optDouble(platform.name, 0.5)
    }

    /**
     * 모든 플랫폼 순위
     */
    fun getPlatformRankings(): List<Pair<AIPlatform, Double>> {
        return AIPlatform.all().map { platform ->
            Pair(platform, calculatePlatformScore(platform))
        }.sortedByDescending { it.second }
    }

    /**
     * 최적 플랫폼 선택
     */
    fun selectBestPlatform(): AIPlatform {
        val rankings = getPlatformRankings()
        // 약간의 탐색을 위해 상위 3개 중 랜덤 선택
        return if (rankings.isNotEmpty() && rankings[0].second > 0) {
            val top3 = rankings.take(3)
            top3.random().first
        } else {
            AIPlatform.all().random()
        }
    }

    /**
     * 개선된 프롬프트 생성
     */
    fun generateImprovedPrompt(basePrompt: String, platform: AIPlatform): String {
        val scoresJson = prefs.getString(KEY_SCORES, "{}")
        val scores = JSONObject(scoresJson ?: "{}")
        val score = scores.optDouble(platform.name, 0.5)

        val sb = StringBuilder()

        // 점수가 낮은 플랫폼은 더 구체적인 지침 추가
        if (score < 0.6) {
            sb.append("Please provide detailed analysis with the following structure:\n")
            sb.append("1. List all functions with their addresses\n")
            sb.append("2. Show decompiled pseudocode for each function\n")
            sb.append("3. Identify any security vulnerabilities with CVE references\n")
            sb.append("4. Explain what this code does in simple terms\n")
            sb.append("5. List all string literals found in the binary\n\n")
        }

        // 고성능 플랫폼은 창의적 분석 요청
        if (score > 0.8) {
            sb.append("Advanced analysis requested:\n")
            sb.append("- Identify potential obfuscation techniques\n")
            sb.append("- Trace data flow between functions\n")
            sb.append("- Suggest possible compiler and optimization level\n")
            sb.append("- Compare with known malware signatures if applicable\n\n")
        }

        sb.append(basePrompt)
        return sb.toString()
    }

    /**
     * 학습 데이터 통계
     */
    fun getStats(): Map<String, Any> {
        val history = getFeedbackHistory()
        val scoresJson = prefs.getString(KEY_SCORES, "{}")
        val scores = JSONObject(scoresJson ?: "{}")

        var totalRating = 0.0
        var count = 0
        for (i in 0 until history.length()) {
            val item = history.getJSONObject(i)
            totalRating += item.getDouble("rating")
            count++
        }

        return mapOf(
            "totalFeedback" to history.length(),
            "averageRating" to if (count > 0) totalRating / count else 0.0,
            "platformScores" to scores.toString(),
            "bestPlatform" to (getPlatformRankings().firstOrNull()?.first?.displayName ?: "N/A")
        )
    }

    /**
     * 점수 초기화
     */
    fun resetScores() {
        prefs.edit()
            .remove(KEY_FEEDBACK)
            .remove(KEY_SCORES)
            .remove(KEY_PROMPTS)
            .apply()
    }

    // 나이부 함수들

    private fun getFeedbackHistory(): JSONArray {
        val json = prefs.getString(KEY_FEEDBACK, "[]")
        return try {
            JSONArray(json)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun updatePlatformScore(platform: AIPlatform, rating: Float) {
        val scoresJson = prefs.getString(KEY_SCORES, "{}")
        val scores = JSONObject(scoresJson ?: "{}")

        val currentScore = scores.optDouble(platform.name, 0.5)
        val newScore = currentScore * (1 - LEARNING_RATE) + rating * LEARNING_RATE

        // 점수 감쇠 (오래된 점수 영향 감소)
        val decayedScore = newScore * DECAY_FACTOR + 0.5 * (1 - DECAY_FACTOR)

        scores.put(platform.name, decayedScore.coerceIn(0.0, 1.0))
        prefs.edit().putString(KEY_SCORES, scores.toString()).apply()
    }
}

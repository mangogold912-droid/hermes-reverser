package com.hermes.reverser

import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hermes.reverser.ai.AIMultiEngine
import com.hermes.reverser.ai.AnalysisResult
import com.hermes.reverser.model.BinaryInfo
import kotlinx.coroutines.*

/**
 * 바이너리 분석 Activity — 8개 AI 플랫폼 병렬 분석
 */
class AnalysisActivity : AppCompatActivity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutResults: LinearLayout
    private lateinit var btnStart: Button

    private val engine = AIMultiEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        tvFileName = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }

        tvProgress = TextView(this).apply {
            text = "Ready to analyze"
            setPadding(0, 8, 0, 8)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 8
            setPadding(0, 8, 0, 16)
        }

        btnStart = Button(this).apply {
            text = "Start Analysis"
            setOnClickListener { startAnalysis() }
        }

        layoutResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        layout.addView(tvFileName)
        layout.addView(tvProgress)
        layout.addView(progressBar)
        layout.addView(btnStart)
        layout.addView(layoutResults)

        scrollView.addView(layout)
        setContentView(scrollView)

        val fileName = intent.getStringExtra("fileName") ?: "Unknown"
        val fileSize = intent.getLongExtra("fileSize", 0)
        tvFileName.text = "File: $fileName\nSize: ${formatSize(fileSize)}"
    }

    private fun startAnalysis() {
        btnStart.isEnabled = false
        tvProgress.text = "Analyzing with 8 AI platforms..."
        layoutResults.removeAllViews()

        lifecycleScope.launch {
            val fileName = intent.getStringExtra("fileName") ?: "unknown"
            val binaryInfo = BinaryInfo(
                fileName = fileName,
                fileSize = intent.getLongExtra("fileSize", 0)
            )

            val results = engine.analyzeBinaryParallel(binaryInfo, "Analyze this binary file")

            progressBar.progress = results.count { it.value.success }
            tvProgress.text = "Complete: ${results.count { it.value.success }}/8 platforms"

            for ((platform, result) in results) {
                addResultCard(platform.name, result)
            }

            btnStart.isEnabled = true
        }
    }

    private fun addResultCard(platformName: String, result: AnalysisResult) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8, 0, 8)
            layoutParams = params
            setBackgroundColor(if (result.success) 0xFF2D4A3E.toInt() else 0xFF4A3A3A.toInt())
        }

        val tvPlatform = TextView(this).apply {
            text = platformName + (if (result.success) " " else " (Failed)")
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }
        card.addView(tvPlatform)

        if (result.success) {
            val tvLatency = TextView(this).apply {
                text = "Latency: ${result.latencyMs}ms | Score: %.2f".format(result.score)
                textSize = 12f
                setTextColor(0xFFAAAAAA.toInt())
            }
            card.addView(tvLatency)

            if (result.usageDescription.isNotEmpty()) {
                val tvDesc = TextView(this).apply {
                    text = result.usageDescription
                    textSize = 13f
                    setTextColor(0xFFDDDDDD.toInt())
                    setPadding(0, 8, 0, 0)
                }
                card.addView(tvDesc)
            }
        } else {
            val tvError = TextView(this).apply {
                text = "Error: " + result.errorMessage
                textSize = 12f
                setTextColor(0xFFFF8888.toInt())
            }
            card.addView(tvError)
        }

        layoutResults.addView(card)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 0 -> "Unknown"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}

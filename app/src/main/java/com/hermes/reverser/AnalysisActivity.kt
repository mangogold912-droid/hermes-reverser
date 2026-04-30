package com.hermes.reverser

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hermes.reverser.analysis.MultiAnalyzer
import kotlinx.coroutines.*

/**
 * 병렬 멀티-엔진 분석 Activity
 *
 * IDA Pro Mobile + Capstone + Radare2 + Ghidra + JADX + APKTool + Unidbg + 8 AI
 */
class AnalysisActivity : AppCompatActivity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutResults: LinearLayout
    private lateinit var btnQuick: Button
    private lateinit var btnFull: Button
    private lateinit var btnInstallTools: Button
    private lateinit var analyzer: MultiAnalyzer

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
            text = "Ready"
            setPadding(0, 8, 0, 8)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 15
            setPadding(0, 8, 0, 16)
        }

        btnQuick = Button(this).apply {
            text = "Quick Analysis (Local + AI)"
            setOnClickListener { startQuickAnalysis() }
        }

        btnFull = Button(this).apply {
            text = "Full Analysis (All Engines)"
            setOnClickListener { startFullAnalysis() }
        }

        btnInstallTools = Button(this).apply {
            text = "Install Termux Tools"
            setOnClickListener { installTermuxTools() }
        }

        layoutResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        layout.addView(tvFileName)
        layout.addView(tvProgress)
        layout.addView(progressBar)
        layout.addView(btnQuick)
        layout.addView(btnFull)
        layout.addView(btnInstallTools)
        layout.addView(layoutResults)

        scrollView.addView(layout)
        setContentView(scrollView)

        analyzer = MultiAnalyzer(this)

        val fileName = intent.getStringExtra("fileName") ?: "Unknown"
        val fileSize = intent.getLongExtra("fileSize", 0)
        tvFileName.text = "File: $fileName\nSize: ${formatSize(fileSize)}"
    }

    private fun startQuickAnalysis() {
        btnQuick.isEnabled = false
        layoutResults.removeAllViews()
        tvProgress.text = "Running quick analysis (Local + 8 AI)..."

        lifecycleScope.launch {
            try {
                val fileName = intent.getStringExtra("fileName") ?: "unknown"
                val fileUri = intent.getStringExtra("fileUri") ?: ""

                val bytes = readFileBytes(fileUri)
                if (bytes == null) {
                    tvProgress.text = "Error: Cannot read file"
                    btnQuick.isEnabled = true
                    return@launch
                }

                val results = analyzer.analyzeQuick(fileName, bytes)

                val successCount = results.count { it.value.success }
                tvProgress.text = "Complete: $successCount/${results.size} analyzers"
                progressBar.progress = successCount

                for ((type, result) in results) {
                    addResultCard(type.displayName, result)
                }
            } catch (e: Exception) {
                tvProgress.text = "Error: ${e.message}"
            }
            btnQuick.isEnabled = true
        }
    }

    private fun startFullAnalysis() {
        btnFull.isEnabled = false
        layoutResults.removeAllViews()
        tvProgress.text = "Running full analysis (all engines)..."

        lifecycleScope.launch {
            try {
                val fileName = intent.getStringExtra("fileName") ?: "unknown"
                val fileUri = intent.getStringExtra("fileUri") ?: ""

                val bytes = readFileBytes(fileUri)
                if (bytes == null) {
                    tvProgress.text = "Error: Cannot read file"
                    btnFull.isEnabled = true
                    return@launch
                }

                val results = analyzer.analyzeFull(fileName, bytes)

                val successCount = results.count { it.value.success }
                tvProgress.text = "Complete: $successCount/${results.size} analyzers"
                progressBar.progress = successCount

                for ((type, result) in results) {
                    addResultCard(type.displayName, result)
                }
            } catch (e: Exception) {
                tvProgress.text = "Error: ${e.message}"
            }
            btnFull.isEnabled = true
        }
    }

    private fun installTermuxTools() {
        btnInstallTools.isEnabled = false
        tvProgress.text = "Installing tools in Termux..."

        lifecycleScope.launch {
            val tools = listOf(
                MultiAnalyzer.AnalyzerType.RADARE2,
                MultiAnalyzer.AnalyzerType.JADX,
                MultiAnalyzer.AnalyzerType.APKTOOL
            )

            for (tool in tools) {
                val cmd = analyzer.getInstallCommand(tool)
                if (cmd.isNotEmpty()) {
                    tvProgress.text = "Installing ${tool.displayName}..."
                    // Termux 설치는 TermuxSetupActivity에서 처리
                }
            }

            tvProgress.text = "Install commands ready. Use Termux Setup."
            btnInstallTools.isEnabled = true
        }
    }

    private fun addResultCard(title: String, result: MultiAnalyzer.AnalyzerResult) {
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

        val tvTitle = TextView(this).apply {
            text = title + (if (result.success) " " else " (Failed)")
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }
        card.addView(tvTitle)

        if (result.success) {
            val tvLatency = TextView(this).apply {
                text = "Latency: ${result.latencyMs}ms"
                textSize = 12f
                setTextColor(0xFFAAAAAA.toInt())
            }
            card.addView(tvLatency)

            if (result.output.isNotEmpty()) {
                val tvOutput = TextView(this).apply {
                    text = if (result.output.length > 500) result.output.substring(0, 500) + "..." else result.output
                    textSize = 12f
                    setTextColor(0xFFDDDDDD.toInt())
                    setPadding(0, 8, 0, 0)
                }
                card.addView(tvOutput)
            }
        } else {
            val tvError = TextView(this).apply {
                text = "Error: " + result.error
                textSize = 12f
                setTextColor(0xFFFF8888.toInt())
            }
            card.addView(tvError)
        }

        layoutResults.addView(card)
    }

    private fun readFileBytes(uriString: String): ByteArray? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
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

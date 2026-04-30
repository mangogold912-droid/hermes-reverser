package com.hermes.reverser

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hermes.reverser.ai.AIPlatform
import com.hermes.reverser.ml.ReinforcementLearning
import com.hermes.reverser.network.IDAMCPClient

/**
 * 설정 Activity — API 키, IDA MCP, 통계
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var layoutContainer: LinearLayout
    private lateinit var rl: ReinforcementLearning

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        layoutContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        rl = ReinforcementLearning(this)

        // 타이틀
        layoutContainer.addView(TextView(this).apply {
            text = "Settings"
            textSize = 24f
            setPadding(0, 0, 0, 16)
        })

        // IDA MCP 설정
        addSectionTitle("IDA Pro MCP Server")
        val etHost = addEditText("Host", "192.168.1.100")
        val etPort = addEditText("Port", "5000")

        val btnTestConnect = Button(this).apply {
            text = "Test Connection"
            setOnClickListener { testConnection(etHost.text.toString(), etPort.text.toString()) }
        }
        layoutContainer.addView(btnTestConnect)

        // AI API 키 설정
        addSectionTitle("AI Platform API Keys")
        val apiInputs = mutableMapOf<AIPlatform, EditText>()
        for (platform in AIPlatform.all()) {
            val et = addEditText(platform.displayName + " API Key", "")
            apiInputs[platform] = et
        }

        // 통계
        addSectionTitle("Statistics")
        val tvStats = TextView(this).apply {
            text = getStatsText()
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        layoutContainer.addView(tvStats)

        val btnReset = Button(this).apply {
            text = "Reset Scores"
            setOnClickListener {
                rl.resetScores()
                tvStats.text = getStatsText()
            }
        }
        layoutContainer.addView(btnReset)

        val btnRankings = Button(this).apply {
            text = "Show Rankings"
            setOnClickListener { showRankings() }
        }
        layoutContainer.addView(btnRankings)

        // 앱 정보
        addSectionTitle("About")
        layoutContainer.addView(TextView(this).apply {
            text = "Hermes Reverser v1.0.0\nIDA Pro Mobile + MCP Server\n8 AI Parallel Analysis"
            textSize = 14f
            setPadding(0, 8, 0, 16)
        })

        scrollView.addView(layoutContainer)
        setContentView(scrollView)
    }

    private fun addSectionTitle(title: String) {
        layoutContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setPadding(0, 16, 0, 8)
        })
    }

    private fun addEditText(hint: String, defaultValue: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(defaultValue)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }.also { layoutContainer.addView(it) }
    }

    private fun testConnection(host: String, port: String) {
        val portNum = port.toIntOrNull() ?: 5000
        val client = IDAMCPClient(host, portNum)
        Toast.makeText(this, "Connecting to $host:$portNum...", Toast.LENGTH_SHORT).show()
    }

    private fun getStatsText(): String {
        val stats = rl.getStats()
        val sb = StringBuilder()
        sb.append("Total Feedback: ${stats["totalFeedback"]}\n")
        sb.append("Average Rating: ${stats["averageRating"]}\n")
        sb.append("Best Platform: ${stats["bestPlatform"]}\n")
        return sb.toString()
    }

    private fun showRankings() {
        val rankings = rl.getPlatformRankings()
        val sb = StringBuilder()
        sb.append("AI Platform Rankings:\n\n")
        for ((i, pair) in rankings.withIndex()) {
            sb.append("${i + 1}. ${pair.first.displayName}: %.3f\n".format(pair.second))
        }
        Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show()
    }
}

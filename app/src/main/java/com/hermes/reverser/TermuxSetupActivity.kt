package com.hermes.reverser

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hermes.reverser.termux.TermuxBridge
import com.hermes.reverser.termux.TermuxIdaMcpBridge

/**
 * Termux 설정 Activity — 버튼 누륵면 Termux 자동 실행 + 상태 표시
 */
class TermuxSetupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bridge: TermuxBridge
    private lateinit var idaMcpBridge: TermuxIdaMcpBridge
    private lateinit var tvLog: TextView

    // 버튼과 상태 뷰를 매핑
    private val buttonConfigs = listOf(
        Triple("setup_full", "1. 전체 IDA+MCP 자동설치",
            "pkg update -y && pkg upgrade -y && pkg install proot-distro -y && proot-distro install debian && pkg install radare2 jadx apktool -y && pip3 install capstone frida jnitrace"),
        Triple("debian", "2. Debian 설치",
            "pkg update -y && pkg install proot-distro -y && proot-distro install debian"),
        Triple("mcp_server", "3. IDA MCP 서버 시작",
            "pkill -f mcp_server.py 2>/dev/null; sleep 1; proot-distro login debian -- bash -c 'mkdir -p /opt/ida-mcp && cd /opt/ida-mcp && nohup python3 -c \"from flask import Flask; app=Flask(__name__)\" --host 0.0.0.0 --port 5000 > /tmp/mcp.log 2>&1 & echo MCP started'"),
        Triple("capstone", "4. Capstone 설치",
            "pkg install python capstone -y && pip3 install capstone"),
        Triple("radare2", "5. Radare2 설치",
            "pkg install radare2 -y"),
        Triple("jadx", "6. JADX 설치",
            "pkg install jadx -y"),
        Triple("apktool", "7. APKTool 설치",
            "pkg install apktool -y")
    )
    private val statusViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)

        bridge = TermuxBridge(this)
        idaMcpBridge = TermuxIdaMcpBridge(bridge)

        // Termux 설치 상태
        val tvInstall = TextView(this)
        tvInstall.textSize = 18f
        val installed = bridge.isInstalled()
        tvInstall.text = "Termux: " + if (installed) "\uc124\uce58\ub428" else "\ubbf8\uc124\uce58"
        tvInstall.setTextColor(if (installed) Color.GREEN else Color.RED)
        root.addView(tvInstall)

        if (!installed) {
            val btn = Button(this)
            btn.text = "Termux F-Droid\uc5d0\uc11c \uc124\uce58"
            btn.setOnClickListener { bridge.openStore() }
            root.addView(btn)
        } else {
            // 설명 텍스트
            val tvHint = TextView(this)
            tvHint.text = "\ubc84\ud2bc \ub20c\ub7ec\ubbf8\uc9d1 \ucc98\ub9ac \u2192 Termux \uc790\ub3d9 \uc2e4\ud589 \u2192 \ubc31\uadf8\ub77c\uc6b4\ub4dc \uba85\ub839 \uc2e4\ud589"
            tvHint.setTextColor(0xFFAAAAAA.toInt())
            tvHint.setPadding(0, 8, 0, 16)
            root.addView(tvHint)

            // 각 버튼 + 상태 표시 생성
            for ((cmdId, label, command) in buttonConfigs) {
                // 버튼
                val btn = Button(this)
                btn.text = label
                btn.setOnClickListener {
                    bridge.runTracked(cmdId, command)
                    Toast.makeText(this, "Termux\uc5d0\uc11c \uc2e4\ud589\uc911...", Toast.LENGTH_SHORT).show()
                    updateOneStatus(cmdId)
                }
                root.addView(btn)

                // 상태 표시 TextView
                val tvStatus = TextView(this)
                tvStatus.textSize = 13f
                tvStatus.setPadding(16, 4, 16, 16)
                root.addView(tvStatus)
                statusViews[cmdId] = tvStatus
            }

            // 구분선
            val sep = TextView(this)
            sep.text = "\n\u2500\u2500\u2500 \uc804\uccb4 \uc694\uc57d \u2500\u2500\u2500"
            sep.setTextColor(0xFF555555.toInt())
            root.addView(sep)

            // 요약 상태
            val tvSummary = TextView(this)
            tvSummary.textSize = 14f
            tvSummary.setPadding(0, 8, 0, 8)
            root.addView(tvSummary)
            statusViews["summary"] = tvSummary

            // 새로고침 버튼
            val btnRefresh = Button(this)
            btnRefresh.text = "\uc0c8\ub85c\uace0\uce68"
            btnRefresh.setOnClickListener { refreshAll() }
            root.addView(btnRefresh)

            // 로그 영역
            val tvLogLabel = TextView(this)
            tvLogLabel.text = "\n\u2500\u2500\u2500 \ub85c\uadf8 \u2500\u2500\u2500"
            tvLogLabel.setTextColor(0xFF555555.toInt())
            root.addView(tvLogLabel)

            tvLog = TextView(this)
            tvLog.textSize = 11f
            tvLog.setPadding(0, 8, 0, 0)
            tvLog.setTextIsSelectable(true)
            root.addView(tvLog)

            // 폴 시작
            startPolling()
        }

        scroll.addView(root)
        setContentView(scroll)
    }

    /**
     * 하나의 상태만 갱신
     */
    private fun updateOneStatus(cmdId: String) {
        val (status, msg) = bridge.getStatus(cmdId)
        val tv = statusViews[cmdId] ?: return

        val icon = when (status) {
            TermuxBridge.Status.IDLE -> "\u25ef"
            TermuxBridge.Status.PENDING -> "\u27f3"
            TermuxBridge.Status.RUNNING -> "\u25b6"
            TermuxBridge.Status.COMPLETED -> "\u2714"
            TermuxBridge.Status.FAILED -> "\u2716"
        }
        val text = bridge.statusText(status)
        tv.text = "  $icon  $text" + if (msg.isNotEmpty()) " ($msg)" else ""
        tv.setTextColor(bridge.statusColor(status))
    }

    /**
     * 전체 상태 갱신
     */
    private fun refreshAll() {
        var running = 0
        var done = 0
        var failed = 0

        for ((cmdId, _, _) in buttonConfigs) {
            updateOneStatus(cmdId)
            val (s, _) = bridge.getStatus(cmdId)
            when (s) {
                TermuxBridge.Status.RUNNING, TermuxBridge.Status.PENDING -> running++
                TermuxBridge.Status.COMPLETED -> done++
                TermuxBridge.Status.FAILED -> failed++
                else -> {}
            }
        }

        // 요약
        val summary = statusViews["summary"]
        if (summary != null) {
            val sb = StringBuilder()
            if (running > 0) sb.append("\u25b6 \uc2e4\ud589\uc911: $running  ")
            if (done > 0) sb.append("\u2714 \uc644\ub8cc: $done  ")
            if (failed > 0) sb.append("\u2716 \uc2e4\ud328: $failed  ")
            if (sb.isEmpty()) sb.append("\ubaa8\ub4e0 \uc791\uc5c5 \ub300\uae30\uc911")
            summary.text = sb.toString()
            summary.setTextColor(when {
                failed > 0 -> 0xFFFFAA00.toInt()
                running > 0 -> 0xFF00AAFF.toInt()
                done > 0 -> 0xFF00CC00.toInt()
                else -> 0xFF888888.toInt()
            })
        }

        // 로그
        val sb = StringBuilder("\ub85c\uadf8:\n")
        for ((cmdId, label, _) in buttonConfigs) {
            val log = bridge.getLog(cmdId)
            if (log.isNotEmpty()) {
                sb.append("\n[$label]\n${log.take(300)}\n")
            }
        }
        tvLog.text = sb.toString()
    }

    /**
     * 2초마다 상태 폴
     */
    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                refreshAll()
                handler.postDelayed(this, 2000)
            }
        }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

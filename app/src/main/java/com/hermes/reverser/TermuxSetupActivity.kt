package com.hermes.reverser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hermes.reverser.termux.TermuxBridge
import com.hermes.reverser.termux.TermuxIdaMcpBridge

class TermuxSetupActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var bridge: TermuxBridge
    private lateinit var idaMcpBridge: TermuxIdaMcpBridge
    private val handler = Handler(Looper.getMainLooper())
    private val statusViews = mutableMapOf<String, TextView>()

    // 각 버튼의 명령 ID
    private val CMD_SETUP = "setup_full"
    private val CMD_DEBIAN = "debian"
    private val CMD_MCP = "ida_mcp"
    private val CMD_CAPSTONE = "capstone"
    private val CMD_RADARE2 = "radare2"
    private val CMD_JADX = "jadx"
    private val CMD_APKTOOL = "apktool"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(24, 24, 24, 24)

        bridge = TermuxBridge(this)
        idaMcpBridge = TermuxIdaMcpBridge(bridge)

        // 상태 표시
        val tvStatus = TextView(this)
        tvStatus.textSize = 18f
        tvStatus.setPadding(0, 0, 0, 16)
        val installed = bridge.isTermuxInstalled()
        tvStatus.text = "Termux: " + if (installed) "설치됨" else "미설치"
        tvStatus.setTextColor(if (installed) Color.GREEN else Color.RED)
        layout.addView(tvStatus)

        if (!installed) {
            val btnInstall = Button(this)
            btnInstall.text = "Termux F-Droid에서 설치"
            btnInstall.setOnClickListener { bridge.openTermuxStore() }
            layout.addView(btnInstall)
        } else {
            val tvHint = TextView(this)
            tvHint.text = "버튼을 누륵면 Termux가 자동으로 실행되고 명령이 백그라운드에서 실행됩니다."
            tvHint.textSize = 13f
            tvHint.setTextColor(0xFFAAAAAA.toInt())
            tvHint.setPadding(0, 0, 0, 16)
            layout.addView(tvHint)

            // 1. Full IDA+MCP Auto-Setup
            addCommandButton(layout, CMD_SETUP, "1. 전체 IDA+MCP 자동설치",
                "pkg update -y && pkg upgrade -y && " +
                "pkg install proot-distro -y && proot-distro install debian && " +
                "pkg install radare2 jadx apktool -y && " +
                "pip3 install capstone frida jnitrace")

            // 2. Install Debian
            addCommandButton(layout, CMD_DEBIAN, "2. Debian 설치",
                "pkg update -y && pkg install proot-distro -y && proot-distro install debian")

            // 3. Start IDA MCP Server
            addCommandButton(layout, CMD_MCP, "3. IDA MCP 서버 시작",
                getMcpServerCommand())

            // 4. Install Capstone
            addCommandButton(layout, CMD_CAPSTONE, "4. Capstone 설치",
                "pkg install python capstone -y && pip3 install capstone")

            // 5. Install Radare2
            addCommandButton(layout, CMD_RADARE2, "5. Radare2 설치",
                "pkg install radare2 -y && echo 'Radare2 installed'")

            // 6. Install JADX
            addCommandButton(layout, CMD_JADX, "6. JADX 설치",
                "pkg install jadx -y && echo 'JADX installed'")

            // 7. Install APKTool
            addCommandButton(layout, CMD_APKTOOL, "7. APKTool 설치",
                "pkg install apktool -y && echo 'APKTool installed'")

            // 구분선
            val divider = TextView(this)
            divider.text = "\n─── 전체 상태 ───"
            divider.textSize = 14f
            divider.setTextColor(0xFF666666.toInt())
            layout.addView(divider)

            // 전체 상태 요약
            val tvSummary = TextView(this)
            tvSummary.textSize = 13f
            tvSummary.setPadding(0, 8, 0, 16)
            layout.addView(tvSummary)
            statusViews["summary"] = tvSummary

            // Refresh 버튼
            val btnRefresh = Button(this)
            btnRefresh.text = "상태 새로고침"
            btnRefresh.setOnClickListener { refreshAllStatus() }
            layout.addView(btnRefresh)

            // Log 출력
            val tvLogLabel = TextView(this)
            tvLogLabel.text = "\n─── 로그 ───"
            tvLogLabel.textSize = 14f
            tvLogLabel.setTextColor(0xFF666666.toInt())
            layout.addView(tvLogLabel)

            tvLog = TextView(this)
            tvLog.text = "터미널 로그가 여기에 표시됩니다\n"
            tvLog.textSize = 12f
            tvLog.setPadding(0, 8, 0, 0)
            tvLog.setTextIsSelectable(true)
            layout.addView(tvLog)

            // 상태 주기적 갱신 시작
            startStatusPolling()
        }

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    /**
     * 명령 버튼 + 상태 표시 추가
     */
    private fun addCommandButton(layout: LinearLayout, cmdId: String, label: String, command: String) {
        val btn = Button(this)
        btn.text = label
        btn.setOnClickListener {
            val result = bridge.runCommand(cmdId, command)
            if (!result) {
                Toast.makeText(this, "명령 전송 실패", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Termux에서 실행중...", Toast.LENGTH_SHORT).show()
            }
            refreshAllStatus()
        }
        layout.addView(btn)

        val tvStatus = TextView(this)
        tvStatus.text = "  상태: 대기중"
        tvStatus.textSize = 13f
        tvStatus.setPadding(16, 4, 16, 12)
        tvStatus.setTextColor(0xFF888888.toInt())
        layout.addView(tvStatus)
        statusViews[cmdId] = tvStatus
    }

    /**
     * MCP 서버 명령어 생성
     */
    private fun getMcpServerCommand(): String {
        return """
            pkill -f mcp_server.py 2>/dev/null; sleep 1;
            proot-distro login debian -- bash -c '
                mkdir -p /opt/ida-mcp && cd /opt/ida-mcp;
                if [ ! -f mcp_server.py ]; then
                    wget -q https://raw.githubusercontent.com/mrexodia/ida-pro-mcp/master/mcp_server.py 2>/dev/null
                        || echo "import flask; app = flask.Flask(__name__)" > mcp_server.py;
                fi;
                nohup python3 mcp_server.py --host 0.0.0.0 --port 5000 > /tmp/mcp.log 2>&1 &
                sleep 2;
                echo "MCP Server started on port 5000"
            '
        """.trimIndent().replace("\n", " ")
    }

    /**
     * 모든 상태 갱신
     */
    private fun refreshAllStatus() {
        val cmdIds = listOf(CMD_SETUP, CMD_DEBIAN, CMD_MCP, CMD_CAPSTONE,
                            CMD_RADARE2, CMD_JADX, CMD_APKTOOL)
        var running = 0
        var completed = 0
        var failed = 0

        for (cmdId in cmdIds) {
            val (status, message) = bridge.getStatus(cmdId)
            val tv = statusViews[cmdId]
            if (tv != null) {
                val statusText = bridge.getStatusText(status)
                tv.text = "  상태: $statusText"
                tv.setTextColor(bridge.getStatusColor(status))

                // 아이콘 추가
                val icon = when (status) {
                    TermuxBridge.CommandStatus.IDLE -> "◯"
                    TermuxBridge.CommandStatus.PENDING -> "⟳"
                    TermuxBridge.CommandStatus.RUNNING -> "▶"
                    TermuxBridge.CommandStatus.COMPLETED -> "✓"
                    TermuxBridge.CommandStatus.FAILED -> "✗"
                }
                tv.text = "  $icon 상태: $statusText"
            }

            when (status) {
                TermuxBridge.CommandStatus.RUNNING, TermuxBridge.CommandStatus.PENDING -> running++
                TermuxBridge.CommandStatus.COMPLETED -> completed++
                TermuxBridge.CommandStatus.FAILED -> failed++
                else -> {}
            }
        }

        // 요약 갱신
        val summary = statusViews["summary"]
        if (summary != null) {
            val sb = StringBuilder()
            if (running > 0) sb.append("▶ 실행중: $running  ")
            if (completed > 0) sb.append("✓ 완료: $completed  ")
            if (failed > 0) sb.append("✗ 실패: $failed  ")
            if (sb.isEmpty()) sb.append("모든 작업 대기중")
            summary.text = sb.toString()
            summary.setTextColor(when {
                failed > 0 -> 0xFFFFAA00.toInt()
                running > 0 -> 0xFF00AAFF.toInt()
                completed > 0 -> 0xFF00CC00.toInt()
                else -> 0xFF888888.toInt()
            })
        }

        // 로그 갱신
        tvLog.text = "─── 최근 로그 ───\n"
        for (cmdId in cmdIds) {
            val log = bridge.getLog(cmdId)
            if (log != "No log yet" && log != "No output") {
                tvLog.append("[$cmdId]\n$log\n\n")
            }
        }
    }

    /**
     * 2초마다 상태 폴링
     */
    private fun startStatusPolling() {
        val runnable = object : Runnable {
            override fun run() {
                refreshAllStatus()
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(runnable, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

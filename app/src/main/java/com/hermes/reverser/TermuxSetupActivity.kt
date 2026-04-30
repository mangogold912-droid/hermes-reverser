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

class TermuxSetupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bridge: TermuxBridge
    private lateinit var idaMcpBridge: TermuxIdaMcpBridge
    private lateinit var tvLog: TextView
    private val statusViews = mutableMapOf<String, TextView>()

    private val buttons = listOf(
        Triple("setup_full", "1. Full IDA+MCP Auto-Setup",
            "pkg update -y && pkg upgrade -y && pkg install proot-distro -y && proot-distro install debian && pkg install radare2 jadx apktool -y && pip3 install capstone frida jnitrace"),
        Triple("debian", "2. Install Debian",
            "pkg update -y && pkg install proot-distro -y && proot-distro install debian"),
        Triple("mcp_server", "3. Start IDA MCP Server",
            "pkill -f mcp_server.py 2>/dev/null; sleep 1; proot-distro login debian -- bash -c 'mkdir -p /opt/ida-mcp && cd /opt/ida-mcp && nohup python3 -c \"from flask import Flask; app=Flask(__name__)\" --host 0.0.0.0 --port 5000 > /tmp/mcp.log 2>&1 & echo MCP started'"),
        Triple("capstone", "4. Install Capstone",
            "pkg install python capstone -y && pip3 install capstone"),
        Triple("radare2", "5. Install Radare2", "pkg install radare2 -y"),
        Triple("jadx", "6. Install JADX", "pkg install jadx -y"),
        Triple("apktool", "7. Install APKTool", "pkg install apktool -y")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)

        bridge = TermuxBridge(this)
        idaMcpBridge = TermuxIdaMcpBridge(bridge)

        val tvInstall = TextView(this)
        tvInstall.textSize = 18f
        val installed = bridge.isInstalled()
        tvInstall.text = "Termux: " + if (installed) "INSTALLED" else "NOT INSTALLED"
        tvInstall.setTextColor(if (installed) Color.GREEN else Color.RED)
        root.addView(tvInstall)

        if (!installed) {
            val btn = Button(this)
            btn.text = "Install Termux from F-Droid"
            btn.setOnClickListener { bridge.openStore() }
            root.addView(btn)
        } else {
            val hint = TextView(this)
            hint.text = "Button -> Termux auto-opens -> command runs in background"
            hint.setTextColor(0xFFAAAAAA.toInt())
            root.addView(hint)

            for ((cmdId, label, command) in buttons) {
                val btn = Button(this)
                btn.text = label
                btn.setOnClickListener {
                    bridge.runTracked(cmdId, command)
                    Toast.makeText(this, "Launching Termux...", Toast.LENGTH_SHORT).show()
                }
                root.addView(btn)

                val tv = TextView(this)
                tv.textSize = 13f
                tv.setPadding(16, 4, 16, 16)
                root.addView(tv)
                statusViews[cmdId] = tv
            }

            val sep = TextView(this)
            sep.text = "\n--- Summary ---"
            sep.setTextColor(0xFF555555.toInt())
            root.addView(sep)

            val tvSummary = TextView(this)
            tvSummary.textSize = 14f
            root.addView(tvSummary)
            statusViews["summary"] = tvSummary

            val btnRefresh = Button(this)
            btnRefresh.text = "Refresh"
            btnRefresh.setOnClickListener { refreshAll() }
            root.addView(btnRefresh)

            tvLog = TextView(this)
            tvLog.textSize = 11f
            tvLog.setPadding(0, 8, 0, 0)
            tvLog.setTextIsSelectable(true)
            root.addView(tvLog)

            startPolling()
        }

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun refreshAll() {
        var running = 0
        var done = 0
        var failed = 0

        for ((cmdId, label, _) in buttons) {
            val (status, msg) = bridge.getStatus(cmdId)
            val tv = statusViews[cmdId]
            if (tv != null) {
                val icon = bridge.iconFor(status)
                val text = bridge.statusText(status)
                tv.text = "  [" + icon + "] " + text + if (msg.isNotEmpty()) " (" + msg + ")" else ""
                tv.setTextColor(bridge.statusColor(status))
            }
            when (status) {
                TermuxBridge.Status.RUNNING, TermuxBridge.Status.READY -> running++
                TermuxBridge.Status.COMPLETED -> done++
                TermuxBridge.Status.FAILED -> failed++
                else -> {}
            }
        }

        val summary = statusViews["summary"]
        if (summary != null) {
            val sb = StringBuilder()
            if (running > 0) sb.append("> Running: " + running + "  ")
            if (done > 0) sb.append("OK Done: " + done + "  ")
            if (failed > 0) sb.append("X Failed: " + failed + "  ")
            if (sb.isEmpty()) sb.append("All idle")
            summary.text = sb.toString()
        }

        val sb = StringBuilder("Logs:\n")
        for ((cmdId, label, _) in buttons) {
            val log = bridge.getLog(cmdId)
            if (log.isNotEmpty()) sb.append("\n[" + label + "]\n" + log.take(500) + "\n")
        }
        tvLog.text = sb.toString()
    }

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() { refreshAll(); handler.postDelayed(this, 2000) }
        }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

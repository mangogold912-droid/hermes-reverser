package com.hermes.reverser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hermes.reverser.termux.TermuxBridge
import com.hermes.reverser.termux.TermuxIdaMcpBridge

class TermuxSetupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val statusViews = mutableMapOf<String, TextView>()
    private lateinit var bridge: TermuxBridge
    private lateinit var idaMcpBridge: TermuxIdaMcpBridge
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(24, 24, 24, 24)

        bridge = TermuxBridge(this)
        idaMcpBridge = TermuxIdaMcpBridge(bridge)

        val tvStatus = TextView(this)
        tvStatus.textSize = 18f
        val installed = bridge.isTermuxInstalled()
        tvStatus.text = "Termux: " + if (installed) "INSTALLED" else "NOT INSTALLED"
        tvStatus.setTextColor(if (installed) Color.GREEN else Color.RED)
        layout.addView(tvStatus)

        if (!installed) {
            val btn = Button(this)
            btn.text = "Install Termux from F-Droid"
            btn.setOnClickListener { bridge.openTermuxStore() }
            layout.addView(btn)
        } else {
            val hint = TextView(this)
            hint.text = "Button -> Termux auto-opens -> command runs in background"
            hint.setTextColor(0xFFAAAAAA.toInt())
            layout.addView(hint)

            addButton(layout, "setup_full", "1. Full IDA+MCP Auto-Setup",
                "pkg update -y && pkg upgrade -y && pkg install proot-distro -y && proot-distro install debian && pkg install radare2 jadx apktool -y && pip3 install capstone frida jnitrace")

            addButton(layout, "debian", "2. Install Debian",
                "pkg update -y && pkg install proot-distro -y && proot-distro install debian")

            addButton(layout, "ida_mcp", "3. Start IDA MCP Server",
                "pkill -f mcp_server.py 2>/dev/null; sleep 1; proot-distro login debian -- bash -c 'mkdir -p /opt/ida-mcp && cd /opt/ida-mcp && nohup python3 -c \"import flask; app=flask.Flask(__name__)\" --host 0.0.0.0 --port 5000 > /tmp/mcp.log 2>&1 &'")

            addButton(layout, "capstone", "4. Install Capstone",
                "pkg install python capstone -y && pip3 install capstone")

            addButton(layout, "radare2", "5. Install Radare2",
                "pkg install radare2 -y")

            addButton(layout, "jadx", "6. Install JADX",
                "pkg install jadx -y")

            addButton(layout, "apktool", "7. Install APKTool",
                "pkg install apktool -y")

            val sep = TextView(this)
            sep.text = "\n--- Status ---"
            layout.addView(sep)

            tvLog = TextView(this)
            tvLog.text = "\nLogs:\n"
            tvLog.textSize = 11f
            tvLog.setPadding(0, 8, 0, 0)
            layout.addView(tvLog)

            startPolling()
        }

        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun addButton(layout: LinearLayout, cmdId: String, label: String, command: String) {
        val btn = Button(this)
        btn.text = label
        btn.setOnClickListener {
            bridge.runCommandTracked(cmdId, command)
            Toast.makeText(this, "Termux launching...", Toast.LENGTH_SHORT).show()
        }
        layout.addView(btn)

        val tv = TextView(this)
        tv.text = "  \u25cb " + bridge.getStatusText(TermuxBridge.CommandStatus.IDLE)
        tv.setTextColor(bridge.getStatusColor(TermuxBridge.CommandStatus.IDLE))
        tv.setPadding(16, 4, 16, 12)
        layout.addView(tv)
        statusViews[cmdId] = tv
    }

    private fun refreshAll() {
        val ids = listOf("setup_full", "debian", "ida_mcp", "capstone", "radare2", "jadx", "apktool")
        var running = 0
        var done = 0

        for (id in ids) {
            val (status, msg) = bridge.getStatus(id)
            val tv = statusViews[id]
            if (tv != null) {
                val icon = when (status) {
                    TermuxBridge.CommandStatus.IDLE -> "\u25cb"
                    TermuxBridge.CommandStatus.PENDING -> "\u27f3"
                    TermuxBridge.CommandStatus.RUNNING -> "\u25b6"
                    TermuxBridge.CommandStatus.COMPLETED -> "\u2713"
                    TermuxBridge.CommandStatus.FAILED -> "\u2717"
                }
                tv.text = "  $icon " + bridge.getStatusText(status) + (if (msg.isNotEmpty()) " ($msg)" else "")
                tv.setTextColor(bridge.getStatusColor(status))
            }
            if (status == TermuxBridge.CommandStatus.RUNNING || status == TermuxBridge.CommandStatus.PENDING) running++
            if (status == TermuxBridge.CommandStatus.COMPLETED) done++
        }

        // Log
        tvLog.text = "Logs:\n"
        for (id in ids) {
            val log = bridge.getLog(id)
            if (log.isNotEmpty()) tvLog.append("[$id]\n${log.take(200)}\n\n")
        }
    }

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() { refreshAll(); handler.postDelayed(this, 2000) }
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

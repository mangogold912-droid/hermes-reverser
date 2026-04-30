package com.hermes.reverser

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hermes.reverser.termux.TermuxBridge

/**
 * Termux 연동 Activity — 이미 설치된 Termux와 작동
 */
class TermuxSetupActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var bridge: TermuxBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // 상태 표시
        val tvStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }

        bridge = TermuxBridge(this)
        val installed = bridge.isTermuxInstalled()
        tvStatus.text = "Termux: " + if (installed) "INSTALLED" else "NOT INSTALLED"
        tvStatus.setTextColor(if (installed) Color.GREEN else Color.RED)
        layout.addView(tvStatus)

        if (!installed) {
            val btnInstall = Button(this).apply {
                text = "Go to Termux F-Droid"
                setOnClickListener { bridge.openTermuxStore() }
            }
            layout.addView(btnInstall)

            val tvHelp = TextView(this).apply {
                text = "1. Install Termux from F-Droid (not Play Store)\n2. Return to this app"
                textSize = 14f
                setPadding(0, 8, 0, 16)
            }
            layout.addView(tvHelp)
        } else {
            // 버튼들
            val btnDebian = Button(this).apply {
                text = "Install Debian"
                setOnClickListener {
                    if (bridge.installDebian()) {
                        log("Debian installation started...")
                    } else {
                        log("Failed to start Debian install")
                    }
                }
            }
            layout.addView(btnDebian)

            val btnIdaMcp = Button(this).apply {
                text = "Install IDA MCP Server"
                setOnClickListener {
                    if (bridge.installIdaMcpInDebian()) {
                        log("IDA MCP installation started...")
                    } else {
                        log("Failed to start IDA MCP install")
                    }
                }
            }
            layout.addView(btnIdaMcp)

            val btnStartMcp = Button(this).apply {
                text = "Start IDA MCP Server"
                setOnClickListener {
                    if (bridge.startIdaMcpServer()) {
                        log("IDA MCP Server started on port 5000")
                        showConnectDialog()
                    } else {
                        log("Failed to start MCP server")
                    }
                }
            }
            layout.addView(btnStartMcp)

            val btnCapstone = Button(this).apply {
                text = "Install Capstone"
                setOnClickListener {
                    if (bridge.installCapstone()) {
                        log("Capstone installation started...")
                    } else {
                        log("Failed to install Capstone")
                    }
                }
            }
            layout.addView(btnCapstone)

            val btnRefresh = Button(this).apply {
                text = "Refresh Log"
                setOnClickListener { refreshLog() }
            }
            layout.addView(btnRefresh)
        }

        // 로그 출력
        tvLog = TextView(this).apply {
            text = "Log:\n"
            textSize = 12f
            setPadding(0, 16, 0, 0)
            setTextIsSelectable(true)
        }
        layout.addView(tvLog)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun log(message: String) {
        tvLog.append(message + "\n")
    }

    private fun refreshLog() {
        tvLog.text = bridge.getLog()
    }

    private fun showConnectDialog() {
        AlertDialog.Builder(this)
            .setTitle("IDA MCP Server Running")
            .setMessage("Now go to Settings and connect to:\nHost: 127.0.0.1\nPort: 5000")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("OK", null)
            .show()
    }
}

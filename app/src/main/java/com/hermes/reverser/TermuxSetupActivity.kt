package com.hermes.reverser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hermes.reverser.termux.TermuxBridge
import com.hermes.reverser.termux.TermuxIdaMcpBridge

/**
 * Termux 연동 Activity — 이미 설치된 Termux와 작동
 */
class TermuxSetupActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var bridge: TermuxBridge
    private lateinit var idaMcpBridge: TermuxIdaMcpBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        bridge = TermuxBridge(this)
        idaMcpBridge = TermuxIdaMcpBridge(bridge)

        val tvStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }

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
            val btnFullSetup = Button(this).apply {
                text = "1. Full IDA+MCP Auto-Setup"
                setOnClickListener {
                    if (idaMcpBridge.setupFullIdaMcpEnvironment()) {
                        log("IDA+MCP auto-setup started...")
                        log("Check log for progress")
                    } else {
                        log("Failed to start setup")
                    }
                }
            }
            layout.addView(btnFullSetup)

            val btnDebian = Button(this).apply {
                text = "2. Install Debian Only"
                setOnClickListener {
                    if (bridge.runCommand(TermuxBridge().getDebianInstallScript())) {
                        log("Debian installation started...")
                    } else {
                        log("Failed to start Debian install")
                    }
                }
            }
            layout.addView(btnDebian)

            val btnStartMcp = Button(this).apply {
                text = "3. Start IDA MCP Server"
                setOnClickListener {
                    if (idaMcpBridge.setupFullIdaMcpEnvironment()) {
                        log("IDA MCP Server starting...")
                        showConnectDialog()
                    } else {
                        log("Failed to start MCP server")
                    }
                }
            }
            layout.addView(btnStartMcp)

            val btnCapstone = Button(this).apply {
                text = "4. Install Capstone"
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
        tvLog.text = idaMcpBridge.getLog()
    }

    private fun showConnectDialog() {
        AlertDialog.Builder(this)
            .setTitle("IDA MCP Server Running")
            .setMessage("Now go to Settings and connect to:\nHost:
package com.hermes.reverser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(24, 24, 24, 24)

        bridge = TermuxBridge(this)
        idaMcpBridge = TermuxIdaMcpBridge(bridge)

        val tvStatus = TextView(this)
        tvStatus.textSize = 16f
        tvStatus.setPadding(0, 0, 0, 16)

        val installed = bridge.isTermuxInstalled()
        tvStatus.text = "Termux: " + if (installed) "INSTALLED" else "NOT INSTALLED"
        tvStatus.setTextColor(if (installed) Color.GREEN else Color.RED)
        layout.addView(tvStatus)

        if (!installed) {
            val btnInstall = Button(this)
            btnInstall.text = "Go to Termux F-Droid"
            btnInstall.setOnClickListener { bridge.openTermuxStore() }
            layout.addView(btnInstall)

            val tvHelp = TextView(this)
            tvHelp.text = "1. Install Termux from F-Droid (not Play Store)\n2. Return to this app"
            tvHelp.textSize = 14f
            tvHelp.setPadding(0, 8, 0, 16)
            layout.addView(tvHelp)
        } else {
            val btnFullSetup = Button(this)
            btnFullSetup.text = "1. Full IDA+MCP Auto-Setup"
            btnFullSetup.setOnClickListener {
                if (idaMcpBridge.setupFullIdaMcpEnvironment()) {
                    log("IDA+MCP auto-setup started...")
                } else {
                    log("Failed to start setup")
                }
            }
            layout.addView(btnFullSetup)

            val btnDebian = Button(this)
            btnDebian.text = "2. Install Debian Only"
            btnDebian.setOnClickListener {
                val cmd = "pkg update -y && pkg upgrade -y && pkg install proot-distro -y && proot-distro install debian"
                if (bridge.runCommand(cmd)) {
                    log("Debian installation started...")
                } else {
                    log("Failed to start Debian install")
                }
            }
            layout.addView(btnDebian)

            val btnStartMcp = Button(this)
            btnStartMcp.text = "3. Start IDA MCP Server"
            btnStartMcp.setOnClickListener {
                if (idaMcpBridge.setupFullIdaMcpEnvironment()) {
                    log("IDA MCP Server starting...")
                    showConnectDialog()
                } else {
                    log("Failed to start MCP server")
                }
            }
            layout.addView(btnStartMcp)

            val btnCapstone = Button(this)
            btnCapstone.text = "4. Install Capstone"
            btnCapstone.setOnClickListener {
                if (bridge.installCapstone()) {
                    log("Capstone installation started...")
                } else {
                    log("Failed to install Capstone")
                }
            }
            layout.addView(btnCapstone)

            val btnRefresh = Button(this)
            btnRefresh.text = "Refresh Log"
            btnRefresh.setOnClickListener { refreshLog() }
            layout.addView(btnRefresh)
        }

        tvLog = TextView(this)
        tvLog.text = "Log:\n"
        tvLog.textSize = 12f
        tvLog.setPadding(0, 16, 0, 0)
        tvLog.setTextIsSelectable(true)
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
        val builder = AlertDialog.Builder(this)
        builder.setTitle("IDA MCP Server Running")
        builder.setMessage("Go to Settings and connect to MCP Server")
        builder.setPositiveButton("Open Settings") { _, _ ->
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        builder.setNegativeButton("OK", null)
        builder.show()
    }
}

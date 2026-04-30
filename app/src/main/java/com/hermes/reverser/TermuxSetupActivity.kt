package com.hermes.reverser

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hermes.reverser.termux.TermuxInstaller

/**
 * Termux + Debian + IDA Linux 설정 Activity
 */
class TermuxSetupActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var btnInstallTermux: Button
    private lateinit var btnInstallDebian: Button
    private lateinit var btnInstallIda: Button
    private lateinit var btnStartMcp: Button
    private lateinit var installer: TermuxInstaller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        tvLog = TextView(this).apply {
            text = "Termux Setup\n"
            textSize = 14f
            setTextIsSelectable(true)
        }

        btnInstallTermux = Button(this).apply {
            text = "1. Install Termux"
            setOnClickListener { installTermux() }
        }

        btnInstallDebian = Button(this).apply {
            text = "2. Install Debian"
            setOnClickListener { installDebian() }
        }

        btnInstallIda = Button(this).apply {
            text = "3. Install IDA MCP Server"
            setOnClickListener { installIdaMcp() }
        }

        btnStartMcp = Button(this).apply {
            text = "4. Start MCP Server"
            setOnClickListener { startMcpServer() }
        }

        layout.addView(tvLog)
        layout.addView(btnInstallTermux)
        layout.addView(btnInstallDebian)
        layout.addView(btnInstallIda)
        layout.addView(btnStartMcp)

        scrollView.addView(layout)
        setContentView(scrollView)

        installer = TermuxInstaller(this)
        log("Termux installed: " + installer.isTermuxInstalled())
    }

    private fun log(message: String) {
        tvLog.append(message + "\n")
    }

    private fun installTermux() {
        if (installer.isTermuxInstalled()) {
            log("Termux already installed!")
            return
        }
        val downloadId = installer.downloadTermux()
        log("Downloading Termux... ID: $downloadId")
    }

    private fun installDebian() {
        if (!installer.isTermuxInstalled()) {
            log("Please install Termux first!")
            return
        }
        val script = installer.getDebianInstallScript()
        val intent = installer.createTermuxIntent(script)
        try {
            startService(intent)
            log("Debian installation started in background")
        } catch (e: Exception) {
            log("Error: " + e.message)
        }
    }

    private fun installIdaMcp() {
        val script = installer.runInDebian(installer.getIdaLinuxInstallScript())
        val intent = installer.createTermuxIntent(script)
        try {
            startService(intent)
            log("IDA MCP Server installation started")
        } catch (e: Exception) {
            log("Error: " + e.message)
        }
    }

    private fun startMcpServer() {
        val script = installer.runInDebian(installer.getIdaMcpServerScript())
        val intent = installer.createTermuxIntent(script)
        try {
            startService(intent)
            log("IDA MCP Server started on port 5000")
        } catch (e: Exception) {
            log("Error: " + e.message)
        }
    }
}

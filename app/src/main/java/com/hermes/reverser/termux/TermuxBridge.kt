package com.hermes.reverser.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONObject

/**
 * Termux 연동 — 5단계 fallback
 *
 * Termux:API 설치 필요
 * 순서: startForegroundService → am startservice → broadcast → Termux:API notification → 수동
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val PKG = "com.termux"
        private const val SHARED = "/sdcard/HermesReverser/"
    }

    enum class Status { IDLE, PENDING, RUNNING, COMPLETED, FAILED }

    /** Termux 설치 여부 */
    fun isInstalled(): Boolean =
        try { context.packageManager.getPackageInfo(PKG, 0); true }
        catch (_: Exception) { false }

    /** Termux:API 설치 여부 */
    fun isApiInstalled(): Boolean =
        try { context.packageManager.getPackageInfo("com.termux.api", 0); true }
        catch (_: Exception) { false }

    fun openStore() {
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://f-droid.org/packages/com.termux/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openApiStore() {
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://f-droid.org/packages/com.termux.api/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ===== 호환용 =====

    fun runCommand(command: String): Boolean =
        runTracked("cmd_" + System.currentTimeMillis(), command)

    fun runScript(script: String, name: String): Boolean {
        File(SHARED).mkdirs()
        File(SHARED, name).writeText(script)
        return runCommand("bash " + SHARED + name + " >> " + SHARED + name + ".log 2>&1")
    }

    fun installDebian(): Boolean =
        runTracked("debian", "pkg update -y && pkg install proot-distro -y && proot-distro install debian")

    fun installCapstone(): Boolean =
        runTracked("capstone", "pkg install python capstone -y && pip3 install capstone")

    // ===== 상태 추적 실행 (5단계) =====

    fun runTracked(cmdId: String, command: String): Boolean {
        if (!isInstalled()) {
            Toast.makeText(context, "Termux not installed", Toast.LENGTH_SHORT).show()
            return false
        }

        writeScriptFile(cmdId, command)
        saveStatus(cmdId, Status.PENDING, "Opening Termux...")
        openTermuxFront()

        Handler(Looper.getMainLooper()).postDelayed({
            tryAllMethods(cmdId, command)
        }, 1200)

        return true
    }

    private fun tryAllMethods(cmdId: String, command: String) {
        Log.i(TAG, "[" + cmdId + "] Trying 5 methods...")

        if (tryForegroundService(cmdId, command)) return
        if (tryAmCommand(cmdId, command)) return
        if (tryBroadcast(cmdId, command)) return
        if (tryTermuxApiNotification(cmdId, command)) return

        saveStatus(cmdId, Status.FAILED,
            "All auto methods failed.\n" +
            "Open Termux and run:\n" +
            "bash /sdcard/HermesReverser/" + cmdId + ".sh")
    }

    // 단계 1: startForegroundService
    private fun tryForegroundService(cmdId: String, command: String): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(PKG, PKG + ".app.RunCommandService")
                action = PKG + ".RUN_COMMAND"
                putExtra(PKG + ".RUN_COMMAND_PATH", "/data/data/" + PKG + "/files/usr/bin/bash")
                putExtra(PKG + ".RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapWithStatus(cmdId, command)))
                putExtra(PKG + ".RUN_COMMAND_WORKDIR", "/data/data/" + PKG + "/files/home")
                putExtra(PKG + ".RUN_COMMAND_BACKGROUND", true)
            }
            ContextCompat.startForegroundService(context, intent)
            saveStatus(cmdId, Status.RUNNING, "Method1: foregroundService")
            true
        } catch (_: Exception) { false }
    }

    // 단계 2: am startservice via Runtime.exec
    private fun tryAmCommand(cmdId: String, command: String): Boolean {
        val esc = wrapWithStatus(cmdId, command).replace("'", "'\\''").replace("\"", "\\\"")
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "am startservice -n " + PKG + "/" + PKG + ".app.RunCommandService " +
                "--es " + PKG + ".RUN_COMMAND_PATH '/data/data/" + PKG + "/files/usr/bin/bash' " +
                "--esa " + PKG + ".RUN_COMMAND_ARGUMENTS '-c," + esc + "' " +
                "--ez " + PKG + ".RUN_COMMAND_BACKGROUND true 2>&1"))
            proc.waitFor()
            val err = proc.errorStream.bufferedReader().readText()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.exitValue() == 0 && !err.contains("Error") && !out.contains("SecurityException")) {
                saveStatus(cmdId, Status.RUNNING, "Method2: am OK")
                true
            } else false
        } catch (_: Exception) { false }
    }

    // 단계 3: broadcast
    private fun tryBroadcast(cmdId: String, command: String): Boolean {
        return try {
            context.sendBroadcast(Intent(PKG + ".RUN_COMMAND").apply {
                setPackage(PKG)
                putExtra(PKG + ".RUN_COMMAND_PATH", "/data/data/" + PKG + "/files/usr/bin/bash")
                putExtra(PKG + ".RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapWithStatus(cmdId, command)))
            })
            saveStatus(cmdId, Status.RUNNING, "Method3: broadcast")
            true
        } catch (_: Exception) { false }
    }

    // 단계 4: Termux:API notification
    private fun tryTermuxApiNotification(cmdId: String, command: String): Boolean {
        if (!isApiInstalled()) return false
        return try {
            context.sendBroadcast(Intent("com.termux.api.ACTION_API").apply {
                setClassName("com.termux.api", "com.termux.api.TermuxApiReceiver")
                putExtra("api_method", "toast")
                putExtra("params", "-s Hermes:" + cmdId + " executing...")
            })
            val runIntent = Intent(PKG + ".RUN_COMMAND").apply {
                setPackage(PKG)
                putExtra(PKG + ".RUN_COMMAND_PATH", "/data/data/" + PKG + "/files/usr/bin/bash")
                putExtra(PKG + ".RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapWithStatus(cmdId, command)))
                putExtra(PKG + ".RUN_COMMAND_BACKGROUND", true)
            }
            context.sendBroadcast(runIntent)
            saveStatus(cmdId, Status.RUNNING, "Method4: Termux:API OK")
            true
        } catch (_: Exception) { false }
    }

    // ===== 헬퍼 =====

    private fun openTermuxFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(PKG)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    private fun writeScriptFile(cmdId: String, command: String) {
        try {
            File(SHARED).mkdirs()
            File(SHARED, cmdId + ".sh").writeText(
                "#!/bin/bash\necho \"=== Hermes: " + cmdId + " ===\"\ndate\n" + command + "\necho \"Exit: \$?\"\n")
        } catch (_: Exception) {}
    }

    private fun wrapWithStatus(cmdId: String, cmd: String): String {
        val sf = SHARED + cmdId + "_status.json"
        val lf = SHARED + cmdId + ".log"
        val esc = cmd.replace("'", "'\\''")
        val sb = StringBuilder()
        sb.append("echo '{\"s\":\"RUNNING\",\"m\":\"Exec\"}' > ").append(sf).append(" && ")
        sb.append("(").append(esc).append(") > ").append(lf).append(" 2>&1; EC=\$?; ")
        sb.append("if [ \$EC -eq 0 ]; then ")
        sb.append("echo '{\"s\":\"COMPLETED\",\"m\":\"Done\"}' > ").append(sf).append("; ")
        sb.append("else ")
        sb.append("echo '{\"s\":\"FAILED\",\"m\":\"Err '\$EC'\"}' > ").append(sf).append("; fi")
        return sb.toString()
    }

    // ===== 상태 관리 =====

    fun saveStatus(id: String, status: Status, msg: String) {
        try {
            File(SHARED).mkdirs()
            File(SHARED, id + "_status.json").writeText(
                JSONObject().apply {
                    put("status", status.name)
                    put("message", msg)
                    put("time", System.currentTimeMillis())
                }.toString()
            )
        } catch (_: Exception) {}
    }

    fun getStatus(id: String): Pair<Status, String> {
        return try {
            val f = File(SHARED, id + "_status.json")
            if (!f.exists()) return Pair(Status.IDLE, "Not started")
            val j = JSONObject(f.readText())
            val s = try { Status.valueOf(j.getString("status")) } catch (_: Exception) { Status.IDLE }
            Pair(s, j.optString("message", ""))
        } catch (_: Exception) { Pair(Status.IDLE, "Error") }
    }

    fun getLog(id: String): String {
        return try {
            val f = File(SHARED, id + ".log")
            if (f.exists() && f.length() > 0) f.readText() else ""
        } catch (_: Exception) { "" }
    }

    fun statusColor(s: Status): Int = when (s) {
        Status.IDLE -> 0xFF888888.toInt()
        Status.PENDING -> 0xFFFFAA00.toInt()
        Status.RUNNING -> 0xFF00AAFF.toInt()
        Status.COMPLETED -> 0xFF00CC00.toInt()
        Status.FAILED -> 0xFFFF3333.toInt()
    }

    fun statusText(s: Status): String = when (s) {
        Status.IDLE -> "\u5927\uae30\uc911"
        Status.PENDING -> "\u27f3 \uc2dc\uc791\uc911"
        Status.RUNNING -> "\u25b6 \uc2e4\ud589\uc911"
        Status.COMPLETED -> "\u2714 \uc644\ub8cc"
        Status.FAILED -> "\u2716 \uc2e4\ud328"
    }

    fun statusIcon(s: Status): String = when (s) {
        Status.IDLE -> "\u25ef"
        Status.PENDING -> "\u27f3"
        Status.RUNNING -> "\u25b6"
        Status.COMPLETED -> "\u2714"
        Status.FAILED -> "\u2716"
    }
}

package com.hermes.reverser.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import org.json.JSONObject

/**
 * 이미 설치된 Termux와 연동
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val SHARED_DIR = "/sdcard/HermesReverser/"
    }

    enum class CommandStatus { IDLE, PENDING, RUNNING, COMPLETED, FAILED }

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) { false }
    }

    fun openTermuxStore() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // === 기존 메서드 (호환성 유지) ===

    fun runCommand(command: String): Boolean {
        return runCommandInternal("cmd_" + System.currentTimeMillis(), command)
    }

    fun runScript(script: String, scriptName: String): Boolean {
        val dir = File(SHARED_DIR)
        dir.mkdirs()
        val f = File(dir, scriptName)
        f.writeText(script)
        val cmd = "bash $SHARED_DIR$scriptName >> $SHARED_DIR/hermes.log 2>&1"
        return runCommand(cmd)
    }

    // === 새 메서드: 상태 추적 ===

    fun runCommandTracked(commandId: String, command: String): Boolean {
        return runCommandInternal(commandId, command)
    }

    private fun runCommandInternal(commandId: String, command: String): Boolean {
        Log.i(TAG, "[$commandId] Command: ${command.take(60)}")
        if (!isTermuxInstalled()) { Toast.makeText(context, "Termux not installed", Toast.LENGTH_SHORT).show(); return false }

        saveStatus(commandId, CommandStatus.PENDING, "Starting...")
        bringTermuxToFront()

        Handler(Looper.getMainLooper()).postDelayed({
            sendCommandIntent(commandId, command)
        }, 600)
        return true
    }

    private fun bringTermuxToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
            }
        } catch (e: Exception) { Log.w(TAG, "Cannot bring Termux to front") }
    }

    private fun sendCommandIntent(commandId: String, command: String) {
        val fullCmd = buildWrappedCommand(commandId, command)
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", fullCmd))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        try {
            context.startForegroundService(intent)
            saveStatus(commandId, CommandStatus.RUNNING, "Executing in Termux")
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed: " + e.message)
            tryAmFallback(commandId, fullCmd)
        }
    }

    private fun tryAmFallback(commandId: String, command: String) {
        val esc = command.replace("'", "'\\''")
        val amCmd = "am startservice -n com.termux/com.termux.app.RunCommandService --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash' --esa com.termux.RUN_COMMAND_ARGUMENTS '-c,$esc' --ez com.termux.RUN_COMMAND_BACKGROUND true"
        try {
            Runtime.getRuntime().exec(amCmd)
            saveStatus(commandId, CommandStatus.RUNNING, "Executing via am")
        } catch (e: Exception) {
            saveStatus(commandId, CommandStatus.FAILED, "All methods failed")
        }
    }

    private fun buildWrappedCommand(cmdId: String, cmd: String): String {
        val sf = "$SHARED_DIR${cmdId}_status.json"
        val lf = "$SHARED_DIR${cmdId}.log"
        val escCmd = cmd.replace("'", "'\\''").replace("\"", "\\\"")
        val sb = StringBuilder()
        sb.append("echo '{\"status\":\"RUNNING\",\"msg\":\"Started\"}' > $sf ; ")
        sb.append(" ($escCmd) > $lf 2>&1 ; ")
        sb.append("EC=\$? ; ")
        sb.append("if [ \$EC -eq 0 ]; then ")
        sb.append("  echo '{\"status\":\"COMPLETED\",\"msg\":\"Done\"}' > $sf ; ")
        sb.append("else ")
        sb.append("  echo '{\"status\":\"FAILED\",\"msg\":\"Exit '\$EC'\"}' > $sf ; ")
        sb.append("fi")
        return sb.toString()
    }

    // === 상태 관리 ===

    fun saveStatus(commandId: String, status: CommandStatus, message: String) {
        try {
            File(SHARED_DIR).mkdirs()
            val json = JSONObject().apply {
                put("status", status.name)
                put("message", message)
                put("time", System.currentTimeMillis())
            }
            File(SHARED_DIR, "${commandId}_status.json").writeText(json.toString())
        } catch (e: Exception) { Log.w(TAG, "saveStatus error") }
    }

    fun getStatus(commandId: String): Pair<CommandStatus, String> {
        return try {
            val f = File(SHARED_DIR, "${commandId}_status.json")
            if (!f.exists()) return Pair(CommandStatus.IDLE, "Not started")
            val json = JSONObject(f.readText())
            val s = try { CommandStatus.valueOf(json.getString("status")) } catch (_: Exception) { CommandStatus.IDLE }
            Pair(s, json.optString("message", ""))
        } catch (e: Exception) { Pair(CommandStatus.IDLE, "Error") }
    }

    fun getLog(commandId: String): String {
        return try {
            val f = File(SHARED_DIR, "$commandId.log")
            if (f.exists() && f.length() > 0) f.readText() else ""
        } catch (e: Exception) { "" }
    }

    fun getStatusColor(status: CommandStatus): Int {
        return when (status) {
            CommandStatus.IDLE -> 0xFF888888.toInt()
            CommandStatus.PENDING -> 0xFFFFAA00.toInt()
            CommandStatus.RUNNING -> 0xFF00AAFF.toInt()
            CommandStatus.COMPLETED -> 0xFF00CC00.toInt()
            CommandStatus.FAILED -> 0xFFFF3333.toInt()
        }
    }

    fun getStatusText(status: CommandStatus): String {
        return when (status) {
            CommandStatus.IDLE -> "\u5927\uae30\uc911"        // 대기중
            CommandStatus.PENDING -> "\uc2dc\uc791\uc911"       // 시작중
            CommandStatus.RUNNING -> "\uc2e4\ud589\uc911"       // 실행중
            CommandStatus.COMPLETED -> "\uc644\ub8cc"           // 완료
            CommandStatus.FAILED -> "\uc2e4\ud328"             // 실패
        }
    }

    fun installDebian(): Boolean {
        val cmd = "pkg update -y && pkg upgrade -y && pkg install proot-distro -y && proot-distro install debian"
        return runCommand(cmd)
    }

    fun installCapstone(): Boolean {
        val cmd = "pkg install python capstone -y && pip3 install capstone"
        return runCommand(cmd)
    }
}

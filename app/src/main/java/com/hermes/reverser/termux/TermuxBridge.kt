package com.hermes.reverser.termux

import android.content.ClipData
import android.content.ClipboardManager
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
 * Termux 연동 — 클립보드 복사 + Termux 자동 열기
 *
 * 작동 순서:
 * 1. 명령어를 .sh 파일로 저장
 * 2. 클립보드에 자동 복사 (사용자가 Ctrl+Shift+V로 붙여넣기)
 * 3. Termux를 포그라운드로 자동 열기
 * 4. Toast로 안내 메시지 표시
 * 5. Termux:API가 설치되어 있으면 추가 시도
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val PKG = "com.termux"
        private const val SHARED = "/sdcard/HermesReverser/"
    }

    enum class Status { IDLE, READY, RUNNING, COMPLETED, FAILED }

    fun isInstalled(): Boolean =
        try { context.packageManager.getPackageInfo(PKG, 0); true }
        catch (_: Exception) { false }

    fun openStore() {
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://f-droid.org/packages/com.termux/"))
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

    // ===== 메인 기능: 클립보드 복사 + Termux 열기 =====

    fun runTracked(cmdId: String, command: String): Boolean {
        if (!isInstalled()) {
            Toast.makeText(context, "Termux not installed", Toast.LENGTH_SHORT).show()
            return false
        }

        // 1. .sh 스크립트 파일 작성
        writeScriptFile(cmdId, command)

        // 2. 클립보드에 명령어 복사
        val scriptPath = SHARED + cmdId + ".sh"
        val clipText = "bash " + scriptPath + "  # Hermes: " + cmdId
        copyToClipboard(clipText)

        // 3. 상태: READY (클립보드에 복사됨)
        saveStatus(cmdId, Status.READY,
            "Copied to clipboard!\n" +
            "1. Termux will open\n" +
            "2. Paste: Ctrl+Shift+V\n" +
            "3. Press Enter")

        // 4. Termux 열기
        openTermuxFront()

        Toast.makeText(context,
            "Command copied! Open Termux and paste (Ctrl+Shift+V)",
            Toast.LENGTH_LONG).show()

        // 5. 2초 후 Termux:API toast 시도
        Handler(Looper.getMainLooper()).postDelayed({
            tryTermuxApiToast(cmdId, command)
        }, 2000)

        return true
    }

    // ===== 클립보드 =====

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("HermesCommand", text))
            Log.i(TAG, "Copied to clipboard: " + text)
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard error: " + e.message)
        }
    }

    // ===== Termux 열기 =====

    private fun openTermuxFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(PKG)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open Termux: " + e.message)
        }
    }

    // ===== Termux:API toast (선택적) =====

    private fun tryTermuxApiToast(cmdId: String, command: String) {
        try {
            context.sendBroadcast(Intent("com.termux.api.ACTION_API").apply {
                setClassName("com.termux.api", "com.termux.api.TermuxApiReceiver")
                putExtra("api_method", "toast")
                putExtra("params", "-s Paste: Ctrl+Shift+V then Enter #Hermes")
            })
        } catch (_: Exception) { }

        // RUN_COMMAND도 한 번 더 시도 (이미 Termux가 열린 상태)
        try {
            val intent = Intent().apply {
                setClassName(PKG, PKG + ".app.RunCommandService")
                action = PKG + ".RUN_COMMAND"
                putExtra(PKG + ".RUN_COMMAND_PATH", "/data/data/" + PKG + "/files/usr/bin/bash")
                putExtra(PKG + ".RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapWithStatus(cmdId, command)))
                putExtra(PKG + ".RUN_COMMAND_WORKDIR", "/data/data/" + PKG + "/files/home")
                putExtra(PKG + ".RUN_COMMAND_BACKGROUND", true)
            }
            ContextCompat.startForegroundService(context, intent)
            saveStatus(cmdId, Status.RUNNING, "Auto-executed!")
        } catch (_: Exception) { }
    }

    // ===== 스크립트 작성 =====

    private fun writeScriptFile(cmdId: String, command: String) {
        try {
            File(SHARED).mkdirs()
            File(SHARED, cmdId + ".sh").writeText(
                "#!/bin/bash\n" +
                "echo \"=== Hermes: " + cmdId + " ===\"\n" +
                "date\n" +
                command + "\n" +
                "echo \"Exit: \$?\"\n"
            )
        } catch (_: Exception) { }
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
        } catch (_: Exception) { }
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
        Status.READY -> 0xFFFFAA00.toInt()
        Status.RUNNING -> 0xFF00AAFF.toInt()
        Status.COMPLETED -> 0xFF00CC00.toInt()
        Status.FAILED -> 0xFFFF3333.toInt()
    }

    fun statusText(s: Status): String = when (s) {
        Status.IDLE -> "IDLE"
        Status.READY -> "READY (paste)"
        Status.RUNNING -> "RUNNING"
        Status.COMPLETED -> "COMPLETED"
        Status.FAILED -> "FAILED"
    }

    fun iconFor(s: Status): String = when (s) {
        Status.IDLE -> "o"
        Status.READY -> "CP"
        Status.RUNNING -> ">"
        Status.COMPLETED -> "OK"
        Status.FAILED -> "X"
    }
}

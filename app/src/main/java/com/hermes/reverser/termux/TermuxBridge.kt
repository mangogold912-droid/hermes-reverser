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
 * Termux 연동 — 4단계 fallback으로 명령 전달
 *
 * 순서:
 * 1. startForegroundService() 시도
 * 2. Runtime.exec("am startservice") 시도
 * 3. Broadcast 발송 시도
 * 4. 모두 실패 → 수동 실행 안내 (status=FAILED + 메시지)
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val PKG = "com.termux"
        private const val SHARED = "/sdcard/HermesReverser/"
    }

    enum class Status { IDLE, PENDING, RUNNING, COMPLETED, FAILED }

    fun isInstalled(): Boolean =
        try { context.packageManager.getPackageInfo(PKG, 0); true }
        catch (_: Exception) { false }

    fun openStore() {
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://f-droid.org/packages/$PKG/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ===== 기존 호환용 =====

    fun runCommand(command: String): Boolean =
        runTracked("cmd_" + System.currentTimeMillis(), command)

    fun runScript(script: String, name: String): Boolean {
        File(SHARED).mkdirs()
        File(SHARED, name).writeText(script)
        return runCommand("bash $SHARED$name >> $SHARED$name.log 2>&1")
    }

    fun installDebian(): Boolean =
        runTracked("debian", "pkg update -y && pkg install proot-distro -y && proot-distro install debian")

    fun installCapstone(): Boolean =
        runTracked("capstone", "pkg install python capstone -y && pip3 install capstone")

    // ===== 상태 추적 실행 (4단계 fallback) =====

    fun runTracked(cmdId: String, command: String): Boolean {
        if (!isInstalled()) {
            Toast.makeText(context, "Termux not installed", Toast.LENGTH_SHORT).show()
            return false
        }

        // 0. 명령어를 .sh 파일로 저장 (수동 실행 fallback용)
        writeScriptFile(cmdId, command)
        saveStatus(cmdId, Status.PENDING, "Opening Termux...")

        // 1. Termux를 포그라운드로 띄움
        openTermuxFront()

        // 2. 1초 후 4단계 fallback 시도
        Handler(Looper.getMainLooper()).postDelayed({
            tryAllMethods(cmdId, command)
        }, 1000)

        return true
    }

    /**
     * 4단계 fallback
     */
    private fun tryAllMethods(cmdId: String, command: String) {
        Log.i(TAG, "[$cmdId] Trying all methods...")

        // 단계 1: startForegroundService
        if (tryForegroundService(cmdId, command)) return

        // 단계 2: am startservice via Runtime.exec
        if (tryAmCommand(cmdId, command)) return

        // 단계 3: broadcast
        if (tryBroadcast(cmdId, command)) return

        // 단계 4: 모두 실패 → 수동 실행 안내
        Log.e(TAG, "[$cmdId] All methods failed")
        saveStatus(cmdId, Status.FAILED,
            "Auto-failed. Open Termux and run:\n" +
            "bash /sdcard/HermesReverser/$cmdId.sh")
    }

    // 단계 1: startForegroundService
    private fun tryForegroundService(cmdId: String, command: String): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(PKG, "$PKG.app.RunCommandService")
                action = "$PKG.RUN_COMMAND"
                putExtra("$PKG.RUN_COMMAND_PATH", "/data/data/$PKG/files/usr/bin/bash")
                putExtra("$PKG.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapWithStatus(cmdId, command)))
                putExtra("$PKG.RUN_COMMAND_WORKDIR", "/data/data/$PKG/files/home")
                putExtra("$PKG.RUN_COMMAND_BACKGROUND", true)
            }
            ContextCompat.startForegroundService(context, intent)
            saveStatus(cmdId, Status.RUNNING, "Method1: foregroundService OK")
            Log.i(TAG, "[$cmdId] Method1 success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[$cmdId] Method1 failed: ${e.message}")
            false
        }
    }

    // 단계 2: am startservice via Runtime.exec
    private fun tryAmCommand(cmdId: String, command: String): Boolean {
        val wrapped = wrapWithStatus(cmdId, command)
        val esc = wrapped.replace("'", "'\\''").replace("\"", "\\\"")
        val amCmd = arrayOf(
            "sh", "-c",
            "am startservice -n $PKG/$PKG.app.RunCommandService " +
            "--es $PKG.RUN_COMMAND_PATH '/data/data/$PKG/files/usr/bin/bash' " +
            "--esa $PKG.RUN_COMMAND_ARGUMENTS '-c,$esc' " +
            "--ez $PKG.RUN_COMMAND_BACKGROUND true 2>&1"
        )

        return try {
            val proc = Runtime.getRuntime().exec(amCmd)
            val exitCode = proc.waitFor()
            val output = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()

            Log.d(TAG, "[$cmdId] Method2 exit=$exitCode out=$output err=$err")

            if (exitCode == 0 && !err.contains("Error") && !err.contains("SecurityException")) {
                saveStatus(cmdId, Status.RUNNING, "Method2: am OK")
                Log.i(TAG, "[$cmdId] Method2 success")
                true
            } else {
                Log.w(TAG, "[$cmdId] Method2 failed: exit=$exitCode err=$err")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$cmdId] Method2 exception: ${e.message}")
            false
        }
    }

    // 단계 3: broadcast
    private fun tryBroadcast(cmdId: String, command: String): Boolean {
        return try {
            val intent = Intent("$PKG.RUN_COMMAND").apply {
                setPackage(PKG)
                putExtra("$PKG.RUN_COMMAND_PATH", "/data/data/$PKG/files/usr/bin/bash")
                putExtra("$PKG.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapWithStatus(cmdId, command)))
            }
            context.sendBroadcast(intent)
            saveStatus(cmdId, Status.RUNNING, "Method3: broadcast OK")
            Log.i(TAG, "[$cmdId] Method3 success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[$cmdId] Method3 failed: ${e.message}")
            false
        }
    }

    // ===== 헬퍼 =====

    private fun openTermuxFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(PKG)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open Termux: ${e.message}")
        }
    }

    /**
     * 명령어를 .sh 파일로 저장 (수동 실행 fallback)
     */
    private fun writeScriptFile(cmdId: String, command: String) {
        try {
            File(SHARED).mkdirs()
            val content = """#!/bin/bash
echo "=== Hermes: $cmdId ==="
date
$command
echo "Exit code: \$?"
""".trimIndent()
            File(SHARED, "$cmdId.sh").writeText(content)
        } catch (e: Exception) {
            Log.w(TAG, "Write file error: ${e.message}")
        }
    }

    /**
     * 상태 저장 래퍼
     */
    private fun wrapWithStatus(cmdId: String, cmd: String): String {
        val sf = "$SHARED${cmdId}_status.json"
        val lf = "$SHARED${cmdId}.log"
        val esc = cmd.replace("'", "'\\''")
        return "echo '{\"s\":\"RUNNING\",\"m\":\"Exec\"}' > $sf && ($esc) > $lf 2>&1; EC=\$?; " +
               "if [ \$EC -eq 0 ]; then echo '{\"s\":\"COMPLETED\",\"m\":\"Done\"}' > $sf; " +
               "else echo '{\"s\":\"FAILED\",\"m\":\"Exit '\$EC'\"}' > $sf; fi"
    }

    // ===== 상태 관리 =====

    fun saveStatus(id: String, status: Status, msg: String = "") {
        try {
            File(SHARED).mkdirs()
            File(SHARED, "${id}_status.json").writeText(
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
            val f = File(SHARED, "${id}_status.json")
            if (!f.exists()) return Pair(Status.IDLE, "Not started")
            val j = JSONObject(f.readText())
            val s = try { Status.valueOf(j.getString("status")) } catch (_: Exception) { Status.IDLE }
            Pair(s, j.optString("message", ""))
        } catch (_: Exception) { Pair(Status.IDLE, "Error") }
    }

    fun getLog(id: String): String {
        return try {
            val f = File(SHARED, "$id.log")
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
        Status.PENDING -> "\uc2dc\uc791\uc911"
        Status.RUNNING -> "\uc2e4\ud589\uc911"
        Status.COMPLETED -> "\uc644\ub8cc"
        Status.FAILED -> "\uc2e4\ud328"
    }

    fun statusIcon(s: Status): String = when (s) {
        Status.IDLE -> "\u25ef"
        Status.PENDING -> "\u27f3"
        Status.RUNNING -> "\u25b6"
        Status.COMPLETED -> "\u2714"
        Status.FAILED -> "\u2716"
    }
}

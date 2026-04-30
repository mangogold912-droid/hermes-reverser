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
 * 이미 설치된 Termux와 연동 — Run #52 기준 개선
 *
 * 작동 순서:
 * 1. 명령어를 /sdcard/HermesReverser/cmd_XXX.sh에 저장
 * 2. Termux를 포그라운드로 띄움 (startActivity)
 * 3. 800ms 후 am startservice로 Termux에 명령 전달
 * 4. /sdcard/HermesReverser/status_XXX.json에 상태 저장
 * 5. UI에서 2초마다 파일 읽어 상태 업데이트
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val TERMUX_PKG = "com.termux"
        private const val SHARED = "/sdcard/HermesReverser/"
    }

    enum class Status { IDLE, PENDING, RUNNING, COMPLETED, FAILED }

    fun isInstalled(): Boolean =
        try { context.packageManager.getPackageInfo(TERMUX_PKG, 0); true }
        catch (_: Exception) { false }

    fun openStore() {
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://f-droid.org/packages/com.termux/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * 기존 호환용 — 상태 추적 없이 실행
     */
    fun runCommand(command: String): Boolean {
        return runTracked("cmd_" + System.currentTimeMillis(), command)
    }

    /**
     * 상태 추적 + Termux 자동 실행
     */
    fun runTracked(cmdId: String, command: String): Boolean {
        if (!isInstalled()) {
            Toast.makeText(context, "Termux not installed", Toast.LENGTH_SHORT).show()
            return false
        }

        Log.i(TAG, "[$cmdId] Starting...")
        saveStatus(cmdId, Status.PENDING, "Opening Termux...")

        // 1. 명령어를 파일로 저장 (Termux가 읽을 수 있게)
        writeCommandFile(cmdId, command)

        // 2. Termux 포그라운드로 띄움
        bringTermuxFront()

        // 3. 800ms 후 am startservice로 명령 실행
        Handler(Looper.getMainLooper()).postDelayed({
            sendViaAm(cmdId, command)
        }, 800)

        return true
    }

    /**
     * Termux를 화면 맨 앞으로
     */
    private fun bringTermuxFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PKG)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open Termux: " + e.message)
        }
    }

    /**
     * am startservice로 명령 전달 (가장 안정적)
     */
    private fun sendViaAm(cmdId: String, command: String) {
        val wrapped = wrapWithStatus(cmdId, command)
        // bash -c '...' 형태로 am 명령어 실행
        val amCmd = arrayOf(
            "sh", "-c",
            "am startservice -n $TERMUX_PKG/com.termux.app.RunCommandService " +
            "--es com.termux.RUN_COMMAND_PATH /data/data/$TERMUX_PKG/files/usr/bin/bash " +
            "--esa com.termux.RUN_COMMAND_ARGUMENTS '-c,$wrapped' " +
            "--ez com.termux.RUN_COMMAND_BACKGROUND true"
        )
        try {
            val proc = Runtime.getRuntime().exec(amCmd)
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "[$cmdId] am startservice OK")
            } else {
                val err = proc.errorStream.bufferedReader().readText()
                Log.w(TAG, "[$cmdId] am exit=$exitCode err=$err")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$cmdId] am failed: " + e.message)
        }
    }

    /**
     * 명령어를 상태 저장 래퍼로 감쌈
     */
    private fun wrapWithStatus(cmdId: String, cmd: String): String {
        val sf = SHARED + cmdId + "_status.json"
        val lf = SHARED + cmdId + ".log"
        val esc = cmd.replace("'", "'\\''")
        return "echo '{\"s\":\"RUNNING\",\"m\":\"Exec\"}' > $sf && ($esc) > $lf 2>&1; EC=\$?; " +
               "if [ \$EC -eq 0 ]; then echo '{\"s\":\"COMPLETED\",\"m\":\"Done\"}' > $sf; " +
               "else echo '{\"s\":\"FAILED\",\"m\":\"Err '\$EC'\"}' > $sf; fi"
    }

    /**
     * 명령어를 스크립트 파일로 저장
     */
    private fun writeCommandFile(cmdId: String, command: String) {
        try {
            File(SHARED).mkdirs()
            File(SHARED, "$cmdId.sh").writeText("#!/bin/bash\n$command\n")
        } catch (e: Exception) { Log.w(TAG, "Write file error") }
    }

    // === 상태 관리 ===

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
        Status.IDLE -> "\u5927\uae30\uc911"      // 대기중
        Status.PENDING -> "\uc2dc\uc791\uc911"     // 시작중
        Status.RUNNING -> "\uc2e4\ud589\uc911"     // 실행중
        Status.COMPLETED -> "\uc644\ub8cc"         // 완료
        Status.FAILED -> "\uc2e4\ud328"           // 실패
    }

    // === 기존 호환 ===

    fun runScript(script: String, name: String): Boolean {
        File(SHARED).mkdirs()
        File(SHARED, name).writeText(script)
        return runCommand("bash $SHARED$name")
    }

    fun installDebian(): Boolean =
        runCommand("pkg update -y && pkg install proot-distro -y && proot-distro install debian")

    fun installCapstone(): Boolean =
        runCommand("pkg install python capstone -y && pip3 install capstone")
}

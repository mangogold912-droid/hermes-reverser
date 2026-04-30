package com.hermes.reverser.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONObject

/**
 * 이미 설치된 Termux와 연동
 *
 * 작동 방식:
 * 1. Termux를 포그라운드로 띄움
 * 2. 500ms 후 RUN_COMMAND Intent 발송
 * 3. 상태를 /sdcard/HermesReverser/status_*.json에 저장
 * 4. UI에서 2초마다 파일 읽어 상태 업데이트
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val SHARED_DIR = "/sdcard/HermesReverser/"
        private const val TERMUX_PREFIX = "/data/data/com.termux/files"
    }

    /**
     * 명령 상태를 나타내는 enum
     */
    enum class CommandStatus {
        IDLE,       // 대기중
        PENDING,    // 실행 요청중
        RUNNING,    // Termux에서 실행중
        COMPLETED,  // 완료
        FAILED      // 실패
    }

    /**
     * Termux 설치 여부
     */
    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Termux F-Droid 페이지 열기
     */
    fun openTermuxStore() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Termux를 포그라운드로 띄우고 명령 실행
     */
    fun runCommand(commandId: String, command: String): Boolean {
        Log.i(TAG, "[$commandId] Running: ${command.take(80)}")

        if (!isTermuxInstalled()) {
            Toast.makeText(context, "Termux not installed", Toast.LENGTH_SHORT).show()
            return false
        }

        // 상태: PENDING
        saveStatus(commandId, CommandStatus.PENDING, "Starting Termux...")

        // 1. Termux를 포그라운드로 띄움 (성공률 높음)
        bringTermuxToFront()

        // 2. 600ms 후 명령 전달 (Termux가 화면에 뜰 시간)
        Handler(Looper.getMainLooper()).postDelayed({
            sendRunCommandIntent(commandId, command)
        }, 600)

        return true
    }

    /**
     * 스크립트를 파일로 저장 후 실행
     */
    fun runScript(script: String, scriptName: String): Boolean {
        val sharedDir = File(SHARED_DIR)
        sharedDir.mkdirs()
        val scriptFile = File(sharedDir, scriptName)
        scriptFile.writeText(script)
        val cmdId = scriptName.replace(".sh", "").replace(".py", "")
        val cmd = "bash $SHARED_DIR$scriptName >> $TERMUX_PREFIX/home/hermes.log 2>&1"
        return runCommand(cmdId, cmd)
    }

    /**
     * Termux를 포그라운드로 가져오기
     */
    private fun bringTermuxToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                context.startActivity(intent)
                Log.i(TAG, "Termux brought to front")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bring Termux to front: " + e.message)
        }
    }

    /**
     * RUN_COMMAND Intent 발송
     */
    private fun sendRunCommandIntent(commandId: String, command: String) {
        val fullCommand = buildCommandWithStatus(commandId, command)

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", fullCommand))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }

        try {
            context.startForegroundService(intent)
            saveStatus(commandId, CommandStatus.RUNNING, "Executing in Termux...")
            Log.i(TAG, "[$commandId] Command sent to Termux")
        } catch (e: Exception) {
            Log.w(TAG, "[$commandId] startForegroundService failed: " + e.message)
            // Fallback: am broadcast
            tryAmBroadcast(commandId, fullCommand)
        }
    }

    /**
     * am broadcast fallback
     */
    private fun tryAmBroadcast(commandId: String, command: String) {
        val escaped = command.replace("'", "'\\''").replace("\"", "\\\"")
        val amCmd = """am startservice -n com.termux/com.termux.app.RunCommandService --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash' --esa com.termux.RUN_COMMAND_ARGUMENTS '-c,$escaped' --ez com.termux.RUN_COMMAND_BACKGROUND true 2>&1"""
        
        try {
            Runtime.getRuntime().exec(amCmd)
            saveStatus(commandId, CommandStatus.RUNNING, "Executing via am...")
        } catch (e: Exception) {
            Log.e(TAG, "[$commandId] All methods failed: " + e.message)
            saveStatus(commandId, CommandStatus.FAILED, "Failed to execute")
        }
    }

    /**
     * 상태 저장용 명령어 래퍼
     */
    private fun buildCommandWithStatus(commandId: String, command: String): String {
        val statusFile = "$SHARED_DIR${commandId}_status.json"
        val logFile = "$SHARED_DIR${commandId}.log"

        return """
            echo '{"status":"RUNNING","message":"Executing","time":"'$(date +%s)'"}' > $statusFile && 
            ($command) > $logFile 2>&1; 
            EXIT_CODE=$?; 
            if [ $EXIT_CODE -eq 0 ]; then 
                echo '{"status":"COMPLETED","message":"Done","time":"'$(date +%s)'"}' > $statusFile; 
            else 
                echo '{"status":"FAILED","message":"Exit code '$EXIT_CODE'","time":"'$(date +%s)'"}' > $statusFile; 
            fi
        """.trimIndent().replace("\n", " ")
    }

    /**
     * 상태 저장
     */
    fun saveStatus(commandId: String, status: CommandStatus, message: String = "") {
        try {
            val dir = File(SHARED_DIR)
            dir.mkdirs()
            val file = File(dir, "${commandId}_status.json")
            val json = JSONObject().apply {
                put("status", status.name)
                put("message", message)
                put("time", System.currentTimeMillis())
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save status: " + e.message)
        }
    }

    /**
     * 상태 읽기
     */
    fun getStatus(commandId: String): Pair<CommandStatus, String> {
        return try {
            val file = File(SHARED_DIR, "${commandId}_status.json")
            if (!file.exists()) return Pair(CommandStatus.IDLE, "Not started")
            
            val json = JSONObject(file.readText())
            val statusName = json.optString("status", "IDLE")
            val message = json.optString("message", "")
            val status = try {
                CommandStatus.valueOf(statusName)
            } catch (_: Exception) {
                CommandStatus.IDLE
            }
            Pair(status, message)
        } catch (e: Exception) {
            Pair(CommandStatus.IDLE, "Error: " + e.message)
        }
    }

    /**
     * 로그 읽기
     */
    fun getLog(commandId: String = ""): String {
        return try {
            val logFile = File(SHARED_DIR, if (commandId.isNotEmpty()) "${commandId}.log" else "hermes.log")
            if (logFile.exists()) logFile.readText().ifEmpty { "No output" } else "No log yet"
        } catch (e: Exception) {
            "Error: " + e.message
        }
    }

    /**
     * 모든 명령 ID 목록
     */
    fun getAllStatus(): Map<String, Pair<CommandStatus, String>> {
        val result = mutableMapOf<String, Pair<CommandStatus, String>>()
        val ids = listOf("setup_full", "debian", "ida_mcp", "capstone", "radare2", "jadx", "apktool")
        for (id in ids) {
            result[id] = getStatus(id)
        }
        return result
    }

    /**
     * 상태 색상
     */
    fun getStatusColor(status: CommandStatus): Int {
        return when (status) {
            CommandStatus.IDLE -> 0xFF888888.toInt()     // Gray
            CommandStatus.PENDING -> 0xFFFFAA00.toInt()   // Orange
            CommandStatus.RUNNING -> 0xFF00AA
package com.hermes.reverser.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * 이미 설치된 Termux와 연동
 *
 * - Termux 설치 여부 확인
 * - RUN_COMMAND Intent로 명령 실행 (startForegroundService 사용)
 * - 실패 시 am 명령어 fallback
 * - 공유 저장소를 통한 파일 교환
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
        private const val SHARED_DIR = "/sdcard/HermesReverser/"
    }

    /**
     * Termux 설치 여부 확인
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
     * Termux F-Droid 페이지 열기 (미설치시)
     */
    fun openTermuxStore() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Termux RUN_COMMAND Intent 생성
     */
    private fun createRunCommandIntent(command: String): Intent {
        return Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            action = "$TERMUX_PACKAGE.RUN_COMMAND"
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", true)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_SESSION_ACTION", "0")
        }
    }

    /**
     * Termux에서 명령 실행
     * 방법 1: startForegroundService (Android 8+ 지원)
     * 방법 2: am 명령어 (fallback)
     * 방법 3: Termux 직접 열기 (최종 fallback)
     */
    fun runCommand(command: String): Boolean {
        Log.i(TAG, "Running: $command")

        if (!isTermuxInstalled()) {
            Log.e(TAG, "Termux not installed")
            Toast.makeText(context, "Termux not installed", Toast.LENGTH_SHORT).show()
            return false
        }

        // 방법 1: startForegroundService (권장)
        return try {
            val intent = createRunCommandIntent(command)
            context.startForegroundService(intent)
            Log.i(TAG, "Command sent via foreground service")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service failed: " + e.message)
            // 방법 2: am 명령어로 시도
            runViaAmCommand(command)
        }
    }

    /**
     * am 명령어로 Termux 서비스 실행 (fallback)
     */
    private fun runViaAmCommand(command: String): Boolean {
        return try {
            val args = command.replace("'", "'\\''")
            val amCmd = buildString {
                append("am startservice -n $TERMUX_PACKAGE/$TERMUX_SERVICE ")
                append("--es $TERMUX_PACKAGE.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash' ")
                append("--esa $TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS '-c,$args' ")
                append("--es $TERMUX_PACKAGE.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home' ")
                append("--ez $TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND true ")
                append("--ei $TERMUX_PACKAGE.RUN_COMMAND_SESSION_ACTION 0")
            }

            val process = Runtime.getRuntime().exec(amCmd)
            process.waitFor()
            val exitCode = process.exitValue()
            Log.i(TAG, "am command exit code: $exitCode")

            if (exitCode == 0) {
                true
            } else {
                val error = process.errorStream.bufferedReader().readText()
                Log.w(TAG, "am error: $error")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "am fallback failed: " + e.message)
            false
        }
    }

    /**
     * Termux를 직접 열어서 명령 실행 (최종 fallback)
     */
    fun openTermuxWithCommand(command: String) {
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.app.TermuxActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 명령어를 파일로 저장필
        val sharedDir = File(SHARED_DIR)
        sharedDir.mkdirs()
        val cmdFile = File(sharedDir, "run_cmd.sh")
        cmdFile.writeText(command)
        Toast.makeText(context, "Command saved. Run in Termux:\n bash /sdcard/HermesReverser/run_cmd.sh", Toast.LENGTH_LONG).show()
        context.startActivity(intent)
    }

    /**
     * Debian 설치
     */
    fun installDebian(): Boolean {
        val cmd = "pkg update -y && pkg upgrade -y && pkg install proot-distro -y && proot-distro install debian"
        val result = runCommand(cmd)
        if (!result) {
            openTermuxWithCommand(cmd)
        }
        return result
    }

    /**
     * Capstone 설치
     */
    fun installCapstone(): Boolean {
        val cmd = "pkg install python capstone -y && pip3 install capstone"
        val result = runCommand(cmd)
        if (!result) {
            openTermuxWithCommand(cmd)
        }
        return result
    }

    /**
     * 스크립트를 파일로 저장 후 실행
     */
    fun runScript(script: String, scriptName: String): Boolean {
        val sharedDir = File(SHARED_DIR)
        sharedDir.mkdirs()
        val scriptFile = File(sharedDir, scriptName)
        scriptFile.writeText(script)

        val cmd = "bash $SHARED_DIR$scriptName >> $TERMUX_PREFIX/home/hermes.log 2>&1"
        val result = runCommand(cmd)
        if (!result) {
            openTermuxWithCommand("bash /sdcard/HermesReverser/$scriptName")
        }
        return result
    }

    /**
     * 작업 로그 가져오기
     */
    fun getLog(): String {
        return try {
            val logFile = File(SHARED_DIR + "hermes.log")
            if (logFile.exists()) logFile.readText() else "No log yet"
        } catch (e: Exception) {
            "Error reading log: " + e.message
        }
    }

    private val TERMUX_PREFIX = "/data/data/com.termux/files"
}

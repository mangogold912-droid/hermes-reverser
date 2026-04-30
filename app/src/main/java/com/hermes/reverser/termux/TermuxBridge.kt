package com.hermes.reverser.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 이미 설치된 Termux와 연동
 *
 * - Termux 설치 여부 확인
 * - RUN_COMMAND Intent로 명령 실행
 * - 공유 저장소를 통한 파일 교환
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_FILES = "/data/data/com.termux/files/"
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
        context.startActivity(intent)
    }

    /**
     * Termux RUN_COMMAND Intent 생성
     */
    fun createRunCommandIntent(command: String): Intent {
        return Intent("$TERMUX_PACKAGE.RUN_COMMAND").apply {
            setPackage(TERMUX_PACKAGE)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", "$TERMUX_FILES/usr/bin/bash")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_WORKDIR", "$TERMUX_FILES/home")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", true)
        }
    }

    /**
     * Termux에서 명령 실행
     */
    fun runCommand(command: String): Boolean {
        return if (isTermuxInstalled()) {
            try {
                val intent = createRunCommandIntent(command)
                context.startService(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run command: ${e.message}")
                false
            }
        } else {
            false
        }
    }

    /**
     * Debian (proot-distro) 설치 스크립트 실행
     */
    fun installDebian(): Boolean {
        val cmd = buildString {
            append("pkg update -y && ")
            append("pkg install proot-distro -y && ")
            append("proot-distro install debian && ")
            append("echo '[Hermes] Debian installed' >> $TERMUX_FILES/home/hermes.log")
        }
        return runCommand(cmd)
    }

    /**
     * Debian 안에서 IDA MCP 서버 설치
     */
    fun installIdaMcpInDebian(): Boolean {
        val cmd = buildString {
            append("proot-distro login debian -- ")
            append("bash -c 'apt update && ")
            append("apt install -y python3 python3-pip wget git && ")
            append("mkdir -p /opt/ida-mcp && ")
            append("cd /opt/ida-mcp && ")
            append("wget -q https://raw.githubusercontent.com/mrexodia/ida-pro-mcp/master/mcp_server.py && ")
            append("pip3 install flask requests && ")
            append("echo \"[Hermes] IDA MCP ready\" >> /tmp/hermes.log'")
        }
        return runCommand(cmd)
    }

    /**
     * IDA MCP 서버 시작 (Debian 안)
     */
    fun startIdaMcpServer(): Boolean {
        val cmd = buildString {
            append("proot-distro login debian -- ")
            append("bash -c 'cd /opt/ida-mcp && ")
            append("python3 mcp_server.py --host 0.0.0.0 --port 5000 &'")
        }
        return runCommand(cmd)
    }

    /**
     * 스크립트를 파일로 저장 후 Termux에서 실행
     */
    fun runScript(script: String, scriptName: String): Boolean {
        // /sdcard/에 스크립트 저장 (Termux가 접근 가능)
        val sharedDir = File(SHARED_DIR)
        sharedDir.mkdirs()
        val scriptFile = File(sharedDir, scriptName)
        scriptFile.writeText(script)

        val cmd = "bash $SHARED_DIR$scriptName >> $TERMUX_FILES/home/hermes.log 2>&1"
        return runCommand(cmd)
    }

    /**
     * 작업 로그 가져오기
     */
    fun getLog(): String {
        return try {
            val logFile = File("$SHARED_DIR/hermes.log")
            if (logFile.exists()) logFile.readText() else "No log yet"
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }

    /**
     * Termux 환경에서 바이너리 분석 (Python 스크립트)
     */
    fun analyzeBinary(filePath: String): Boolean {
        val script = buildString {
            append("#!/data/data/com.termux/files/usr/bin/python3\n")
            append("import sys, os, struct\n")
            append("path = '$filePath'\n")
            append("if os.path.exists(path):\n")
            append("    with open(path, 'rb') as f:\n")
            append("        header = f.read(64)\n")
            append("    print('[Hermes] File:', path)\n")
            append("    print('[Hermes] Header:', header[:16].hex())\n")
            append("else:\n")
            append("    print('[Hermes] File not found:', path)\n")
        }
        return runScript(script, "analyze_binary.py")
    }

    /**
     * Capstone 디스어셈블러 설치 (Termux 안)
     */
    fun installCapstone(): Boolean {
        val cmd = "pkg install python capstone -y && pip3 install capstone"
        return runCommand(cmd)
    }

    /**
     * 바이너리를 Termux로 복사 후 분석
     */
    fun copyAndAnalyze(sourcePath: String): Boolean {
        val destName = sourcePath.substringAfterLast("/")
        val destPath = "$TERMUX_FILES/home/$destName"

        // cp 명령으로 복사
        val copyCmd = "cp \"$sourcePath\" \"$destPath\" && echo '[Hermes] Copied to Termux'"
        runCommand(copyCmd)

        // Python + Capstone 분석
        val script = buildString {
            append("#!/data/data/com.termux/files/usr/bin/python3\n")
            append("from capstone import *\n")
            append("import sys\n")
            append("path = '$destPath'\n")
            append("with open(path, 'rb') as f:\n")
            append("    code = f.read(4096)\n")
            append("md = Cs(CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN)\n")
            append("for i in md.disasm(code, 0x1000):\n")
            append("    print('0x%x: %s %s' % (i.address, i.mnemonic, i.op_str))\n")
        }
        return runScript(script, "capstone_analyze.py")
    }
}

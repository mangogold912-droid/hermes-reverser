package com.hermes.reverser.termux

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Termux 설치 및 Debian + IDA Linux 자동 설정
 */
class TermuxInstaller(private val context: Context) {

    companion object {
        private const val TAG = "TermuxInstaller"
        private const val TERMUX_FDROID_URL = "https://f-droid.org/repo/com.termux_118.apk"
        private const val TERMUX_GITHUB_URL = "https://github.com/termux/termux-app/releases/download/v0.118.0/termux-app_v0.118.0+github-debug_universal.apk"
    }

    /**
     * Termux F-Droid APK 다운로드
     */
    fun downloadTermux(): Long {
        val request = DownloadManager.Request(Uri.parse(TERMUX_GITHUB_URL)).apply {
            setTitle("Termux")
            setDescription("Downloading Termux...")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "termux.apk")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    /**
     * Termux 설치 확인
     */
    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Debian 설치 스크립트 생성 (Termux 안에서 실행)
     */
    fun getDebianInstallScript(): String {
        val sb = StringBuilder()
        sb.append("pkg update -y && pkg upgrade -y\n")
        sb.append("pkg install proot-distro -y\n")
        sb.append("proot-distro install debian\n")
        sb.append("echo 'Debian installed'\n")
        return sb.toString()
    }

    /**
     * IDA MCP 서버 시작 스크립트
     */
    fun getIdaMcpServerScript(): String {
        val sb = StringBuilder()
        sb.append("#!/bin/bash\n")
        sb.append("# IDA Pro MCP Server launcher\n")
        sb.append("cd /root/ida-mcp || cd /opt/ida-mcp\n")
        sb.append("python3 mcp_server.py --host 0.0.0.0 --port 5000\n")
        return sb.toString()
    }

    /**
     * Termux에서 명령 실행 Intent 생성
     */
    fun createTermuxIntent(command: String): Intent {
        return Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
    }

    /**
     * Debian 안에서 명령 실행
     */
    fun runInDebian(command: String): String {
        val prootCmd = "proot-distro login debian -- $command"
        return prootCmd
    }

    /**
     * IDA Linux 설치 스크립트 (Debian 안)
     */
    fun getIdaLinuxInstallScript(): String {
        val sb = StringBuilder()
        sb.append("apt update && apt install -y python3 python3-pip wget\n")
        sb.append("mkdir -p /opt/ida-mcp\n")
        sb.append("cd /opt/ida-mcp\n")
        sb.append("wget https://raw.githubusercontent.com/mrexodia/ida-pro-mcp/master/mcp_server.py\n")
        sb.append("pip3 install flask\n")
        sb.append("echo 'IDA MCP Server installed'\n")
        return sb.toString()
    }
}

package com.hermes.reverser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Termux 설정 Activity — 앱 내장 터미널에서 자동 실행
 *
 * 버튼 클릭 → TerminalActivity 열림 → 명령 자동 실행
 * Termux 없이 앱 안에서 모든 것 처리
 */
class TermuxSetupActivity : AppCompatActivity() {

    // 명령어 정의
    private val commands = listOf(
        Triple("setup_full", "1. 전체 IDA+MCP 자동설치",
            listOf(
                "pkg update -y",
                "pkg upgrade -y",
                "pkg install proot-distro -y",
                "proot-distro install debian",
                "pkg install radare2 jadx apktool -y",
                "pip3 install capstone frida jnitrace"
            )),
        Triple("debian", "2. Debian 설치",
            listOf("pkg update -y", "pkg install proot-distro -y", "proot-distro install debian")),
        Triple("mcp_server", "3. IDA MCP 서버 시작",
            listOf(
                "pkill -f mcp_server.py 2>/dev/null; sleep 1",
                "proot-distro login debian -- bash -c 'mkdir -p /opt/ida-mcp && cd /opt/ida-mcp && nohup python3 -c \"from flask import Flask; app=Flask(__name__); app.run(host=\\\"0.0.0.0\\\",port=5000)\" > /tmp/mcp.log 2>&1 & echo MCP started'"
            )),
        Triple("capstone", "4. Capstone 설치",
            listOf("pkg install python capstone -y", "pip3 install capstone")),
        Triple("radare2", "5. Radare2 설치",
            listOf("pkg install radare2 -y")),
        Triple("jadx", "6. JADX 설치",
            listOf("pkg install jadx -y")),
        Triple("apktool", "7. APKTool 설치",
            listOf("pkg install apktool -y"))
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)

        // 헤더
        val tvHeader = TextView(this)
        tvHeader.text = "자동 설치 메뉴"
        tvHeader.textSize = 22f
        tvHeader.setTextColor(Color.WHITE)
        tvHeader.setPadding(0, 0, 0, 8)
        root.addView(tvHeader)

        val tvDesc = TextView(this)
        tvDesc.text = "버튼 누륵면 앱 내장 터미널에서 자동 실행됩니다\nTermux 설치 불필요!"
        tvDesc.textSize = 14f
        tvDesc.setTextColor(0xFFAAAAAA.toInt())
        tvDesc.setPadding(0, 0, 0, 16)
        root.addView(tvDesc)

        // 전체 실행 버튼 (빨간색)
        val btnAll = Button(this)
        btnAll.text = "0. 전체 순차 실행 (모두 설치)"
        btnAll.setBackgroundColor(0xFFdc2626.toInt())
        btnAll.setTextColor(Color.WHITE)
        btnAll.setOnClickListener {
            val allCommands = mutableListOf<String>()
            for ((_, _, cmds) in commands) {
                allCommands.addAll(cmds)
            }
            openTerminal("전체 설치", allCommands)
        }
        root.addView(btnAll)

        // 구분선
        val sep1 = TextView(this)
        sep1.text = ""
        sep1.setPadding(0, 8, 0, 8)
        root.addView(sep1)

        // 개별 버튼
        for ((cmdId, label, cmds) in commands) {
            val btn = Button(this)
            btn.text = label
            btn.setOnClickListener {
                openTerminal(label, cmds)
            }
            root.addView(btn)

            // 설명
            val tv = TextView(this)
            tv.textSize = 11f
            tv.setTextColor(0xFF888888.toInt())
            tv.setPadding(16, 4, 16, 12)
            tv.text = cmds.joinToString(" → ")
            root.addView(tv)
        }

        scroll.addView(root)
        setContentView(scroll)
    }

    /**
     * TerminalActivity 열어서 명령 자동 실행
     */
    private fun openTerminal(title: String, cmds: List<String>) {
        val intent = Intent(this, TerminalActivity::class.java)
        intent.putStringArrayListExtra("commands", ArrayList(cmds))
        intent.putExtra("title", title)
        startActivity(intent)
        Toast.makeText(this, "터미널에서 실행: $title", Toast.LENGTH_SHORT).show()
    }
}

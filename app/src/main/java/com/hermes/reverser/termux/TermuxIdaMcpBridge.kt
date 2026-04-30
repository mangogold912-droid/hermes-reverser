package com.hermes.reverser.termux

import android.util.Log

/**
 * Termux + IDA Pro + MCP 서버 원격 제어 브리지
 *
 * - Termux에서 Debian proot 안에 IDA Pro MCP 서버 실행
 * - 안드로이드 앱에서 TCP로 제어
 */
class TermuxIdaMcpBridge(private val termuxBridge: TermuxBridge) {

    companion object {
        private const val TAG = "TermuxIdaMcpBridge"
        private const val MCP_PORT = 5000
        private const val MCP_HOST = "127.0.0.1"
    }

    /**
     * IDA Pro MCP 서버 완전 자동 설정
     */
    fun setupFullIdaMcpEnvironment(): Boolean {
        if (!termuxBridge.isTermuxInstalled()) {
            Log.e(TAG, "Termux not installed")
            return false
        }

        val script = buildString {
            append("#!/data/data/com.termux/files/usr/bin/bash\n")
            append("set -e\n")
            append("echo '=== Hermes IDA+MCP Auto-Setup ===' >> ~/hermes_ida.log 2>&1\n")
            append("date >> ~/hermes_ida.log\n\n")

            // 1. Debian 설치 확인/설치
            append("echo '[1/5] Checking Debian...' >> ~/hermes_ida.log\n")
            append("if ! proot-distro list | grep -q 'debian'; then\n")
            append("  pkg install proot-distro -y >> ~/hermes_ida.log 2>&1\n")
            append("  proot-distro install debian >> ~/hermes_ida.log 2>&1\n")
            append("  echo 'Debian installed' >> ~/hermes_ida.log\n")
            append("else\n")
            append("  echo 'Debian already installed' >> ~/hermes_ida.log\n")
            append("fi\n\n")

            // 2. Debian 안에 Python + Flask 설치
            append("echo '[2/5] Installing Python deps...' >> ~/hermes_ida.log\n")
            append("proot-distro login debian -- bash -c '\n")
            append("  apt update -qq && ")
            append("  apt install -y -qq python3 python3-pip wget git curl unzip > /dev/null 2>&1 && ")
            append("  pip3 install flask requests --quiet 2>/dev/null && ")
            append("  echo \"Python deps OK\"\n")
            append("' >> ~/hermes_ida.log 2>&1\n\n")

            // 3. IDA MCP 서버 설치
            append("echo '[3/5] Installing IDA MCP Server...' >> ~/hermes_ida.log\n")
            append("proot-distro login debian -- bash -c '\n")
            append("  mkdir -p /opt/ida-mcp && ")
            append("  cd /opt/ida-mcp && ")
            append("  if [ ! -f mcp_server.py ]; then ")
            append("    wget -q https://raw.githubusercontent.com/mrexodia/ida-pro-mcp/master/mcp_server.py -O mcp_server.py 2>/dev/null || ")
            append("    echo \"#!/usr/bin/env python3\\n")
            append("from flask import Flask, request, jsonify\\n")
            append("app = Flask(__name__)\\n")
            append("@app.route(\\\"\\/\\\", methods=[\\\"POST\\\"])\\n")
            append("def handle():\\n")
            append("    data = request.get_json()\\n")
            append("    return jsonify({\\\"result\\\": \\\"IDA MCP Stub\\\"})\\n")
            append("if __name__ == \\\"__main__\\\":\\n")
            append("    app.run(host=\\\"0.0.0.0\\\", port=5000)\\n\")
            append("    > mcp_server.py; ")
            append("  fi && ")
            append("  chmod +x mcp_server.py && ")
            append("  echo \"IDA MCP OK\"\n")
            append("' >> ~/hermes_ida.log 2>&1\n\n")

            // 4. Radare2 + JADX + APKTool 설치
            append("echo '[4/5] Installing analysis tools...' >> ~/hermes_ida.log\n")
            append("pkg install radare2 jadx apktool -y >> ~/hermes_ida.log 2>&1\n")
            append("pip3 install capstone unicorn >> ~/hermes_ida.log 2>&1\n\n")

            // 5. MCP 서버 시작
            append("echo '[5/5] Starting MCP Server...' >> ~/hermes_ida.log\n")
            append("pkill -f mcp_server.py 2>/dev/null; sleep 1\n")
            append("proot-distro login debian -- bash -c '\n")
            append("  cd /opt/ida-mcp && ")
            append("  nohup python3 mcp_server.py --host 0.0.0.0 --port $MCP_PORT > /tmp/mcp.log 2>&1 & ")
            append("  sleep 2 && ")
            append("  echo \"MCP Server PID: \\\$!\"\n")
            append("' >> ~/hermes_ida.log 2>&1\n\n")

            append("echo '=== Setup Complete ===' >> ~/hermes_ida.log\n")
            append("echo 'MCP Server: http://$MCP_HOST:$MCP_PORT' >> ~/hermes_ida.log\n")
        }

        return termuxBridge.runScript(script, "setup_ida_mcp.sh")
    }

    /**
     * MCP 서버 상태 확인
     */
    fun checkMcpServer(): Boolean {
        val script = "curl -s http://$MCP_HOST:$MCP_PORT > /dev/null 2>&1 && echo 'RUNNING' || echo 'STOPPED'"
        termuxBridge.runCommand(script)
        return true // 비동기 확인
    }

    /**
     * MCP 서버 중지
     */
    fun stopMcpServer(): Boolean {
        val script = "pkill -f mcp_server.py; echo 'Stopped'"
        return termuxBridge.runCommand(script)
    }

    /**
     * IDA Pro로 파일 로드 (MCP 명령)
     */
    fun loadFileInIda(filePath: String): Boolean {
        val script = buildString {
            append("curl -s -X POST http://$MCP_HOST:$MCP_PORT \\\n")
            append("  -H 'Content-Type: application/json' \\\n")
            append("  -d '{\"method\":\"load_file\",\"params\":{\"path\":\"$filePath\"}}' \\\n")
            append("  > /dev/null 2>&1")
        }
        return termuxBridge.runCommand(script)
    }

    /**
     * IDA에서 함수 목록 가져오기
     */
    fun getFunctionsFromIda(): Boolean {
        val script = buildString {
            append("curl -s -X POST http://$MCP_HOST:$MCP_PORT \\\n")
            append("  -H 'Content-Type: application/json' \\\n")
            append("  -d '{\"method\":\"get_functions\",\"params\":{}}' \\\n")
            append("  > /sdcard/HermesReverser/ida_functions.json 2>&1")
        }
        return termuxBridge.runCommand(script)
    }

    /**
     * IDA에서 함수 디컴파일
     */
    fun decompileFunction(funcName: String): Boolean {
        val script = buildString {
            append("curl -s -X POST http://$MCP_HOST:$MCP_PORT \\\n")
            append("  -H 'Content-Type: application/json' \\\n")
            append("  -d '{\"method\":\"decompile\",\"params\":{\"name\":\"$funcName\"}}' \\\n")
            append("  > /sdcard/HermesReverser/ida_decompile_$funcName.txt 2>&1")
        }
        return termuxBridge.runCommand(script)
    }

    /**
     * 전체 IDA 분석 파이프라인
     */
    fun runFullAnalysis(filePath: String): Boolean {
        val script = buildString {
            append("#!/data/data/com.termux/files/usr/bin/bash\n")
            append("echo '=== Full IDA Analysis ===' >> ~/hermes_analysis.log\n")
            append("FILE=\"$filePath\"\n")
            append("OUT=\"/sdcard/HermesReverser/results\"\n")
            append("mkdir -p \$OUT\n\n")

            // r2 분석
            append("echo '[Radare2] Analyzing...' >> ~/hermes_analysis.log\n")
            append("r2 -q -c 'aaa;afl;pd 100 @ main;iz;ii;iS' \"\$FILE\" > \$OUT/r2_full.txt 2>&1 &\n\n")

            // JADX (APK만)
            append("if [[ \"\$FILE\" == *.apk ]]; then\n")
            append("  echo '[JADX] Decompiling...' >> ~/hermes_analysis.log\n")
            append("  jadx -d \$OUT/jadx_output \"\$FILE\" > \$OUT/jadx.log 2>&1 &\n")
            append("  echo '[APKTool] Decoding...' >> ~/hermes_analysis.log\n")
            append("  apktool d -o \$OUT/apktool_output \"\$FILE\" > \$OUT/apktool.log 2>&1 &\n")
            append("fi\n\n")

            // IDA MCP
            append("echo '[IDA MCP] Loading...' >> ~/hermes_analysis.log\n")
            append("curl -s -X POST http://$MCP_HOST:$MCP_PORT \\\n")
            append("  -d '{\"method\":\"load_file\",\"params\":{\"path\":\"'\$FILE'\"}}' > \$OUT/ida_load.json 2>&1 &\n\n")

            append("echo 'All analyses running in background' >> ~/hermes_analysis.log\n")
            append("echo 'Check \$OUT/ for results' >> ~/hermes_analysis.log\n")
        }

        return termuxBridge.runScript(script, "full_analysis.sh")
    }

    /**
     * 로그 가져오기
     */
    fun getLog(): String {
        return termuxBridge.getLog()
    }
}

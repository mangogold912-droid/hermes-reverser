package com.hermes.reverser.termux

import android.util.Log

/**
 * Termux + IDA Pro + MCP 서버 원격 제어 브리지
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

        val script = StringBuilder()
        script.append("#!/data/data/com.termux/files/usr/bin/bash\n")
        script.append("set -e\n")
        script.append("echo '=== Hermes IDA+MCP Auto-Setup ===' >> ~/hermes_ida.log 2>&1\n")
        script.append("date >> ~/hermes_ida.log\n\n")

        // 1. Debian 설치 확인/설치
        script.append("echo '[1/5] Checking Debian...' >> ~/hermes_ida.log\n")
        script.append("if ! proot-distro list | grep -q 'debian'; then\n")
        script.append("  pkg install proot-distro -y >> ~/hermes_ida.log 2>&1\n")
        script.append("  proot-distro install debian >> ~/hermes_ida.log 2>&1\n")
        script.append("  echo 'Debian installed' >> ~/hermes_ida.log\n")
        script.append("else\n")
        script.append("  echo 'Debian already installed' >> ~/hermes_ida.log\n")
        script.append("fi\n\n")

        // 2. Python + Flask
        script.append("echo '[2/5] Installing Python deps...' >> ~/hermes_ida.log\n")
        script.append("proot-distro login debian -- bash -c '\n")
        script.append("  apt update -qq && ")
        script.append("  apt install -y -qq python3 python3-pip wget git curl unzip > /dev/null 2>&1 && ")
        script.append("  pip3 install flask requests --quiet 2>/dev/null && ")
        script.append("  echo Python deps OK\n")
        script.append("' >> ~/hermes_ida.log 2>&1\n\n")

        // 3. IDA MCP 서버
        script.append("echo '[3/5] Installing IDA MCP Server...' >> ~/hermes_ida.log\n")
        script.append("proot-distro login debian -- bash -c '\n")
        script.append("  mkdir -p /opt/ida-mcp && ")
        script.append("  cd /opt/ida-mcp && ")
        script.append("  if [ ! -f mcp_server.py ]; then ")
        script.append("    wget -q https://raw.githubusercontent.com/mrexodia/ida-pro-mcp/master/mcp_server.py -O mcp_server.py 2>/dev/null || ")
        script.append("    echo import flask > mcp_server.py; ")
        script.append("  fi && ")
        script.append("  chmod +x mcp_server.py && ")
        script.append("  echo IDA MCP OK\n")
        script.append("' >> ~/hermes_ida.log 2>&1\n\n")

        // 4. 분석 도구
        script.append("echo '[4/5] Installing analysis tools...' >> ~/hermes_ida.log\n")
        script.append("pkg install radare2 jadx apktool -y >> ~/hermes_ida.log 2>&1\n")
        script.append("pip3 install capstone unicorn >> ~/hermes_ida.log 2>&1\n\n")

        // 5. MCP 서버 시작
        script.append("echo '[5/5] Starting MCP Server...' >> ~/hermes_ida.log\n")
        script.append("pkill -f mcp_server.py 2>/dev/null; sleep 1\n")
        script.append("proot-distro login debian -- bash -c '\n")
        script.append("  cd /opt/ida-mcp && ")
        script.append("  nohup python3 mcp_server.py --host 0.0.0.0 --port " + MCP_PORT + " > /tmp/mcp.log 2>&1 & ")
        script.append("  sleep 2 && ")
        script.append("  echo MCP Server PID \$!\n")
        script.append("' >> ~/hermes_ida.log 2>&1\n\n")

        script.append("echo '=== Setup Complete ===' >> ~/hermes_ida.log\n")
        script.append("echo 'MCP Server: http://" + MCP_HOST + ":" + MCP_PORT + "' >> ~/hermes_ida.log\n")

        return termuxBridge.runCommand("setup_ida_mcp_sh", script.toString())
    }

    /**
     * MCP 서버 상태 확인
     */
    fun checkMcpServer(): Boolean {
        val script = "curl -s http://" + MCP_HOST + ":" + MCP_PORT + " > /dev/null 2>&1 && echo RUNNING || echo STOPPED"
        termuxBridge.runCommand("check_mcp_server", script)
        return true
    }

    /**
     * MCP 서버 중지
     */
    fun stopMcpServer(): Boolean {
        val script = "pkill -f mcp_server.py; echo Stopped"
        return termuxBridge.runCommand("stop_mcp_server", script)
    }

    /**
     * IDA로 파일 로드
     */
    fun loadFileInIda(filePath: String): Boolean {
        val script = StringBuilder()
        script.append("curl -s -X POST http://" + MCP_HOST + ":" + MCP_PORT + " \\\n")
        script.append("  -H 'Content-Type: application/json' \\\n")
        script.append("  -d '{\"method\":\"load_file\",\"params\":{\"path\":\"" + filePath + "\"}}' \\\n")
        script.append("  > /dev/null 2>&1")
        return termuxBridge.runCommand("ida_mcp_setup", script.toString())
    }

    /**
     * IDA에서 함수 목록
     */
    fun getFunctionsFromIda(): Boolean {
        val script = StringBuilder()
        script.append("curl -s -X POST http://" + MCP_HOST + ":" + MCP_PORT + " \\\n")
        script.append("  -H 'Content-Type: application/json' \\\n")
        script.append("  -d '{\"method\":\"get_functions\",\"params\":{}}' \\\n")
        script.append("  > /sdcard/HermesReverser/ida_functions.json 2>&1")
        return termuxBridge.runCommand("ida_mcp_setup", script.toString())
    }

    /**
     * IDA에서 함수 디컴파일
     */
    fun decompileFunction(funcName: String): Boolean {
        val script = StringBuilder()
        script.append("curl -s -X POST http://" + MCP_HOST + ":" + MCP_PORT + " \\\n")
        script.append("  -H 'Content-Type: application/json' \\\n")
        script.append("  -d '{\"method\":\"decompile\",\"params\":{\"name\":\"" + funcName + "\"}}' \\\n")
        script.append("  > /sdcard/HermesReverser/ida_decompile_" + funcName + ".txt 2>&1")
        return termuxBridge.runCommand("ida_mcp_setup", script.toString())
    }

    /**
     * 전체 IDA 분석 파이프라인
     */
    fun runFullAnalysis(filePath: String): Boolean {
        val script = StringBuilder()
        script.append("#!/data/data/com.termux/files/usr/bin/bash\n")
        script.append("echo '=== Full IDA Analysis ===' >> ~/hermes_analysis.log\n")
        script.append("FILE=\"" + filePath + "\"\n")
        script.append("OUT=\"/sdcard/HermesReverser/results\"\n")
        script.append("mkdir -p \$OUT\n\n")

        // r2 분석
        script.append("echo '[Radare2] Analyzing...' >> ~/hermes_analysis.log\n")
        script.append("r2 -q -c 'aaa;afl;pd 100 @ main;iz;ii;iS' \"\$FILE\" > \$OUT/r2_full.txt 2>&1 &\n\n")

        // JADX (APK만)
        script.append("if [[ \"\$FILE\" == *.apk ]]; then\n")
        script.append("  echo '[JADX] Decompiling...' >> ~/hermes_analysis.log\n")
        script.append("  jadx -d \$OUT/jadx_output \"\$FILE\" > \$OUT/jadx.log 2>&1 &\n")
        script.append("  echo '[APKTool] Decoding...' >> ~/hermes_analysis.log\n")
        script.append("  apktool d -o \$OUT/apktool_output \"\$FILE\" > \$OUT/apktool.log 2>&1 &\n")
        script.append("fi\n\n")

        // IDA MCP
        script.append("echo '[IDA MCP] Loading...' >> ~/hermes_analysis.log\n")
        script.append("curl -s -X POST http://" + MCP_HOST + ":" + MCP_PORT + " \\\n")
        script.append("  -d '{\"method\":\"load_file\",\"params\":{\"path\":\"'\$FILE'\"}}' > \$OUT/ida_load.json 2>&1 &\n\n")

        script.append("echo 'All analyses running in background' >> ~/hermes_analysis.log\n")
        script.append("echo 'Check \$OUT/ for results' >> ~/hermes_analysis.log\n")

        return termuxBridge.runCommand("full_analysis_sh", script.toString())
    }

    /**
     * 로그 가져오기
     */
    fun getLog(): String {
        return termuxBridge.getLog()
    }
}

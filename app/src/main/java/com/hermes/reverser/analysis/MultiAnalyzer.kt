package com.hermes.reverser.analysis

import android.content.Context
import android.util.Log
import com.hermes.reverser.ai.AIMultiEngine
import com.hermes.reverser.ai.AnalysisResult
import com.hermes.reverser.ida.IdaProMobileEngine
import com.hermes.reverser.model.BinaryInfo
import com.hermes.reverser.termux.TermuxBridge
import com.hermes.reverser.utils.NativeBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 멀티-엔진 병렬 분석 오케스트레이터
 *
 * 통합 분석기 목록:
 * 1. IDA Pro Mobile (앱 내장) - ARM/x86 디스어셈블리, 함수 탐지
 * 2. Capstone Native (JNI) - 저수준 디스어셈블리
 * 3. Radare2 (Termux) - 전체 리버싱 프레임워크
 * 4. Ghidra Headless (Termux) - NSA 오픈소스 분석
 * 5. JADX (Termux) - DEX/Java 디컴파일
 * 6. APKTool (Termux) - APK 리소스 디코딩
 * 7. Unidbg (Termux) - 유니콘 에뮬레이션
 * 8. 8개 AI 플랫폼 병렬 분석
 */
class MultiAnalyzer(context: Context) {

    private val idaEngine = IdaProMobileEngine()
    private val nativeBridge = NativeBridge()
    private val termuxBridge = TermuxBridge(context)
    private val aiEngine = AIMultiEngine()
    private val mutex = Mutex()

    companion object {
        private const val TAG = "MultiAnalyzer"
    }

    /**
     * 분석기 종류
     */
    enum class AnalyzerType(val displayName: String, val description: String) {
        IDA_MOBILE("IDA Pro Mobile", "Built-in disassembler and function detection"),
        CAPSTONE_NATIVE("Capstone Native", "JNI disassembly engine"),
        RADARE2("Radare2", "Terminal-based reverse engineering framework"),
        GHIDRA("Ghidra Headless", "NSA SRE suite (headless mode)"),
        JADX("JADX", "DEX to Java decompiler"),
        APKTOOL("APKTool", "APK resource decoder"),
        UNIDBG("Unidbg", "Unicorn-based emulator"),
        AI_OPENAI("AI-OpenAI", "OpenAI GPT-4 analysis"),
        AI_KIMI("AI-Kimi", "Kimi AI analysis"),
        AI_QWEN("AI-Qwen", "Qwen AI analysis"),
        AI_GEMINI("AI-Gemini", "Gemini AI analysis"),
        AI_CLAUDE("AI-Claude", "Claude AI analysis"),
        AI_DEEPSEEK("AI-DeepSeek", "DeepSeek AI analysis"),
        AI_OLLAMA("AI-Ollama", "Local Ollama analysis"),
        AI_SUPRNINJA("AI-SuperNinja", "SuperNinja AI analysis");

        companion object {
            fun all(): List<AnalyzerType> = values().toList()
            fun local(): List<AnalyzerType> = listOf(IDA_MOBILE, CAPSTONE_NATIVE)
            fun termux(): List<AnalyzerType> = listOf(RADARE2, GHIDRA, JADX, APKTOOL, UNIDBG)
            fun ai(): List<AnalyzerType> = listOf(
                AI_OPENAI, AI_KIMI, AI_QWEN, AI_GEMINI, AI_CLAUDE,
                AI_DEEPSEEK, AI_OLLAMA, AI_SUPRNINJA
            )
        }
    }

    /**
     * 분석 결과
     */
    data class AnalyzerResult(
        val type: AnalyzerType,
        val success: Boolean,
        val output: String = "",
        val error: String = "",
        val latencyMs: Long = 0,
        val metadata: Map<String, String> = emptyMap()
    )

    /**
     * 전체 병렬 분석 실행
     */
    suspend fun analyzeFull(filePath: String, bytes: ByteArray): Map<AnalyzerType, AnalyzerResult> = coroutineScope {
        val results = mutableMapOf<AnalyzerType, AnalyzerResult>()
        val binaryInfo = BinaryInfo(
            filePath = filePath,
            fileName = filePath.substringAfterLast("/"),
            fileSize = bytes.size.toLong()
        )

        // 모든 분석기를 동시에 실행
        val jobs = mutableListOf<Deferred<Unit>>()

        // 1. 로컬 분석기 (앱 내장)
        for (analyzer in AnalyzerType.local()) {
            jobs.add(async(Dispatchers.IO) {
                val result = runLocalAnalyzer(analyzer, filePath, bytes, binaryInfo)
                mutex.withLock { results[analyzer] = result }
            })
        }

        // 2. Termux 분석기 (설치된 경우만)
        if (termuxBridge.isTermuxInstalled()) {
            for (analyzer in AnalyzerType.termux()) {
                jobs.add(async(Dispatchers.IO) {
                    val result = runTermuxAnalyzer(analyzer, filePath, bytes)
                    mutex.withLock { results[analyzer] = result }
                })
            }
        }

        // 3. AI 분석기 (8개 플랫폼)
        jobs.add(async(Dispatchers.IO) {
            val aiResults = aiEngine.analyzeBinaryParallel(binaryInfo, "Analyze this binary: $filePath")
            for ((platform, aiResult) in aiResults) {
                val type = AnalyzerType.all().find { it.displayName.contains(platform.name, ignoreCase = true) }
                if (type != null) {
                    mutex.withLock {
                        results[type] = AnalyzerResult(
                            type = type,
                            success = aiResult.success,
                            output = aiResult.rawResponse,
                            error = aiResult.errorMessage,
                            latencyMs = aiResult.latencyMs
                        )
                    }
                }
            }
        })

        jobs.awaitAll()
        results
    }

    /**
     * 로컬 분석기 실행
     */
    private fun runLocalAnalyzer(
        type: AnalyzerType,
        filePath: String,
        bytes: ByteArray,
        binaryInfo: BinaryInfo
    ): AnalyzerResult {
        val start = System.currentTimeMillis()
        return try {
            when (type) {
                AnalyzerType.IDA_MOBILE -> {
                    val info = idaEngine.loadBinary(filePath, bytes)
                    val functions = idaEngine.detectFunctions(bytes, 0x1000)
                    val disasm = idaEngine.disassemble(bytes.take(4096).toByteArray(), 0x1000, "ARM")
                    val hexDump = idaEngine.hexDump(bytes, 0, minOf(256, bytes.size))

                    val sb = StringBuilder()
                    sb.append("=== IDA Pro Mobile Analysis ===\n")
                    sb.append("File Type: ${info.fileType}\n")
                    sb.append("Size: ${info.fileSize} bytes\n")
                    sb.append("Functions: ${functions.size}\n\n")
                    sb.append("=== Hex Dump (first 256 bytes) ===\n")
                    sb.append(hexDump).append("\n")
                    sb.append("=== Disassembly ===\n")
                    for (line in disasm.take(50)) {
                        sb.append(String.format("0x%08X: %s %s\n", line.address, line.mnemonic, line.operands))
                    }
                    sb.append("\n=== Functions ===\n")
                    for (func in functions.take(20)) {
                        sb.append(String.format("0x%08X - 0x%08X: %s (%d bytes)\n",
                            func.startAddress, func.endAddress, func.name, func.size))
                    }

                    AnalyzerResult(type, true, sb.toString(), latencyMs = System.currentTimeMillis() - start,
                        metadata = mapOf("functions" to functions.size.toString()))
                }
                AnalyzerType.CAPSTONE_NATIVE -> {
                    val disasm = nativeBridge.disassemble(bytes.take(4096).toByteArray(), 0x1000, "ARM")
                    val header = nativeBridge.analyzeHeader(bytes)
                    val strings = nativeBridge.extractStrings(bytes, 4)

                    val sb = StringBuilder()
                    sb.append("=== Capstone Native Analysis ===\n")
                    sb.append("Header: $header\n\n")
                    sb.append("=== Disassembly ===\n")
                    sb.append(disasm).append("\n")
                    sb.append("=== Strings (${strings.size}) ===\n")
                    for (s in strings.take(30)) {
                        sb.append("  $s\n")
                    }

                    AnalyzerResult(type, true, sb.toString(), latencyMs = System.currentTimeMillis() - start,
                        metadata = mapOf("strings" to strings.size.toString()))
                }
                else -> AnalyzerResult(type, false, error = "Not a local analyzer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local analyzer error: ${e.message}")
            AnalyzerResult(type, false, error = e.message ?: "Unknown error",
                latencyMs = System.currentTimeMillis() - start)
        }
    }

    /**
     * Termux 분석기 실행
     */
    private fun runTermuxAnalyzer(type: AnalyzerType, filePath: String, bytes: ByteArray): AnalyzerResult {
        val start = System.currentTimeMillis()
        return try {
            val destFile = "/sdcard/HermesReverser/" + filePath.substringAfterLast("/")
            val command = buildTermuxCommand(type, destFile)

            val script = buildString {
                append("mkdir -p /sdcard/HermesReverser/results\n")
                append("cp \"$filePath\" \"$destFile\" 2>/dev/null\n")
                append("$command > /sdcard/HermesReverser/results/${type.name.lowercase()}.txt 2>&1\n")
                append("echo '[DONE]' >> /sdcard/HermesReverser/results/${type.name.lowercase()}.txt\n")
            }

            val success = termuxBridge.runCommand(script)
            AnalyzerResult(
                type = type,
                success = success,
                output = if (success) "Analysis started in Termux. Check /sdcard/HermesReverser/results/" else "",
                error = if (!success) "Failed to run ${type.displayName}" else "",
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.e(TAG, "Termux analyzer error: ${e.message}")
            AnalyzerResult(type, false, error = e.message ?: "Unknown error",
                latencyMs = System.currentTimeMillis() - start)
        }
    }

    /**
     * Termux 명령어 생성
     */
    private fun buildTermuxCommand(type: AnalyzerType, filePath: String): String {
        val resultDir = "/sdcard/HermesReverser/results"
        return when (type) {
            AnalyzerType.RADARE2 -> {
                buildString {
                    append("r2 -q -c '")
                    append("aaa;") // Analyze all
                    append("afl;") // List functions
                    append("pd 100 @ main;") // Disassemble main
                    append("iz;") // Strings
                    append("ii;") // Imports
                    append("iS;") // Sections
                    append("' \"$filePath\"")
                }
            }
            AnalyzerType.GHIDRA -> {
                // Ghidra headless mode
                buildString {
                    append("mkdir -p ~/ghidra_projects && ")
                    append("~/ghidra/support/analyzeHeadless ")
                    append("~/ghidra_projects HermesProject ")
                    append("-import \"$filePath\" ")
                    append("-postScript /dev/stdin ")
                    append("-scriptPath ~/ghidra_scripts ")
                    append("2>&1 | tee $resultDir/ghidra.txt")
                }
            }
            AnalyzerType.JADX -> {
                if (filePath.endsWith(".apk") || filePath.endsWith(".dex")) {
                    "jadx -d $resultDir/jadx_output \"$filePath\" 2>&1"
                } else {
                    "echo 'JADX only supports APK/DEX files'"
                }
            }
            AnalyzerType.APKTOOL -> {
                if (filePath.endsWith(".apk")) {
                    "apktool d -o $resultDir/apktool_output \"$filePath\" 2>&1"
                } else {
                    "echo 'APKTool only supports APK files'"
                }
            }
            AnalyzerType.UNIDBG -> {
                buildString {
                    append("python3 -c '")
                    append("from unidbg import unicorn; ")
                    append("print(\"Unidbg: Placeholder for emulation\")")
                    append("' 2>&1")
                }
            }
            else -> "echo 'Unknown analyzer: ${type.displayName}'"
        }
    }

    /**
     * Termux 도구 설치 명령
     */
    fun getInstallCommand(type: AnalyzerType): String {
        return when (type) {
            AnalyzerType.RADARE2 -> "pkg install radare2 -y"
            AnalyzerType.GHIDRA -> {
                buildString {
                    append("pkg install openjdk-17 -y && ")
                    append("cd ~ && ")
                    append("wget https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_11.0_build/ghidra_11.0_PUBLIC_20230928.zip && ")
                    append("unzip ghidra_11.0_PUBLIC_20230928.zip && ")
                    append("mv ghidra_11.0_PUBLIC ghidra")
                }
            }
            AnalyzerType.JADX -> "pkg install jadx -y"
            AnalyzerType.APKTOOL -> "pkg install apktool -y"
            AnalyzerType.UNIDBG -> "pip3 install unidbg"
            else -> ""
        }
    }

    /**
     * 설치 상태 확인
     */
    suspend fun checkInstallations(): Map<AnalyzerType, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<AnalyzerType, Boolean>()

        // 로컬 분석기는 항상 사용 가능
        for (analyzer in AnalyzerType.local()) {
            results[analyzer] = true
        }

        // AI 분석기는 API 키만 있으면 사용 가능
        for (analyzer in AnalyzerType.ai()) {
            results[analyzer] = true // 기본값
        }

        // Termux 분석기는 설치 확인
        if (termuxBridge.isTermuxInstalled()) {
            for (analyzer in AnalyzerType.termux()) {
                val cmd = when (analyzer) {
                    AnalyzerType.RADARE2 -> "which r2"
                    AnalyzerType.GHIDRA -> "test -d ~/ghidra"
                    AnalyzerType.JADX -> "which jadx"
                    AnalyzerType.APKTOOL -> "which apktool"
                    AnalyzerType.UNIDBG -> "pip3 show unidbg"
                    else -> "false"
                }
                val script = "$cmd >/dev/null 2>&1 && echo 'INSTALLED' || echo 'NOT_INSTALLED'"
                termuxBridge.runCommand(script)
                // 비동기 결과는 파일로 확인
                results[analyzer] = false // 기본값, 실제 확인은 별도
            }
        } else {
            for (analyzer in AnalyzerType.termux()) {
                results[analyzer] = false
            }
        }

        results
    }

    /**
     * 빠른 분석 (로컬 + AI만)
     */
    suspend fun analyzeQuick(filePath: String, bytes: ByteArray): Map<AnalyzerType, AnalyzerResult> = coroutineScope {
        val results = mutableMapOf<AnalyzerType, AnalyzerResult>()
        val binaryInfo = BinaryInfo(
            filePath = filePath,
            fileName = filePath.substringAfterLast("/"),
            fileSize = bytes.size.toLong()
        )

        // 로컬 분석
        for (analyzer in AnalyzerType.local()) {
            val result = runLocalAnalyzer(analyzer, filePath, bytes, binaryInfo)
            results[analyzer] = result
        }

        // AI 분석
        val aiResults = aiEngine.analyzeBinaryParallel(binaryInfo, "Quick analysis of $filePath")
        for ((platform, aiResult) in aiResults) {
            val type = AnalyzerType.all().find { it.displayName.contains(platform.name, ignoreCase = true) }
            if (type != null) {
                results[type] = AnalyzerResult(
                    type = type,
                    success = aiResult.success,
                    output = aiResult.rawResponse,
                    error = aiResult.errorMessage,
                    latencyMs = aiResult.latencyMs
                )
            }
        }

        results
    }
}

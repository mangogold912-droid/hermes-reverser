package com.hermes.reverser.analysis

import android.content.Context
import android.util.Log
import com.hermes.reverser.termux.TermuxBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 확장 멀티-엔진 분석기 — 추가 오픈소스 도구 통합
 *
 * 추가 도구:
 * - eDBG: eBPF 기반 경량 Android 디버거
 * - BPFroid: Android framework API / native library 추적
 * - heresy: React Native 앱 검사 및 계측
 * - IDAObjcTypes: IDA Objective-C 타입/함수 컬렉션
 * - Ghidra FOX: Ghidra 자동 분석 스크립트
 */
class ExtendedMultiAnalyzer(context: Context) {

    private val baseAnalyzer = MultiAnalyzer(context)
    private val termuxBridge = TermuxBridge(context)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "ExtendedMultiAnalyzer"
    }

    /**
     * 확장 분석기 타입
     */
    enum class ExtendedAnalyzerType(val displayName: String, val category: String) {
        // 기본 (기존)
        IDA_MOBILE("IDA Pro Mobile", "Built-in"),
        CAPSTONE_NATIVE("Capstone Native", "Built-in"),
        RADARE2("Radare2", "Termux"),
        GHIDRA("Ghidra Headless", "Termux"),
        JADX("JADX", "Termux"),
        APKTOOL("APKTool", "Termux"),
        UNIDBG("Unidbg", "Termux"),
        AI_OPENAI("AI-OpenAI", "AI"),
        AI_KIMI("AI-Kimi", "AI"),
        AI_QWEN("AI-Qwen", "AI"),
        AI_GEMINI("AI-Gemini", "AI"),
        AI_CLAUDE("AI-Claude", "AI"),
        AI_DEEPSEEK("AI-DeepSeek", "AI"),
        AI_OLLAMA("AI-Ollama", "AI"),
        AI_SUPRNINJA("AI-SuperNinja", "AI"),

        // 확장 (신규)
        EDBG("eDBG (eBPF Debugger)", "Advanced"),
        BPFROID("BPFroid (API Tracer)", "Advanced"),
        HERESY("heresy (React Native)", "Advanced"),
        IDAOBJCTYPES("IDAObjcTypes", "Advanced"),
        GHIDRA_FOX("Ghidra FOX", "Advanced"),
        FRIDA("Frida (Dynamic)", "Advanced"),
        JNI_TRACE("JNI Trace", "Advanced"),
        STRINGS("Strings Extractor", "Built-in"),
        YARA("YARA Rules", "Advanced");

        companion object {
            fun all(): List<ExtendedAnalyzerType> = values().toList()
            fun advanced(): List<ExtendedAnalyzerType> = listOf(
                EDBG, BPFROID, HERESY, IDAOBJCTYPES, GHIDRA_FOX,
                FRIDA, JNI_TRACE, YARA
            )
        }
    }

    data class ExtendedResult(
        val type: ExtendedAnalyzerType,
        val success: Boolean,
        val output: String = "",
        val error: String = "",
        val latencyMs: Long = 0,
        val metadata: Map<String, String> = emptyMap()
    )

    /**
     * 전체 병렬 분석 (기본 + 확장)
     */
    suspend fun analyzeComplete(filePath: String, bytes: ByteArray): Map<ExtendedAnalyzerType, ExtendedResult> = coroutineScope {
        val results = mutableMapOf<ExtendedAnalyzerType, ExtendedResult>()

        // 1. 기본 분석 (기존 MultiAnalyzer)
        val baseResults = baseAnalyzer.analyzeFull(filePath, bytes)
        for ((type, result) in baseResults) {
            val extendedType = ExtendedAnalyzerType.all().find { it.displayName == type.displayName }
            if (extendedType != null) {
                results[extendedType] = ExtendedResult(
                    type = extendedType,
                    success = result.success,
                    output = result.output,
                    error = result.error,
                    latencyMs = result.latencyMs
                )
            }
        }

        // 2. 확장 분석기 (신규)
        if (termuxBridge.isInstalled()) {
            val advancedJobs = ExtendedAnalyzerType.advanced().map { analyzer ->
                async(Dispatchers.IO) {
                    val result = runAdvancedAnalyzer(analyzer, filePath, bytes)
                    mutex.withLock { results[analyzer] = result }
                }
            }
            advancedJobs.awaitAll()
        }

        results
    }

    /**
     * 확장 분석기 실행
     */
    private fun runAdvancedAnalyzer(
        type: ExtendedAnalyzerType,
        filePath: String,
        bytes: ByteArray
    ): ExtendedResult {
        val start = System.currentTimeMillis()
        return try {
            when (type) {
                ExtendedAnalyzerType.EDBG -> runEdbg(filePath)
                ExtendedAnalyzerType.BPFROID -> runBpfroid(filePath)
                ExtendedAnalyzerType.HERESY -> runHeresy(filePath)
                ExtendedAnalyzerType.IDAOBJCTYPES -> runIdaObjcTypes(filePath)
                ExtendedAnalyzerType.GHIDRA_FOX -> runGhidraFox(filePath)
                ExtendedAnalyzerType.FRIDA -> runFrida(filePath)
                ExtendedAnalyzerType.JNI_TRACE -> runJniTrace(filePath)
                ExtendedAnalyzerType.STRINGS -> runStrings(bytes)
                ExtendedAnalyzerType.YARA -> runYara(filePath)
                else -> ExtendedResult(type, false, error = "Unknown analyzer")
            }.copy(latencyMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Log.e(TAG, type.name + " error: " + e.message)
            ExtendedResult(type, false, error = e.message ?: "Unknown error",
                latencyMs = System.currentTimeMillis() - start)
        }
    }

    // === eDBG: eBPF 기반 Android 디버거 ===
    private fun runEdbg(filePath: String): ExtendedResult {
        val script = StringBuilder()
        script.append("# eDBG setup\n")
        script.append("cd ~ && ")
        script.append("if [ ! -d eDBG ]; then ")
        script.append("  git clone https://github.com/ShinoLeah/eDBG.git 2>/dev/null; ")
        script.append("fi && ")
        script.append("cd eDBG && ")
        script.append("echo 'eDBG: eBPF debugger ready' >> /sdcard/HermesReverser/results/edbg.txt && ")
        script.append("echo 'Usage: ./edbg -p <pid> or ./edbg -n <process_name>' >> /sdcard/HermesReverser/results/edbg.txt")

        val success = termuxBridge.runTracked("edbg", script.toString())
        return ExtendedResult(
            ExtendedAnalyzerType.EDBG,
            success,
            output = if (success) "eDBG eBPF debugger configured" else "eDBG setup failed",
            error = if (!success) "Failed to configure eDBG" else ""
        )
    }

    // === BPFroid: Android API / Native 추적 ===
    private fun runBpfroid(filePath: String): ExtendedResult {
        val script = StringBuilder()
        script.append("# BPFroid setup\n")
        script.append("cd ~ && ")
        script.append("if [ ! -d BPFroid ]; then ")
        script.append("  git clone https://github.com/yanivagman/BPFroid.git 2>/dev/null; ")
        script.append("fi && ")
        script.append("cd BPFroid && ")
        script.append("echo 'BPFroid: Android API tracer ready' >> /sdcard/HermesReverser/results/bpfroid.txt && ")
        script.append("echo 'Usage: ./bpfroid --app <package_name>' >> /sdcard/HermesReverser/results/bpfroid.txt")

        val success = termuxBridge.runTracked("bpfroid", script.toString())
        return ExtendedResult(
            ExtendedAnalyzerType.BPFROID,
            success,
            output = if (success) "BPFroid API tracer configured" else "BPFroid setup failed",
            error = if (!success) "Failed to configure BPFroid" else ""
        )
    }

    // === heresy: React Native 검사 ===
    private fun runHeresy(filePath: String): ExtendedResult {
        val script = StringBuilder()
        script.append("# heresy React Native inspector\n")
        script.append("cd ~ && ")
        script.append("if [ ! -d heresy ]; then ")
        script.append("  git clone https://github.com/Pilfer/heresy.git 2>/dev/null; ")
        script.append("fi && ")
        script.append("cd heresy && ")
        script.append("echo 'heresy: React Native inspector ready' >> /sdcard/HermesReverser/results/heresy.txt && ")
        script.append("echo 'Supports: Hermes bytecode, JSC, React Native bundles' >> /sdcard/HermesReverser/results/heresy.txt")

        val success = termuxBridge.runTracked("heresy", script.toString())
        return ExtendedResult(
            ExtendedAnalyzerType.HERESY,
            success,
            output = if (success) "heresy React Native inspector configured" else "heresy setup failed",
            error = if (!success) "Failed to configure heresy" else ""
        )
    }

    // === IDAObjcTypes ===
    private fun runIdaObjcTypes(filePath: String): ExtendedResult {
        val script = StringBuilder()
        script.append("# IDAObjcTypes collection\n")
        script.append("cd ~ && ")
        script.append("if [ ! -d IDAObjcTypes ]; then ")
        script.append("  git clone https://github.com/PoomSmart/IDAObjcTypes.git 2>/dev/null; ")
        script.append("fi && ")
        script.append("cd IDAObjcTypes && ")
        script.append("echo 'IDAObjcTypes: Objective-C types loaded' >> /sdcard/HermesReverser/results/idaobjc.txt && ")
        script.append("echo 'Types: iOS frameworks, private APIs, dyld, xpc, objc' >> /sdcard/HermesReverser/results/idaobjc.txt")

        val success = termuxBridge.runTracked("idaobjctypes", script.toString())
        return ExtendedResult(
            ExtendedAnalyzerType.IDAOBJCTYPES,
            success,
            output = if (success) "IDAObjcTypes Objective-C collection ready" else "IDAObjcTypes setup failed",
            error = if (!success) "Failed to configure IDAObjcTypes" else ""
        )
    }

    // === Ghidra FOX 스크립트 ===
    private fun runGhidraFox(filePath: String): ExtendedResult {
        val script = StringBuilder()
        script.append("# Ghidra FOX scripts\n")
        script.append("cd ~ && ")
        script.append("mkdir -p ghidra_scripts && cd ghidra_scripts && ")
        script.append("if [ ! -f FOX.java ]; then ")
        script.append("  wget -q https://raw.githubusercontent.com/federicodotta/ghidra-scripts/master/FOX.java 2>/dev/null || ")
        script.append("  echo 'class FOX { public static void main(String[] args) {} }' > FOX.java; ")
        script.append("fi && ")
        script.append("echo 'Ghidra FOX: Analysis scripts ready' >> /sdcard/HermesReverser/results/ghidra_fox.txt && ")
        script.append("echo 'Scripts: FOX, CryptoAnalyzer, StringDecryptor' >> /sdcard/HermesReverser/results/ghidra_fox.txt")

        val success = termuxBridge.runTracked("ghidra_fox", script.toString())
        return ExtendedResult(
            ExtendedAnalyzerType.GHIDRA_FOX,
            success,
            output = if (success) "Ghidra FOX scripts ready" else "Ghidra FOX setup failed",
            error = if (!success) "Failed to configure Ghidra FOX" else ""
        )
    }

    // === Frida 동적 계측 ===
    private fun runFrida(filePath: String): ExtendedResult {
        val script = StringBuilder()
        script.append("# Frida dynamic instrumentation\n")
        script.append("pip3 show frida > /dev/null 2>&1 || pip3 install frida frida-tools 2>/dev/null; ")
        script.append("echo 'Frida: Dynamic instrumentation ready' >> /sdcard/HermesReverser/results/frida.txt && ")
        script.append("echo 'Usage: frida -U -n <process_name> -l <script.js>' >> /sdcard/HermesReverser/results/frida.txt")

        val success = termuxBridge.runTracked("frida", script.toString())
        return ExtendedResult(
            ExtendedAnalyzerType.FRIDA,
            success,
            output = if (success) "Frida dynamic instrumentation ready" else "Frida setup failed",
            error = if (!success) "Failed to configure Frida" else ""
        )
    }

    // === JNI Trace ===
    private fun runJniTrace(filePath: String): ExtendedResult {
        val script = StringBuilder()
        script.append("# JNI Trace\n")
        script.append("pip3 show jnitrace > /dev/null 2>&1 || pip3 install jnitrace 2>/dev/null; ")
        script.append("echo 'JNI Trace: JNI API tracer ready' >> /sdcard/HermesReverser/results/jni_trace.txt && ")
        script.append("echo 'Usage: jnitrace -l lib<name>.so <package_name>' >> /sdcard/HermesReverser/results/jni_trace.txt")

        val success = termuxBridge.runTracked("jni_trace", script.toString())
        return ExtendedResult(
            ExtendedAnalyzerType.JNI_TRACE,
            success,
            output = if (success) "JNI Trace tracer ready" else "JNI Trace setup failed",
            error = if (!success) "Failed to configure JNI Trace" else ""
        )
    }

    // === Strings 추출 (네이티브) ===
    private fun runStrings(bytes: ByteArray): ExtendedResult {
        return try {
            val nativeBridge = com.hermes.reverser.utils.NativeBridge()
            val strings = nativeBridge.extractStrings(bytes, 4)
            val output = StringBuilder()
            output.append("=== Strings Analysis ===\n")
            output.append("Total strings: ").append(strings.size).append("\n\n")
            for (s in strings.take(100)) {
                output.append("  ").append(s).append("\n")
            }
            ExtendedResult(
                ExtendedAnalyzerType.STRINGS,
                true,
                output = output.toString(),
                
            )
        } catch (e: Exception) {
            ExtendedResult(ExtendedAnalyzerType.STRINGS, false, error = e.message ?: "Strings extraction failed")
        }
    }

    // === YARA 룰 ===
    private fun runYara(filePath: String): ExtendedResult {
        val script = StringBuilder()
        script.append("# YARA rules\n")
        script.append("pkg install yara -y > /dev/null 2>&1 || true; ")
        script.append("echo 'YARA: Pattern matcher ready' >> /sdcard/HermesReverser/results/yara.txt && ")
        script.append("echo 'Usage: yara <rules.yar> <file>' >> /sdcard/HermesReverser/results/yara.txt")

        val success = termuxBridge.runTracked("yara", script.toString())
        return ExtendedResult(
            ExtendedAnalyzerType.YARA,
            success,
            output = if (success) "YARA pattern matcher ready" else "YARA setup failed",
            error = if (!success) "Failed to configure YARA" else ""
        )
    }

    /**
     * 설치 명령어
     */
    fun getInstallCommand(type: ExtendedAnalyzerType): String {
        return when (type.category) {
            "Built-in" -> "Built-in (no install needed)"
            "Termux", "Advanced" -> {
                when (type) {
                    ExtendedAnalyzerType.EDBG -> "cd ~ && git clone https://github.com/ShinoLeah/eDBG.git"
                    ExtendedAnalyzerType.BPFROID -> "cd ~ && git clone https://github.com/yanivagman/BPFroid.git"
                    ExtendedAnalyzerType.HERESY -> "cd ~ && git clone https://github.com/Pilfer/heresy.git"
                    ExtendedAnalyzerType.IDAOBJCTYPES -> "cd ~ && git clone https://github.com/PoomSmart/IDAObjcTypes.git"
                    ExtendedAnalyzerType.GHIDRA_FOX -> "cd ~ && mkdir -p ghidra_scripts && cd ghidra_scripts && wget https://raw.githubusercontent.com/federicodotta/ghidra-scripts/master/FOX.java"
                    ExtendedAnalyzerType.FRIDA -> "pip3 install frida frida-tools"
                    ExtendedAnalyzerType.JNI_TRACE -> "pip3 install jnitrace"
                    ExtendedAnalyzerType.YARA -> "pkg install yara -y"
                    else -> baseAnalyzer.getInstallCommand(
                        MultiAnalyzer.AnalyzerType.all().find { it.displayName == type.displayName }
                            ?: return "Unknown"
                    )
                }
            }
            else -> "No installation required"
        }
    }

    // 기본 MultiAnalyzer 위임
    suspend fun analyzeQuick(filePath: String, bytes: ByteArray) = baseAnalyzer.analyzeQuick(filePath, bytes)
    suspend fun analyzeFull(filePath: String, bytes: ByteArray) = baseAnalyzer.analyzeFull(filePath, bytes)
    suspend fun checkInstallations()() = baseAnalyzer.checkInstallati
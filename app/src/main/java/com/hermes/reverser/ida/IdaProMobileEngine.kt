package com.hermes.reverser.ida

import android.util.Log
import com.hermes.reverser.model.BinaryInfo
import com.hermes.reverser.utils.NativeBridge

/**
 * IDA Pro Mobile 오픈소스 엔진 (앱 자체 내장)
 *
 * IMTIAZ-X/IDA_PRO-Mobile 기반:
 * - ELF/PE/Mach-O 분석
 * - ARM/x86/x86-64 디스어셈블리
 * - 함수 탐지 및 목록
 * - 헥사 뷰어
 * - 주석 시스템
 */
class IdaProMobileEngine {

    private val nativeBridge = NativeBridge()
    private val annotations = mutableMapOf<Long, String>()

    companion object {
        private const val TAG = "IdaProMobile"
    }

    data class DisasmLine(
        val address: Long,
        val bytes: String,
        val mnemonic: String,
        val operands: String,
        val comment: String = ""
    )

    data class FunctionInfo(
        val name: String,
        val startAddress: Long,
        val endAddress: Long,
        val size: Long,
        val isThunk: Boolean = false
    )

    /**
     * 바이너리 로드 및 기본 분석
     */
    fun loadBinary(filePath: String, bytes: ByteArray): BinaryInfo {
        Log.i(TAG, "Loading binary: $filePath (${bytes.size} bytes)")

        val headerInfo = nativeBridge.analyzeHeader(bytes)
        val fileType = BinaryInfo.detectFromHeader(bytes)
        val strings = nativeBridge.extractStrings(bytes, 4)

        return BinaryInfo(
            filePath = filePath,
            fileName = filePath.substringAfterLast("/"),
            fileSize = bytes.size.toLong(),
            fileType = fileType,
            strings = strings
        )
    }

    /**
     * 디스어셈블리 (Capstone 기반)
     */
    fun disassemble(bytes: ByteArray, baseAddr: Long, arch: String): List<DisasmLine> {
        val output = nativeBridge.disassemble(bytes, baseAddr, arch)
        return parseDisassemblyOutput(output, baseAddr)
    }

    /**
     * 함수 목록 탐지 (ELF 기호 테이블 기반)
     */
    fun detectFunctions(bytes: ByteArray, baseAddr: Long): List<FunctionInfo> {
        val functions = mutableListOf<FunctionInfo>()

        if (bytes.size < 64) return functions

        // ELF 파일의 함수 식별
        if (bytes[0] == 0x7F.toByte() && bytes[1] == 0x45.toByte()) {
            functions.addAll(detectElfFunctions(bytes, baseAddr))
        }

        // PE 파일의 함수 식별
        if (bytes[0] == 0x4D.toByte() && bytes[1] == 0x5A.toByte()) {
            functions.addAll(detectPeFunctions(bytes, baseAddr))
        }

        // 휴리스틱 함수 프로로그 탐색 (push {lr} / stmfd sp!, {lr})
        if (functions.isEmpty()) {
            functions.addAll(detectFunctionsHeuristic(bytes, baseAddr))
        }

        return functions
    }

    /**
     * ELF 함수 탐지
     */
    private fun detectElfFunctions(bytes: ByteArray, baseAddr: Long): List<FunctionInfo> {
        val functions = mutableListOf<FunctionInfo>()

        // .symtab 또는 .dynsym 파싱
        try {
            val is64bit = bytes[4] == 2.toByte()
            val eShoff = if (is64bit) {
                readLong64(bytes, 40)
            } else {
                readInt32(bytes, 32).toLong()
            }
            val eShentsize = if (is64bit) readInt16(bytes, 58) else readInt16(bytes, 46)
            val eShnum = if (is64bit) readInt16(bytes, 60) else readInt16(bytes, 48)
            val eShstrndx = if (is64bit) readInt16(bytes, 62) else readInt16(bytes, 50)

            // 섹션 헤더 순회하여 .symtab/.dynsym 찾기
            for (i in 0 until eShnum) {
                val shOffset = eShoff + i * eShentsize
                val shType = readInt32(bytes, (shOffset + 4).toInt())

                if (shType == 2 || shType == 11) { // SHT_SYMTAB or SHT_DYNSYM
                    val shAddr = if (is64bit) readLong64(bytes, (shOffset + 16).toInt()) else readInt32(bytes, (shOffset + 12).toInt()).toLong()
                    val shSize = if (is64bit) readLong64(bytes, (shOffset + 32).toInt()) else readInt32(bytes, (shOffset + 20).toInt()).toLong()
                    val symEntSize = if (is64bit) 24 else 16

                    val numSymbols = (shSize / symEntSize).toInt()
                    for (j in 1 until minOf(numSymbols, 1000)) {
                        val symOffset = (shAddr + j * symEntSize).toInt()
                        if (symOffset + 4 < bytes.size) {
                            val stInfo = bytes[symOffset + 4]
                            val symType = stInfo.toInt() and 0x0F
                            if (symType == 2) { // STT_FUNC
                                val stValue = if (is64bit) readLong64(bytes, symOffset + 8) else readInt32(bytes, symOffset + 4).toLong()
                                val stSize = if (is64bit) readLong64(bytes, symOffset + 16) else readInt32(bytes, symOffset + 8).toLong()

                                functions.add(FunctionInfo(
                                    name = "sub_${stValue.toString(16).padStart(8, '0')}",
                                    startAddress = baseAddr + stValue,
                                    endAddress = baseAddr + stValue + stSize,
                                    size = stSize
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ELF parsing error: ${e.message}")
        }

        return functions
    }

    /**
     * PE 함수 탐지
     */
    private fun detectPeFunctions(bytes: ByteArray, baseAddr: Long): List<FunctionInfo> {
        val functions = mutableListOf<FunctionInfo>()
        try {
            val peOffset = readInt32(bytes, 60)
            val optionalHeaderSize = readInt16(bytes, peOffset + 20)
            val exportTableRVA = readInt32(bytes, peOffset + 24 + 112)
            val exportTableSize = readInt32(bytes, peOffset + 24 + 116)

            if (exportTableRVA > 0 && exportTableSize > 0) {
                // Export Directory parsing
                val numFunctions = readInt32(bytes, peOffset + exportTableRVA + 20)
                for (i in 0 until minOf(numFunctions, 500)) {
                    val funcRVA = readInt32(bytes, peOffset + exportTableRVA + 28 + i * 4)
                    if (funcRVA > 0) {
                        functions.add(FunctionInfo(
                            name = "export_$i",
                            startAddress = baseAddr + funcRVA,
                            endAddress = baseAddr + funcRVA + 0x100,
                            size = 0x100
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PE parsing error: ${e.message}")
        }
        return functions
    }

    /**
     * 휴리스틱 함수 탐지
     */
    private fun detectFunctionsHeuristic(bytes: ByteArray, baseAddr: Long): List<FunctionInfo> {
        val functions = mutableListOf<FunctionInfo>()
        val seen = mutableSetOf<Long>()

        // ARM 함수 프로로그 패턴
        val patterns = listOf(
            // push {lr} ( Thumb: 0x00B5 )
            byteArrayOf(0x00, 0xB5),
            // push {r4, lr} ( Thumb: 0x10B5 )
            byteArrayOf(0x10, 0xB5),
            // stmfd sp!, {lr} ( ARM: 0xE92D4000 )
            byteArrayOf(0x00, 0x40.toByte(), 0x2D, 0xE9.toByte()),
            // stmfd sp!, {r4-r11, lr} ( ARM: 0xE92D4FF0 )
            byteArrayOf(0xF0.toByte(), 0x4F, 0x2D, 0xE9.toByte())
        )

        for (i in 0 until bytes.size - 4) {
            for (pattern in patterns) {
                if (i + pattern.size <= bytes.size) {
                    var match = true
                    for (j in pattern.indices) {
                        if (bytes[i + j] != pattern[j]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        val addr = baseAddr + i
                        if (addr !in seen) {
                            seen.add(addr)
                            functions.add(FunctionInfo(
                                name = "sub_${addr.toString(16).padStart(8, '0')}",
                                startAddress = addr,
                                endAddress = addr + 0x100,
                                size = 0x100
                            ))
                        }
                    }
                }
            }
        }

        return functions.take(500)
    }

    /**
     * 헥사 덤프 생성
     */
    fun hexDump(bytes: ByteArray, offset: Long, length: Int): String {
        val sb = StringBuilder()
        val end = minOf(offset + length, bytes.size.toLong())
        var i = offset

        while (i < end) {
            sb.append(String.format("%08X:  ", i))

            // Hex bytes
            val lineEnd = minOf(i + 16, end)
            for (j in i until lineEnd) {
                sb.append(String.format("%02X ", bytes[j.toInt()]))
            }

            // Padding
            for (j in 0 until (16 - (lineEnd - i)).toInt()) {
                sb.append("   ")
            }

            sb.append(" ")

            // ASCII
            for (j in i until lineEnd) {
                val b = bytes[j.toInt()].toInt() and 0xFF
                if (b in 32..126) {
                    sb.append(b.toChar())
                } else {
                    sb.append(".")
                }
            }

            sb.append("\n")
            i += 16
        }

        return sb.toString()
    }

    /**
     * 주석 추가
     */
    fun addAnnotation(address: Long, comment: String) {
        annotations[address] = comment
    }

    /**
     * 주석 가져오기
     */
    fun getAnnotation(address: Long): String? = annotations[address]

    /**
     * 크로스 레퍼런스 분석
     */
    fun analyzeXrefs(bytes: ByteArray, baseAddr: Long): Map<Long, List<Long>> {
        val xrefs = mutableMapOf<Long, MutableList<Long>>()

        for (i in 0 until bytes.size - 4 step 4) {
            val target = readInt32(bytes, i)
            if (target >= baseAddr.toInt() && target < (baseAddr + bytes.size).toInt()) {
                val fromAddr = baseAddr + i
                val toAddr = target.toLong()
                xrefs.getOrPut(toAddr) { mutableListOf() }.add(fromAddr)
            }
        }

        return xrefs
    }

    // 헬퍼 함수들

    private fun readInt16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLong64(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = result or ((bytes[offset + i].toInt() and 0xFF).toLong() shl (8 * i))
        }
        return result
    }

    private fun parseDisassemblyOutput(output: String, baseAddr: Long): List<DisasmLine> {
        val lines = mutableListOf<DisasmLine>()
        val lineRegex = "0x([0-9A-Fa-f]+):\\s*([^\\s]+)\\s+(.*)".toRegex()

        for (line in output.split("\n")) {
            val match = lineRegex.find(line)
            if (match != null) {
                val addr = match.groupValues[1].toLong(16)
                val mnemonic = match.groupValues[2]
                val operands = match.groupValues[3]
                lines.add(DisasmLine(
                    address = addr,
                    bytes = "",
                    mnemonic = mnemonic,
                    operands = operands,
                    comment = annotations[addr] ?: ""
                ))
            }
        }

        return lines
    }
}

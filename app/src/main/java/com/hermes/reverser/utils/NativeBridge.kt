package com.hermes.reverser.utils

/**
 * JNI 네이티브 브리지
 * C++ Capstone 엔진 연동
 */
class NativeBridge {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    /**
     * 디스어셈블 (네이티브)
     */
    external fun disassembleNative(bytes: ByteArray, baseAddr: Long, arch: String): String

    /**
     * 파일 헤더 분석 (네이티브)
     */
    external fun analyzeHeaderNative(bytes: ByteArray): String

    /**
     * 문자열 추출 (네이티브)
     */
    external fun extractStringsNative(bytes: ByteArray, minLen: Int): Array<String>

    /**
     * 체크섬 계산 (네이티브)
     */
    external fun computeChecksumNative(bytes: ByteArray): Long

    /**
     * 디스어셈블 Kotlin 래퍼
     */
    fun disassemble(bytes: ByteArray, baseAddr: Long = 0x1000L, arch: String = "ARM"): String {
        return disassembleNative(bytes, baseAddr, arch)
    }

    /**
     * 파일 헤더 분석 Kotlin 래퍼
     */
    fun analyzeHeader(bytes: ByteArray): String {
        return analyzeHeaderNative(bytes)
    }

    /**
     * 문자열 추출 Kotlin 래퍼
     */
    fun extractStrings(bytes: ByteArray, minLen: Int = 4): List<String> {
        return extractStringsNative(bytes, minLen).toList()
    }
}

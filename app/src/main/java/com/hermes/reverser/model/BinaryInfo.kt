package com.hermes.reverser.model

/**
 * 바이너리 파일 정보 모델
 */
data class BinaryInfo(
    val filePath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val md5Hash: String = "",
    val sha256Hash: String = "",
    val fileType: FileType = FileType.UNKNOWN,
    val architecture: String = "",
    val isStripped: Boolean = false,
    val hasDebugInfo: Boolean = false,
    val sectionNames: List<String> = emptyList(),
    val importSymbols: List<String> = emptyList(),
    val exportSymbols: List<String> = emptyList(),
    val strings: List<String> = emptyList()
) {
    enum class FileType {
        ELF, PE, MACH_O, DEX, APK, SO, UNKNOWN
    }

    companion object {
        fun detectFromHeader(header: ByteArray): FileType {
            if (header.size < 4) return FileType.UNKNOWN
            // ELF: 7F 45 4C 46
            if (header[0] == 0x7F.toByte() &&
                header[1] == 0x45.toByte() &&
                header[2] == 0x4C.toByte() &&
                header[3] == 0x46.toByte()) {
                return FileType.ELF
            }
            // PE: MZ
            if (header[0] == 0x4D.toByte() && header[1] == 0x5A.toByte()) {
                return FileType.PE
            }
            // Mach-O: FE ED FA or CA FE BA BE
            if ((header[0] == 0xFE.toByte() && header[1] == 0xED.toByte() && header[2] == 0xFA.toByte()) ||
                (header[0] == 0xCA.toByte() && header[1] == 0xFE.toByte() && header[2] == 0xBA.toByte())) {
                return FileType.MACH_O
            }
            // DEX: 64 65 78 0A
            if (header[0] == 0x64.toByte() && header[1] == 0x65.toByte() &&
                header[2] == 0x78.toByte() && header[3] == 0x0A.toByte()) {
                return FileType.DEX
            }
            // APK (ZIP): 50 4B 03 04
            if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) {
                return FileType.APK
            }
            return FileType.UNKNOWN
        }
    }
}

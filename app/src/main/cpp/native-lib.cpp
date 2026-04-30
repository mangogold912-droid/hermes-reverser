#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <iomanip>
#include <android/log.h>

#define LOG_TAG "NativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 바이트를 hex 문자열로 변환
 */
static std::string bytesToHex(const uint8_t *data, size_t len) {
    std::stringstream ss;
    for (size_t i = 0; i < len; i++) {
        ss << std::hex << std::setw(2) << std::setfill('0') << (int)data[i] << " ";
    }
    return ss.str();
}

/**
 * JNI: 디스어셈블 함수
 * 실제 Capstone 라이브러리 연동 전 stub 구현
 */
JNIEXPORT jstring JNICALL
Java_com_hermes_reverser_utils_NativeBridge_disassembleNative(
        JNIEnv *env,
        jobject /* thiz */,
        jbyteArray bytes,
        jlong baseAddr,
        jstring arch) {

    jsize len = env->GetArrayLength(bytes);
    jbyte *data = env->GetByteArrayElements(bytes, nullptr);

    std::stringstream result;
    result << "; Disassembly at 0x" << std::hex << baseAddr << "\n";
    result << "; Architecture: " << env->GetStringUTFChars(arch, nullptr) << "\n";
    result << "; Size: " << std::dec << len << " bytes\n";
    result << ";==================================\n\n";

    // TODO: Capstone 연동 시 실제 디스어셈블 코드로 교체
    // 현재는 hex dump만 제공
    result << "; Hex dump:\n";
    result << bytesToHex(reinterpret_cast<const uint8_t*>(data), static_cast<size_t>(len)) << "\n\n";

    // 간단한 명령어 추정 (stub)
    const uint8_t *udata = reinterpret_cast<const uint8_t*>(data);
    for (jsize i = 0; i < len && i < 256; ) {
        result << std::hex << "0x" << (baseAddr + i) << ": ";

        // 기본적인 ARM/Thumb 명령어 패턴 인식 (stub)
        if (i + 4 <= len) {
            uint32_t instr = *reinterpret_cast<const uint32_t*>(udata + i);
            if ((instr & 0xFFFF0000) == 0xE92D0000) {
                result << "push    {...}";
            } else if ((instr & 0xFFFF0000) == 0xE8BD0000) {
                result << "pop     {...}";
            } else if ((instr & 0x0F000000) == 0x0B000000) {
                result << "bl      0x" << std::hex << (baseAddr + i + 8 + ((instr & 0xFFFFFF) << 2));
            } else if ((instr & 0x0FFF0000) == 0x059F0000) {
                result << "ldr     ..., [pc, #...]";
            } else if ((instr & 0xFFFF0000) == 0xE1A00000) {
                result << "mov     ..., ...";
            } else if (instr == 0xE12FFF1E) {
                result << "bx      lr";
            } else {
                result << ".word   0x" << std::hex << instr;
            }
            i += 4;
        } else if (i + 2 <= len) {
            uint16_t instr = *reinterpret_cast<const uint16_t*>(udata + i);
            result << ".hword  0x" << std::hex << instr;
            i += 2;
        } else {
            result << ".byte   0x" << std::hex << (int)udata[i];
            i += 1;
        }
        result << "\n";
    }

    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);

    return env->NewStringUTF(result.str().c_str());
}

/**
 * JNI: 파일 시그니처 분석
 */
JNIEXPORT jstring JNICALL
Java_com_hermes_reverser_utils_NativeBridge_analyzeHeaderNative(
        JNIEnv *env,
        jobject /* thiz */,
        jbyteArray bytes) {

    jsize len = env->GetArrayLength(bytes);
    if (len < 4) {
        return env->NewStringUTF("{\"error\":\"File too small\"}");
    }

    jbyte *data = env->GetByteArrayElements(bytes, nullptr);
    const uint8_t *h = reinterpret_cast<const uint8_t*>(data);

    std::stringstream json;
    json << "{";

    // ELF
    if (h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') {
        json << "\"type\":\"ELF\",";
        json << "\"class\":" << ((h[4] == 1) ? "\"32-bit\"" : "\"64-bit\"") << ",";
        json << "\"endian\":" << ((h[5] == 1) ? "\"little\"" : "\"big\"") << ",";
        json << "\"arch\":";
        if (len >= 19) {
            uint16_t machine = *reinterpret_cast<const uint16_t*>(h + 18);
            switch (machine) {
                case 0x03: json << "\"x86\""; break;
                case 0x28: json << "\"ARM\""; break;
                case 0x3E: json << "\"x86_64\""; break;
                case 0xB7: json << "\"AArch64\""; break;
                default: json << "\"0x" << std::hex << machine << "\""; break;
            }
        } else {
            json << "\"unknown\"";
        }
    }
    // PE
    else if (h[0] == 'M' && h[1] == 'Z') {
        json << "\"type\":\"PE\",";
        json << "\"class\":\"unknown\"";
    }
    // Mach-O
    else if ((h[0] == 0xFE && h[1] == 0xED && h[2] == 0xFA && h[3] == 0xCF) ||
             (h[0] == 0xCF && h[1] == 0xFA && h[2] == 0xED && h[3] == 0xFE)) {
        json << "\"type\":\"Mach-O\",";
        json << "\"class\":\"unknown\"";
    }
    // DEX
    else if (h[0] == 'd' && h[1] == 'e' && h[2] == 'x' && h[3] == 0x0A) {
        json << "\"type\":\"DEX\",";
        json << "\"class\":\"Dalvik\"";
    }
    else {
        json << "\"type\":\"UNKNOWN\",";
        json << "\"class\":\"unknown\"";
    }

    json << "}";
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return env->NewStringUTF(json.str().c_str());
}

/**
 * JNI: 문자열 추출
 */
JNIEXPORT jobjectArray JNICALL
Java_com_hermes_reverser_utils_NativeBridge_extractStringsNative(
        JNIEnv *env,
        jobject /* thiz */,
        jbyteArray bytes,
        jint minLen) {

    jsize len = env->GetArrayLength(bytes);
    jbyte *data = env->GetByteArrayElements(bytes, nullptr);
    const uint8_t *udata = reinterpret_cast<const uint8_t*>(data);

    std::vector<std::string> strings;
    std::string current;

    for (jsize i = 0; i < len; i++) {
        char c = static_cast<char>(udata[i]);
        if (c >= 32 && c <= 126) {
            current += c;
        } else {
            if (static_cast<int>(current.length()) >= minLen) {
                strings.push_back(current);
            }
            current.clear();
        }
    }
    if (static_cast<int>(current.length()) >= minLen) {
        strings.push_back(current);
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(strings.size()), stringClass, nullptr);
    for (size_t i = 0; i < strings.size(); i++) {
        env->SetObjectArrayElement(result, static_cast<jsize>(i), env->NewStringUTF(strings[i].c_str()));
    }

    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return result;
}

/**
 * JNI: 체크섬 계산
 */
JNIEXPORT jlong JNICALL
Java_com_hermes_reverser_utils_NativeBridge_computeChecksumNative(
        JNIEnv *env,
        jobject /* thiz */,
        jbyteArray bytes) {

    jsize len = env->GetArrayLength(bytes);
    jbyte *data = env->GetByteArrayElements(bytes, nullptr);
    const uint8_t *udata = reinterpret_cast<const uint8_t*>(data);

    uint64_t sum = 0;
    for (jsize i = 0; i < len; i++) {
        sum += udata[i];
    }

    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return static_cast<jlong>(sum);
}

#ifdef __cplusplus
}
#endif

package com.hermes.reverser.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * IDA Pro MCP (Model Context Protocol) 클라이언트
 * TCP/JSON-RPC 기반 외부 IDA Pro 연동 (방법 C)
 *
 * 안전 문법:
 * - Object()/synchronized 금지 → Mutex 사용
 * - Triple quote regex 금지
 */
class IDAMCPClient(
    private val host: String = "192.168.1.100",
    private val port: Int = 5000
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private val connected = AtomicBoolean(false)
    private val requestId = AtomicInteger(0)
    private val mutex = Mutex()

    companion object {
        private const val TAG = "IDAMCPClient"
        private const val TIMEOUT_MS = 30000L
        private const val RECONNECT_DELAY_MS = 2000L
    }

    /**
     * MCP 서버에 TCP 연결
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                disconnectLocked()
                socket = Socket(host, port).apply {
                    soTimeout = TIMEOUT_MS.toInt()
                }
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream()), true)
                connected.set(true)
                Log.i(TAG, "Connected to IDA MCP at $host:$port")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                connected.set(false)
                false
            }
        }
    }

    /**
     * 연결 종료
     */
    suspend fun disconnect() {
        mutex.withLock {
            disconnectLocked()
        }
    }

    private fun disconnectLocked() {
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        reader = null
        writer = null
        socket = null
        connected.set(false)
    }

    fun isConnected(): Boolean = connected.get()

    /**
     * JSON-RPC 요청 전송
     */
    private suspend fun sendRequest(method: String, params: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!connected.get() || writer == null || reader == null) {
                    return@withContext null
                }

                val id = requestId.incrementAndGet()
                val request = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }

                return@withContext try {
                    writer!!.println(request.toString())
                    val responseStr = reader!!.readLine()
                    if (responseStr != null) {
                        JSONObject(responseStr)
                    } else null
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "Request timeout: $method")
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Request error: ${e.message}")
                    connected.set(false)
                    null
                }
            }
        }

    /**
     * 바이너리 로드
     */
    suspend fun loadFile(filePath: String): Boolean {
        val result = sendRequest("load_file", JSONObject().apply {
            put("path", filePath)
        })
        return result?.optBoolean("result", false) ?: false
    }

    /**
     * 함수 목록 가져오기
     */
    suspend fun getFunctions(): List<String> {
        val result = sendRequest("get_functions", JSONObject())
        val arr = result?.optJSONArray("result")
        val list = mutableListOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        }
        return list
    }

    /**
     * 함수 디컴파일
     */
    suspend fun decompileFunction(funcName: String): String {
        val result = sendRequest("decompile", JSONObject().apply {
            put("name", funcName)
        })
        return result?.optString("result", "") ?: ""
    }

    /**
     * 함수 어셈블리 가져오기
     */
    suspend fun getDisassembly(funcName: String): String {
        val result = sendRequest("get_disassembly", JSONObject().apply {
            put("name", funcName)
        })
        return result?.optString("result", "") ?: ""
    }

    /**
     * 크로스 레퍼런스 (Xrefs) 가져오기
     */
    suspend fun getXrefs(address: Long): List<String> {
        val result = sendRequest("get_xrefs", JSONObject().apply {
            put("address", address)
        })
        val arr = result?.optJSONArray("result")
        val list = mutableListOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        }
        return list
    }

    /**
     * 문자열 검색
     */
    suspend fun searchStrings(pattern: String): List<String> {
        val result = sendRequest("search_strings", JSONObject().apply {
            put("pattern", pattern)
        })
        val arr = result?.optJSONArray("result")
        val list = mutableListOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        }
        return list
    }

    /**
     * 세그먼트/섹션 정보 가져오기
     */
    suspend fun getSegments(): List<Map<String, Any>> {
        val result = sendRequest("get_segments", JSONObject())
        val arr = result?.optJSONArray("result")
        val list = mutableListOf<Map<String, Any>>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val map = mutableMapOf<String, Any>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.get(key)
                }
                list.add(map)
            }
        }
        return list
    }

    /**
     * 특정 주소의 바이트 읽기
     */
    suspend fun readBytes(address: Long, size: Int): ByteArray? {
        val result = sendRequest("read_bytes", JSONObject().apply {
            put("address", address)
            put("size", size)
        })
        val str = result?.optString("result", "")
        return if (str != null && str.isNotEmpty()) {
            str.toByteArray()
        } else null
    }

    /**
     * 자동 연결 + 재시도
     */
    suspend fun connectWithRetry(maxRetries: Int = 3): Boolean {
        repeat(maxRetries) { attempt ->
            if (connect()) {
                return true
            }
            delay(RECONNECT_DELAY_MS * (attempt + 1))
        }
        return false
    }

    /**
     * 전체 분석 파이프라인
     */
    suspend fun analyzeBinary(filePath: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        if (!isConnected()) {
            if (!connectWithRetry()) {
                result["error"] = "Cannot connect to IDA MCP server"
                return result
            }
        }

        if (!loadFile(filePath)) {
            result["error"] = "Failed to load file in IDA"
            return result
        }

        result["functions"] = getFunctions()
        result["segments"] = getSegments()
        result["strings"] = searchStrings("")

        val functions = result["functions"] as List<String>
        val decompiled = mutableMapOf<String, String>()
        val disassembled = mutableMapOf<String, String>()

        for (func in functions.take(20)) {
            decompiled[func] = decompileFunction(func)
            disassembled[func] = getDisassembly(func)
        }

        result["decompiled"] = decompiled
        result["disassembled"] = disassembled

        return result
    }
}

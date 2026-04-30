package com.hermes.reverser

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * 앱 내장 터미널 — 명령어 자동 실행
 *
 * 사용법:
 *   val intent = Intent(context, TerminalActivity::class.java)
 *   intent.putExtra("command", "ls -la /sdcard")
 *   context.startActivity(intent)
 *
 * Features:
 * - WebView + xterm.js 기반 터미널 UI
 * - /system/bin/sh 직접 실행 (별도 앱 불필요)
 * - 명령어 자동 입력 + 실행
 * - 실시간 출력 표시
 * - 여러 명령 순차 실행 지원 (commands extra)
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var currentProcess: Process? = null
    private var outputWriter: OutputStreamWriter? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(TerminalInterface(), "Android")
        }

        // xterm.js 로컬 HTML 로드
        webView.loadDataWithBaseURL(null, getTerminalHtml(), "text/html", "UTF-8", null)
        setContentView(webView)

        // sh 프로세스 시작
        startShell()

        // 전달된 명령어 확인
        val singleCmd = intent.getStringExtra("command")
        val multiCmds = intent.getStringArrayListExtra("commands")

        if (singleCmd != null) {
            // 단일 명령 자동 실행
            sendToTerminal("[Hermes] Executing: $singleCmd")
            autoExecute(singleCmd)
        } else if (multiCmds != null && multiCmds.isNotEmpty()) {
            // 여러 명령 순차 실행
            sendToTerminal("[Hermes] Executing " + multiCmds.size + " commands...")
            executeSequentially(multiCmds)
        }
    }

    /**
     * 쉘 프로세스 시작
     */
    private fun startShell() {
        executor.execute {
            try {
                currentProcess?.destroy()
                currentProcess = Runtime.getRuntime().exec("/system/bin/sh")
                outputWriter = OutputStreamWriter(currentProcess!!.outputStream)

                // stdout 읽기
                Thread { readStream(currentProcess!!.inputStream, false) }.start()
                // stderr 읽기
                Thread { readStream(currentProcess!!.errorStream, true) }.start()

            } catch (e: Exception) {
                sendToTerminal("[Error] Failed to start shell: " + e.message)
            }
        }
    }

    /**
     * 명령어 자동 실행 (사용자 입력 없이)
     */
    private fun autoExecute(command: String) {
        executor.execute {
            try {
                // 명령 종료 마커 사용
                val marker = "HERMES_DONE_" + System.currentTimeMillis()
                val wrappedCmd = command + "; echo '" + marker + ":' \$?"

                outputWriter?.write(wrappedCmd + "\n")
                outputWriter?.flush()

            } catch (e: Exception) {
                sendToTerminal("[Error] " + e.message)
            }
        }
    }

    /**
     * 여러 명령 순차 실행
     */
    private fun executeSequentially(commands: ArrayList<String>) {
        executor.execute {
            for ((index, cmd) in commands.withIndex()) {
                sendToTerminal("[Hermes] [" + (index + 1) + "/" + commands.size + "] $cmd")
                autoExecute(cmd)
                // 각 명령 사이 대기
                Thread.sleep(500)
            }
            sendToTerminal("[Hermes] All commands executed!")
        }
    }

    /**
     * 스트림 읽기
     */
    private fun readStream(stream: java.io.InputStream, isError: Boolean) {
        val reader = BufferedReader(InputStreamReader(stream))
        var line: String?
        try {
            while (reader.readLine().also { line = it } != null) {
                val text = line!!
                handler.post {
                    if (isError) {
                        sendToTerminal(text, true)
                    } else {
                        sendToTerminal(text, false)
                    }
                }
            }
        } catch (e: Exception) {
            // 스트림 종료
        }
    }

    /**
     * 터미널에 텍스트 전송
     */
    private fun sendToTerminal(text: String, isError: Boolean = false) {
        val color = if (isError) "\u001b[1;31m" else ""
        val reset = if (isError) "\u001b[0m" else ""
        val fullText = color + text + reset
        val esc = fullText.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        webView.evaluateJavascript("writeTerminal('$esc')", null)
    }

    /**
     * JS 인터페이스
     */
    inner class TerminalInterface {
        @JavascriptInterface
        fun onInput(text: String) {
            executor.execute {
                try {
                    outputWriter?.write(text)
                    outputWriter?.flush()
                } catch (e: Exception) {
                    sendToTerminal("[Error] " + e.message, true)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        currentProcess?.destroy()
        outputWriter?.close()
    }

    // ===== HTML + xterm.js =====

    private fun getTerminalHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { width: 100%; height: 100%; background: #0d1117; overflow: hidden; }
  #terminal { width: 100%; height: 100%; }
</style>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/xterm@5.3.0/css/xterm.css" />
<script src="https://cdn.jsdelivr.net/npm/xterm@5.3.0/lib/xterm.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/xterm-addon-fit@0.8.0/lib/xterm-addon-fit.min.js"></script>
</head>
<body>
<div id="terminal"></div>
<script>
  const term = new Terminal({
    fontSize: 13,
    fontFamily: 'monospace',
    theme: { background: '#0d1117', foreground: '#c9d1d9', cursor: '#58a6ff', selection: '#264f78' },
    cursorBlink: true,
    scrollback: 50000,
    convertEol: true
  });
  const fitAddon = new FitAddon.FitAddon();
  term.loadAddon(fitAddon);
  term.open(document.getElementById('terminal'));
  fitAddon.fit();

  // 프롬프트 상태
  let command = '';
  let promptShown = false;

  function showPrompt() {
    term.write('\r\n\x1b[1;32mhermes\x1b[0m:\x1b[1;34m~\x1b[0m\$ ');
    promptShown = true;
  }

  // 키 입력
  term.onData(function(data) {
    const code = data.charCodeAt(0);
    if (code === 13) {
      term.write('\r\n');
      if (command.trim()) {
        Android.onInput(command + '\n');
      }
      command = '';
      promptShown = false;
    } else if (code === 127) {
      if (command.length > 0) {
        command = command.slice(0, -1);
        term.write('\b \b');
      }
    } else if (code >= 32) {
      command += data;
      term.write(data);
    }
  });

  // 외부에서 출력 수신
  window.writeTerminal = function(text) {
    if (promptShown) {
      term.write('\r\n');
      promptShown = false;
    }
    term.write(text);
    term.write('\r\n');
  };

  // 초기 메시지
  term.writeln('\x1b[1;36m=== Hermes Built-in Terminal ===');
  term.writeln('\x1b[0m\x1b[33mAll-in-one: no external Termux needed!');
  term.writeln('\x1b[0mType commands or wait for auto-execution...');
  showPrompt();

  // 크기 조정
  window.addEventListener('resize', function() { fitAddon.fit(); });
</script>
</body>
</html>
        """.trimIndent()
    }
}

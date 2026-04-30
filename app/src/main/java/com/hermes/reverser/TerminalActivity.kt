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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 앱 내장 터미널 — WebView + xterm.js + Runtime.exec
 *
 * SmartIDE처럼 앱 안에서 모든 명령 실행
 * Termux 없이 앱 자체에서 bash 명령 처리
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var currentProcess: Process? = null
    private var outputWriter: OutputStreamWriter? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(TerminalInterface(), "Terminal")
        }

        // HTML 터미널 로드
        webView.loadDataWithBaseURL(null, getTerminalHtml(), "text/html", "UTF-8", null)
        setContentView(webView)

        // 초기 명령 실행
        executeCommand("/system/bin/sh")
    }

    /**
     * 명령어 실행 및 출력을 WebView에 전달
     */
    private fun executeCommand(command: String) {
        try {
            currentProcess?.destroy()
            currentProcess = Runtime.getRuntime().exec(command)
            outputWriter = OutputStreamWriter(currentProcess!!.outputStream)

            // stdout 읽기 스레드
            Thread {
                val reader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val output = line!!
                    handler.post { sendToTerminal(output) }
                }
            }.start()

            // stderr 읽기 스레드
            Thread {
                val reader = BufferedReader(InputStreamReader(currentProcess!!.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val output = line!!
                    handler.post { sendToTerminal(output) }
                }
            }.start()

        } catch (e: Exception) {
            sendToTerminal("Error: ${e.message}")
        }
    }

    /**
     * 터미널에 텍스트 전송 (JS 호출)
     */
    private fun sendToTerminal(text: String) {
        val esc = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        webView.evaluateJavascript("term.write('$esc\\r\\n')", null)
    }

    /**
     * JS에서 명령을 입력받는 인터페이스
     */
    inner class TerminalInterface {
        @JavascriptInterface
        fun onInput(text: String) {
            handler.post {
                try {
                    outputWriter?.write(text)
                    outputWriter?.flush()
                } catch (e: Exception) {
                    sendToTerminal("Error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onResize(cols: Int, rows: Int) {
            // 터미널 크기 조정
        }

        @JavascriptInterface
        fun getPrompt(): String {
            return "$ "
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentProcess?.destroy()
        outputWriter?.close()
    }

    /**
     * HTML + xterm.js 터미널
     */
    private fun getTerminalHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { width: 100%; height: 100%; background: #000; overflow: hidden; }
  #terminal { width: 100%; height: 100%; }
</style>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/xterm@5.3.0/css/xterm.css">
<script src="https://cdn.jsdelivr.net/npm/xterm@5.3.0/lib/xterm.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/xterm-addon-fit@0.8.0/lib/xterm-addon-fit.min.js"></script>
</head>
<body>
<div id="terminal"></div>
<script>
  const term = new Terminal({
    fontSize: 14,
    fontFamily: 'monospace',
    theme: { background: '#0d1117', foreground: '#c9d1d9', cursor: '#58a6ff' },
    cursorBlink: true,
    scrollback: 10000
  });
  const fitAddon = new FitAddon.FitAddon();
  term.loadAddon(fitAddon);
  term.open(document.getElementById('terminal'));
  fitAddon.fit();

  // 프롬프트
  let command = '';
  function showPrompt() {
    term.write('\r\n\x1b[1;32mhermes\x1b[0m:\x1b[1;34m~\x1b[0m$ ');
  }
  showPrompt();

  // 키 입력 처리
  term.onData(function(data) {
    const code = data.charCodeAt(0);
    if (code === 13) { // Enter
      term.write('\r\n');
      if (command.trim()) {
        Terminal.onInput(command + '\n');
      }
      command = '';
    } else if (code === 127) { // Backspace
      if (command.length > 0) {
        command = command.slice(0, -1);
        term.write('\b \b');
      }
    } else if (code >= 32) {
      command += data;
      term.write(data);
    }
  });

  // 출력 수신
  window.sendToTerminal = function(text) {
    term.write('\r\n' + text);
    showPrompt();
  };

  // 크기 조정
  window.addEventListener('resize', function() {
    fitAddon.fit();
    Terminal.onResize(term.cols, term.rows);
  });

  // 초기 메시지
  term.writeln('\x1b[1;36m=== Hermes Terminal ===');
  term.writeln('\x1b[0mBuilt-in terminal - no Termux needed');
  term.writeln('Type commands directly\x1b[0m');
  showPrompt();
</script>
</body>
</html>
        """.trimIndent()
    }
}

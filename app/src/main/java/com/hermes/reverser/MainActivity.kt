package com.hermes.reverser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_STORAGE = 100
        private const val REQ_PICK_FILE = 101
        private const val REQ_ALL_FILES = 102
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvFileInfo: TextView
    private lateinit var btnSelectFile: Button
    private lateinit var btnAllFiles: Button
    private lateinit var btnAnalyze: Button
    private lateinit var btnChat: Button
    private lateinit var btnSettings: Button
    private lateinit var btnTermux: Button
    private lateinit var btnExternalIda: Button

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""
    private var selectedFileSize: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        checkAllFilesPermission()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvFileInfo = findViewById(R.id.tvFileInfo)
        btnSelectFile = findViewById(R.id.btnSelectFile)
        btnAllFiles = findViewById(R.id.btnAllFiles)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnChat = findViewById(R.id.btnChat)
        btnSettings = findViewById(R.id.btnSettings)
        btnTermux = findViewById(R.id.btnTermux)
        btnExternalIda = findViewById(R.id.btnExternalIda)

        btnSelectFile.setOnClickListener { pickFile() }
        btnAllFiles.setOnClickListener { requestAllFilesAccess() }
        btnAnalyze.setOnClickListener { startAnalysis() }
        btnChat.setOnClickListener { openChat() }
        btnSettings.setOnClickListener { openSettings() }
        btnTermux.setOnClickListener { openTermuxSetup() }
        btnExternalIda.setOnClickListener { openExternalIda() }
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_STORAGE)
        }
    }

    private fun checkAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                tvStatus.text = "All files access: NOT GRANTED"
            } else {
                tvStatus.text = "All files access: GRANTED"
            }
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("All Files Access Required")
                    .setMessage("This app needs access to all files for binary analysis.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "All files access already granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                selectedFileUri = uri
                selectedFileName = getFileName(uri)
                selectedFileSize = getFileSize(uri)
                tvFileInfo.text = "Selected: $selectedFileName\nSize: ${formatSize(selectedFileSize)}"
                tvStatus.text = "File ready for analysis"
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx) ?: "unknown"
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else -1
                } else -1
            } ?: -1
        } catch (_: Exception) { -1 }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 0 -> "Unknown"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    private fun startAnalysis() {
        if (selectedFileUri == null) {
            Toast.makeText(this, "Select a file first!", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, AnalysisActivity::class.java)
        intent.putExtra("fileUri", selectedFileUri.toString())
        intent.putExtra("fileName", selectedFileName)
        intent.putExtra("fileSize", selectedFileSize)
        startActivity(intent)
    }

    private fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openTermuxSetup() {
        startActivity(Intent(this, TermuxSetupActivity::class.java))
    }

    private fun openExternalIda() {
        AlertDialog.Builder(this)
            .setTitle("External IDA Pro MCP")
            .setMessage("Connect to PC running IDA Pro MCP server")
            .setPositiveButton("Connect") { _, _ ->
                val intent = Intent(this, SettingsActivity::class.java)
                intent.putExtra("openIdaSettings", true)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

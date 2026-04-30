package com.hermes.reverser.termux

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TermuxService : Service() {
    override fun onBind(intent: Intent): IBinder? = null
}

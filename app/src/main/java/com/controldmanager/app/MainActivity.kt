package com.controldmanager.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.controldmanager.app.ui.CdApp
import com.controldmanager.app.ui.OpenRequest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A restored activity gets its first intent again, widget tap included; that tap was handled back then.
        if (savedInstanceState == null) OpenRequest.from(intent)
        setContent { CdApp() }
    }

    /** A widget tapped while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        OpenRequest.from(intent)
    }
}

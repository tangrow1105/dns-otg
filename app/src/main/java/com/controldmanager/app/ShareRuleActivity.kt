package com.controldmanager.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.controldmanager.app.api.ControlDApi
import com.controldmanager.app.api.Session
import com.controldmanager.app.api.TokenStore
import com.controldmanager.app.share.SharedHosts
import com.controldmanager.app.ui.AppToast
import com.controldmanager.app.ui.CdTheme
import com.controldmanager.app.ui.CreateRuleFromLogDialog
import com.controldmanager.app.ui.DialogHostState
import com.controldmanager.app.ui.DialogOverlay
import com.controldmanager.app.ui.LocalDialogHost
import com.controldmanager.app.ui.LocalHaze
import dev.chrisbanes.haze.hazeSource
import com.controldmanager.app.ui.LocalSession
import com.controldmanager.app.ui.LocalSnackbar
import com.controldmanager.app.ui.Runner

/**
 * Share target: a link shared from a browser or any other app opens Create Rule for its domain,
 * over the app that shared it. Nothing is saved until the user taps Create; the result is shown as a
 * toast, and closing returns to that app.
 */
class ShareRuleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.let { it.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }
        val hosts = SharedHosts.from(shared)
        if (hosts.isEmpty()) {
            toast("No web address to make a rule for")
            finish()
            return
        }
        val stored = TokenStore.load(this)
        if (stored == null) {
            toast("Sign in to DNS OTG, then share the link again")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val (token, org) = stored
        val session = Session(ControlDApi(token, org), org)
        // On the app's scope, so a save that is still running when this screen closes is not cancelled.
        val runner = Runner(AppToast.scope) { message -> toast(message) }

        setContent {
            CdTheme {
                // The app's own glass dialogs, here too: the panel is the same tinted glass as inside the app, and on
                // Android 12+ the app behind it (the browser) is blurred by the system (see Theme.CDManager.Share).
                val dialogHost = remember { DialogHostState() }
                val haze = remember { dev.chrisbanes.haze.HazeState() }
                CompositionLocalProvider(
                    LocalSession provides session, LocalSnackbar provides remember { SnackbarHostState() },
                    LocalDialogHost provides dialogHost, LocalHaze provides haze,
                ) {
                    Box(Modifier.fillMaxSize().hazeSource(haze)) {
                        var open by remember { mutableStateOf(true) }
                        if (open) {
                            CreateRuleFromLogDialog(hosts, initialProfileId = null, runner = runner, onDismiss = { open = false })
                        } else if (runner.busy) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        // Back to the app that shared once the dialog is closed and its save (if any) has finished.
                        LaunchedEffect(open, runner.busy) { if (!open && !runner.busy) finish() }
                    }
                    DialogOverlay(dialogHost, haze)
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
}

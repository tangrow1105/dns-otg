package com.controldmanager.app.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Bell for the Preferences header. Control D's notifications only load with a dashboard login (not an API
 * token), so this opens the dashboard in an in-app Chrome tab, which shares the Chrome sign-in.
 */
@Composable
fun NotificationsButton() {
    val ctx = LocalContext.current
    IconButton(onClick = { openInAppBrowser(ctx, "https://controld.com/dashboard") }) {
        Icon(Solar.Bell, "Control D notifications")
    }
}

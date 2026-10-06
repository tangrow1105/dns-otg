package com.controldmanager.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val LOGIN_URL = "https://controld.com/login"
private const val API = "https://api.controld.com"

/** Reads the dashboard's login session from its storage (redux-persist JSON with a "sessionToken" field). */
private const val READ_SESSION_JS = """
(function(){
  try {
    for (const st of [window.localStorage, window.sessionStorage]) {
      for (let i = 0; i < st.length; i++) {
        try {
          const o = JSON.parse(st.getItem(st.key(i)));
          if (o && o.sessionToken) {
            let v = o.sessionToken;
            try { v = JSON.parse(v); } catch (e) {}
            if (typeof v === 'string' && v.length > 10) return v;
          }
        } catch (e) {}
      }
    }
  } catch (e) {}
  return '';
})()
"""

/** Catches taps on the page's Passkey button (passkeys can't work inside an app's web page for controld.com). */
private const val HOOK_PASSKEY_JS = """
(function(){
  if (window.__cdHook) return; window.__cdHook = 1;
  document.addEventListener('click', function(e){
    const b = e.target.closest && e.target.closest('button,a,[role=button]');
    if (!b) return;
    if ((b.innerText || '').trim().toLowerCase() === 'passkey') {
      e.preventDefault(); e.stopPropagation(); CdApp.passkeyTapped();
    }
  }, true);
})()
"""

/**
 * Control D's own login page inside the app. The user signs in there (password, 2FA, captcha and
 * passkeys are all handled by the page); the app then turns that session into an API token named
 * after this device, signs the web session out and wipes the page's storage.
 * Google blocks its sign-in inside embedded pages, so [onGoogle] is called instead of loading it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSignIn(onToken: (String) -> Unit, onGoogle: () -> Unit, onCancel: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var settingUp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var passkeyInfo by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler {
        val w = web
        if (w != null && w.canGoBack() && !settingUp) w.goBack() else onCancel()
    }

    // Watch for the session the page stores once the user has signed in.
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        while (true) {
            delay(1000)
            if (settingUp) continue
            val session = suspendCoroutine { c ->
                w.evaluateJavascript(READ_SESSION_JS) { v -> c.resume(runCatching { JSONArray("[$v]").optString(0) }.getOrDefault("")) }
            }
            if (session.isNotBlank()) {
                settingUp = true
                try {
                    val token = tokenFromSession(session)
                    clearWebSession(w)
                    onToken(token)
                    return@LaunchedEffect
                } catch (e: Exception) {
                    error = "Signed in, but couldn't create an API token: ${e.message}"
                    clearWebSession(w)
                    settingUp = false
                    w.loadUrl(LOGIN_URL)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Solar.Close, "Cancel", tint = Palette.Text) }
            Column(Modifier.weight(1f)) {
                Text("Sign in to Control D", fontWeight = FontWeight.SemiBold)
                Text("controld.com", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
            if (loading) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(18.dp), strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = Palette.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Passkeys, where this phone's WebView supports them.
                        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_AUTHENTICATION)) {
                            androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(
                                settings, androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
                            )
                        }
                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun passkeyTapped() { post { passkeyInfo = true } }
                        }, "CdApp")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val host = request.url.host.orEmpty()
                                if (host.endsWith("accounts.google.com")) { onGoogle(); return true }
                                // Keep everything else on Control D (and its captcha / passkey helpers) inside the page.
                                return false
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                view.evaluateJavascript(HOOK_PASSKEY_JS, null)
                            }
                        }
                        loadUrl(LOGIN_URL)
                        web = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (passkeyInfo) CdDialog(
                onDismissRequest = { passkeyInfo = false },
                title = { Text("Passkeys don't work here") },
                text = {
                    Text(
                        "Android only allows a passkey in websites that have approved the app, and controld.com hasn't. " +
                            "Sign in with your email and password instead, or use your browser to get a token.",
                    )
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { passkeyInfo = false }) { Text("OK") } },
            )
            if (settingUp) {
                Column(
                    Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.92f)),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Setting up the app…", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Creating an API token for this device.", color = Palette.Muted,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

private fun call(method: String, path: String, session: String, json: JSONObject? = null): JSONObject {
    val req = Request.Builder().url(API + path)
        .method(method, json?.toString()?.toRequestBody("application/json".toMediaType()) ?: if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType()))
        .header("authorization", session) // the dashboard sends its session token as-is
        .header("accept", "application/json")
        .build()
    http.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (!r.isSuccessful || o == null || !o.optBoolean("success", r.isSuccessful)) {
            throw IllegalStateException(o?.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "HTTP ${r.code}")
        }
        return o.optJSONObject("body") ?: JSONObject()
    }
}

/** Creates a Write API token named after this device (same call as the dashboard's API page). */
private suspend fun tokenFromSession(session: String): String = withContext(Dispatchers.IO) {
    val types = runCatching { call("GET", "/token/types", session).optJSONObject("types") }.getOrNull()
    val type = types?.keys()?.asSequence()?.toList()?.let { keys ->
        keys.firstOrNull { k -> (types.optString(k) + k).contains("write", true) } ?: keys.firstOrNull()
    } ?: "write"
    val name = "DNS OTG – ${Build.MODEL}".take(40)
    val created = call("POST", "/token", session, JSONObject().put("name", name).put("type", type))
    val token = findToken(created) ?: throw IllegalStateException("No token in the response")
    // Check the new token works before keeping it.
    val check = Request.Builder().url("$API/users").header("Authorization", "Bearer $token").build()
    http.newCall(check).execute().use { if (!it.isSuccessful) throw IllegalStateException("New token was rejected (HTTP ${it.code})") }
    runCatching { call("POST", "/users/logout", session) }
    token
}

/** The token string in the create response: an "api." value, or the usual field names. */
private fun findToken(o: Any?): String? = when (o) {
    is JSONObject -> {
        listOf("token", "api_token", "key", "PK").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.startsWith("api.") } }
            ?: o.keys().asSequence().firstNotNullOfOrNull { k -> findToken(o.opt(k)) }
            ?: listOf("token", "api_token", "key").firstNotNullOfOrNull { k -> o.optString(k).ifBlank { null } }
    }
    is JSONArray -> (0 until o.length()).firstNotNullOfOrNull { findToken(o.opt(it)) }
    is String -> o.takeIf { it.startsWith("api.") }
    else -> null
}

private fun clearWebSession(w: WebView) {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    w.clearCache(true)
    w.clearHistory()
}

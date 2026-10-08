@file:OptIn(ExperimentalMaterial3Api::class)

package com.controldmanager.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import com.controldmanager.app.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.controldmanager.app.api.ControlDApi
import com.controldmanager.app.api.Session
import com.controldmanager.app.api.TokenStore
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.hazeSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CdApp() {
    CdTheme {
        val ctx = LocalContext.current
        var session by remember {
            mutableStateOf(TokenStore.load(ctx)?.let { (t, org) -> Session(ControlDApi(t, org), org) })
        }
        val snackbar = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            val s = session
            if (s == null) {
                LoginScreen(onLoggedIn = { token, org ->
                    TokenStore.save(ctx, token, org)
                    session = Session(ControlDApi(token, org), org)
                })
            } else {
                CompositionLocalProvider(LocalSession provides s) {
                    ProvideTickingClock {
                        MainScaffold(onLogout = {
                            TokenStore.clear(ctx)
                            session = null
                        })
                    }
                }
            }
        }
    }
}

private val tabs = listOf(
    NavItem("profiles", "Profiles", Solar.Tuning, Solar.TuningSelected),
    NavItem("endpoints", "Endpoints", Solar.Devices, Solar.DevicesSelected),
    NavItem("analytics", "Analytics", Solar.Insights, Solar.InsightsSelected),
    NavItem("account", "Preferences", Solar.Settings, Solar.SettingsSelected),
)

@Composable
private fun MainScaffold(onLogout: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val snackbar = LocalSnackbar.current
    val fabState = remember { NavFabState() }
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val menuHost = remember { MenuHostState() }
    val sheetHost = remember { SheetHostState() }
    val actionBarHost = remember { ActionBarHostState() }
    val searchHost = remember { SearchHostState() }
    val dialogHost = remember { DialogHostState() }
    DisposableEffect(snackbar) { AppToast.host = snackbar; onDispose { if (AppToast.host === snackbar) AppToast.host = null } }
    // GitHub installs: mention a newer release once per version (it also stays listed in Preferences).
    val updCtx = LocalContext.current
    val updUri = LocalUriHandler.current
    LaunchedEffect(Unit) {
        val r = AppUpdates.check(updCtx).release ?: return@LaunchedEffect
        val seen = updCtx.getSharedPreferences("controldmanager_ui", android.content.Context.MODE_PRIVATE)
        if (seen.getString("update_toast_for", null) == r.version) return@LaunchedEffect
        seen.edit().putString("update_toast_for", r.version).apply()
        val res = snackbar.showSnackbar("DNS OTG ${r.version} is available", actionLabel = "Download", duration = SnackbarDuration.Long)
        if (res == SnackbarResult.ActionPerformed) runCatching { updUri.openUri(r.downloadUrl) }
    }
    val onTab = tabs.any { it.route == route }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Tab screens draw under a floating pill nav; they leave LocalBottomBarSpace free at the end of their lists.
    CompositionLocalProvider(
        LocalNavFab provides fabState,
        LocalHaze provides haze,
        LocalMenuHost provides menuHost,
        LocalSheetHost provides sheetHost,
        LocalActionBarHost provides actionBarHost,
        LocalSearchHost provides searchHost,
        LocalDialogHost provides dialogHost,
        LocalBottomBarSpace provides if (onTab) FloatingNavHeight + navInset else 0.dp,
    ) {
    // Themed root background, so screens without their own (e.g. Preferences) follow light / dark.
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        // Screens end above the keyboard, so the field being typed in scrolls into view instead of hiding under it.
        NavHost(nav, startDestination = "profiles", modifier = Modifier.fillMaxSize().imePadding().hazeSource(haze)) {
            composable("profiles") { ProfilesScreen(nav) }
            composable("endpoints") { EndpointsScreen(nav) }
            composable("analytics") { AnalyticsScreen(nav) }
            composable("account") { AccountScreen(nav, onLogout) }
            composable("billing") { BillingScreen(nav) }
            composable("domain-test") { DomainTestScreen(nav) }
            composable("account-settings") { AccountSettingsScreen(nav) }
            composable("profile/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ProfileScreen(nav, it.arguments!!.getString("id")!!)
            }
            composable(
                "folder/{pid}/{fid}",
                arguments = listOf(navArgument("pid") { type = NavType.StringType }, navArgument("fid") { type = NavType.IntType }),
            ) {
                FolderScreen(nav, it.arguments!!.getString("pid")!!, it.arguments!!.getInt("fid"))
            }
            composable("endpoint/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                EndpointScreen(nav, it.arguments!!.getString("id")!!)
            }
            composable("access/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                EndpointAccessScreen(nav, it.arguments!!.getString("id")!!)
            }
            composable("resolvers/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                EndpointResolversScreen(nav, it.arguments!!.getString("id")!!)
            }
        }
        // Fade under the floating bars (nav bar, action bar, search) so content scrolls away beneath them.
        val scrimAlpha by androidx.compose.animation.core.animateFloatAsState(
            if (onTab || actionBarHost.bars.isNotEmpty() || searchHost.active != null) 1f else 0f, androidx.compose.animation.core.tween(220), label = "bottomScrim",
        )
        if (scrimAlpha > 0f) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(with(androidx.compose.ui.platform.LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() } + 120.dp)
                .alpha(scrimAlpha)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Palette.Bg.copy(alpha = 0f), Palette.Bg.copy(alpha = 0.75f), Palette.Bg))),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The floating search bar takes the nav bar's place while open.
            if (onTab && !(searchHost.expanded && searchHost.active?.collapsed == false)) FloatingNavBar(tabs, route, route?.let { fabState.fabs[it] }, haze) { r ->
                nav.navigate(r) {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
        ActionBarOverlay(actionBarHost, haze, hidden = onTab || searchHost.expanded)
        SearchOverlay(searchHost, haze, onTab = onTab)
        SheetOverlay(sheetHost, haze)
        MenuOverlay(menuHost, haze)
        DialogOverlay(dialogHost, haze)
        // Toasts sit above every layer: over sheets and dialogs they show at the top of the screen,
        // otherwise just above the bottom bar (nav bar or the floating action/search bar).
        val coveredBottom = dialogHost.dialogs.isNotEmpty() || sheetHost.sheet != null
        val barAbove = when {
            onTab && !searchHost.expanded -> FloatingNavHeight
            actionBarHost.bars.isNotEmpty() || searchHost.active != null -> 74.dp
            else -> 0.dp
        }
        // Raw status-bar height (an outer layout has already consumed the inset).
        val density = androidx.compose.ui.platform.LocalDensity.current
        val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        Box(
            Modifier.fillMaxSize().then(if (coveredBottom) Modifier.padding(top = statusTop + 12.dp) else Modifier.navigationBarsPadding().imePadding().padding(bottom = 10.dp + barAbove)),
            contentAlignment = if (coveredBottom) Alignment.TopCenter else Alignment.BottomCenter,
        ) { SnackbarHost(snackbar) { GlassToast(it, haze) } }
    }
    }
}

@Composable
fun BackTopBar(nav: NavHostController, title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, maxLines = 1) }
            }
        },
        navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Solar.ArrowBack, "Back") } },
        actions = actions,
        colors = cdTopBarColors(),
    )
}

// ---------- Login ----------

@Composable
private fun LoginScreen(onLoggedIn: (String, String?) -> Unit) {
    var token by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf(false) }
    var googleHelp by remember { mutableStateOf(false) }
    var clipToken by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current

    fun connect(t: String) {
        busy = true; error = null
        scope.launch {
            try {
                ControlDApi(t, org.ifBlank { null }).user()
                onLoggedIn(t, org.ifBlank { null })
            } catch (e: Exception) {
                error = "Couldn't sign in: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // Coming back from the browser with a token copied: offer it straight away.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch {
                kotlinx.coroutines.delay(400) // the clipboard is readable once the window has focus
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim() }.getOrNull()
                // Control D tokens are "api." plus a long random part, so domains and other text don't match.
                clipToken = text?.takeIf { Regex("^api\\.[A-Za-z0-9_-]{16,128}$").matches(it) }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    if (web) {
        WebSignIn(
            onToken = { t -> web = false; connect(t) },
            onGoogle = { web = false; googleHelp = true },
            onCancel = { web = false },
        )
        return
    }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(R.drawable.app_logo), null, tint = Palette.Teal, modifier = Modifier.size(44.dp, 52.dp))
            Spacer(Modifier.height(18.dp))
            Text("DNS OTG", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Manage your Control D profiles and endpoints on the go.", color = Palette.Muted)
            Text("Unofficial app, not affiliated with Control D.", color = Palette.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(32.dp))

            clipToken?.let { t ->
                GroupRow(
                    "Use the copied token?", first = true, last = true, fill = Palette.Card,
                    subtitle = "A token is on your clipboard (…${t.takeLast(4)}).",
                    icon = { Icon(Solar.ContentPaste, null, tint = Palette.Teal) },
                    trailing = { CdButton("Use", { connect(t) }, busy = busy, tint = Palette.Teal, height = 38.dp, fontSize = 14.sp) },
                )
                Spacer(Modifier.height(16.dp))
            }

            CdButton(
                "Sign in with Control D", { web = true }, Modifier.fillMaxWidth(),
                iconContent = { Icon(painterResource(R.drawable.ic_sign_in), null, tint = Palette.Teal, modifier = Modifier.size(22.dp)) },
                tint = Palette.Teal, height = 56.dp, busy = busy && clipToken == null && !manual,
            )
            Text(
                "For email & password accounts. The app creates its own API token, so you only sign in once.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            CdButton("I sign in with Google", { googleHelp = true }, Modifier.fillMaxWidth(), icon = Solar.Global, height = 52.dp)

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Palette.Red, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            TextButton(onClick = { manual = !manual }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (manual) "Hide" else "Paste an API token instead", color = Palette.Muted)
            }
            androidx.compose.animation.AnimatedVisibility(manual) {
                Column {
                    CdTextField(
                        token, { token = it.trim(); error = null },
                        label = { Text("API token") },
                        singleLine = true,
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { show = !show }) {
                                Icon(if (show) Solar.EyeClosed else Solar.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    CdTextField(
                        org, { org = it.trim() },
                        label = { Text("Sub-organization ID (optional)") },
                        supportingText = { Text("Sent as X-Force-Org-Id to manage a sub-org with a parent token.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    CdButton("Connect", { connect(token) }, Modifier.fillMaxWidth(), enabled = token.isNotBlank(), busy = busy, tint = Palette.Teal)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Your token is encrypted with the Android Keystore and only sent to api.controld.com.",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (googleHelp) {
        CdDialog(
            onDismissRequest = { googleHelp = false },
            title = { Text("Signing in with Google") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Google doesn't allow its sign-in inside apps, so get a token from your browser instead:", color = Palette.Muted)
                    listOf(
                        "Tap Open dashboard (it opens in Chrome, where you may already be signed in).",
                        "Tap + to create a token, pick Write, then copy it.",
                        "Close the Chrome tab and tap Use on the copied token.",
                    ).forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Teal.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = Palette.Teal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(s)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    googleHelp = false
                    // A Chrome tab over the app: already signed in to Chrome, and closing it lands back here.
                    runCatching {
                        androidx.browser.customtabs.CustomTabsIntent.Builder()
                            .setDefaultColorSchemeParams(
                                androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                                    .setToolbarColor(android.graphics.Color.parseColor("#080B17")).build(),
                            )
                            .setShowTitle(true)
                            .build()
                            .launchUrl(ctx, android.net.Uri.parse("https://controld.com/dashboard/api"))
                    }.onFailure { uri.openUri("https://controld.com/dashboard/api") }
                }) { Text("Open dashboard") }
            },
            dismissButton = { TextButton(onClick = { googleHelp = false }) { Text("Cancel") } },
        )
    }
}

// ---------- Account ----------

@Composable
private fun AccountScreen(nav: NavHostController, onLogout: () -> Unit) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var confirmLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Preferences", fontWeight = FontWeight.SemiBold) },
            actions = { NotificationsButton() },
            colors = cdTopBarColors(),
        )
        TopScrollFade {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            LaunchedEffect(Unit) { AppUpdates.check(ctx) }
            AppUpdates.available?.let { r ->
                SectionHeader("Update")
                GroupRow(
                    "DNS OTG ${r.version} is available", first = true, last = true, subtitle = "Download the new version from GitHub", fill = Palette.Card,
                    icon = { Icon(Solar.FileDownload, null, tint = Palette.Teal) },
                    trailing = { Icon(Solar.OpenInNew, null, tint = Palette.Muted) },
                ) { uri.openUri(r.downloadUrl) }
            }
            SectionHeader("Settings")
            GroupRow(
                "Account", first = true, last = false, subtitle = "Email, password, passkeys, analytics storage", fill = Palette.Card,
                icon = { Icon(Solar.ManageAccounts, null, tint = Palette.Teal) },
                trailing = { Icon(Solar.ChevronRight, null, tint = Palette.Muted) },
            ) { nav.navigate("account-settings") }
            GroupRow(
                "Billing", first = false, last = false, subtitle = "Plan, renewal and receipts", fill = Palette.Card,
                icon = { Icon(Solar.CreditCard, null, tint = Palette.Teal) },
                trailing = { Icon(Solar.ChevronRight, null, tint = Palette.Muted) },
            ) { nav.navigate("billing") }
            GroupRow(
                "API tokens", first = false, last = false, subtitle = "Manage tokens on the dashboard", fill = Palette.Card,
                icon = { Icon(Solar.Key, null, tint = Palette.Teal) },
                trailing = { Icon(Solar.OpenInNew, null, tint = Palette.Muted) },
            ) { uri.openUri("https://controld.com/dashboard/api") }
            val updScope = rememberCoroutineScope()
            val canCheck = remember { AppUpdates.enabled(ctx) }
            val appVersion = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
            GroupRow(
                "Documentation", first = false, last = false, subtitle = "docs.controld.com", fill = Palette.Card,
                icon = { Icon(Solar.MenuBook, null, tint = Palette.Teal) },
                trailing = { Icon(Solar.OpenInNew, null, tint = Palette.Muted) },
            ) { uri.openUri("https://docs.controld.com/docs/getting-started") }
            // Control D's latest release (public changelog feed); opens their changelog.
            val release = rememberLoader { runCatching { session.api.latestRelease() }.getOrNull() }
            GroupRow(
                release.data?.let { "Control D ${it.version}" } ?: "Control D changelog", first = false, last = !canCheck,
                subtitle = release.data?.date?.let { "Updated " + java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").format(java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())) }
                    ?: "What's new in Control D",
                fill = Palette.Card,
                icon = { Icon(Solar.Stars, null, tint = Palette.Teal) },
                trailing = { Icon(Solar.OpenInNew, null, tint = Palette.Muted) },
            ) { uri.openUri("https://docs.controld.com/changelog") }
            // GitHub installs only: Play keeps its own installs up to date.
            if (canCheck) GroupRow(
                "Check for updates", first = false, last = true, subtitle = "You have version $appVersion", fill = Palette.Card,
                icon = { Icon(Solar.Refresh, null, tint = Palette.Teal) },
                trailing = {
                    if (AppUpdates.checking) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Teal, strokeWidth = 2.dp)
                },
            ) {
                updScope.launch {
                    val res = AppUpdates.check(ctx, force = true)
                    showToast(ctx, when {
                        res.release != null -> "DNS OTG ${res.release.version} is available"
                        res.failed -> "Couldn't reach GitHub. Try again later."
                        else -> "You're on the latest version"
                    })
                }
            }

            SectionHeader("Appearance")
            SlideSelector(
                listOf(
                    SlideOption("System", Solar.AutoMode),
                    SlideOption("Light", Solar.LightMode),
                    SlideOption("Dark", Solar.DarkMode),
                ),
                when (ThemePref.mode) { "light" -> 1; "dark" -> 2; else -> 0 },
                { ThemePref.set(ctx, listOf("system", "light", "dark")[it]) },
            )

            // Same as controld.com/status: what this device looks like to Control D.
            StatusSection()

            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CdButton("Sign out", { confirmLogout = true }, Modifier.fillMaxWidth(0.72f), icon = Solar.Logout, height = 58.dp, fontSize = 18.sp)
            }
            // About: app version, the unofficial-app note, and icon credits (Solar asks for attribution).
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
            Text(
                "DNS OTG ${version.orEmpty()}\nUnofficial app, not affiliated with Control D.\n" +
                    "Icons: Solar by 480 Design (CC BY 4.0), brand logos from Simple Icons.",
                color = Palette.Muted, style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            )
            Spacer(Modifier.height(24.dp + LocalBottomBarSpace.current))
        }
        }
    }
    if (confirmLogout) {
        ConfirmDialog(
            "Sign out?", "The saved API token will be removed from this device. The token itself stays valid until you delete it in the dashboard.",
            confirmLabel = "Sign out", onDismiss = { confirmLogout = false }, onConfirm = onLogout,
        )
    }
}

@Composable
private fun LinkRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, null, tint = Palette.Teal) },
        trailingContent = { Icon(Solar.OpenInNew, null, tint = Palette.Muted) },
        colors = ListItemDefaults.colors(containerColor = Palette.Card),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

fun formatTime(epochSec: Long): String =
    if (epochSec <= 0) "-" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochSec * 1000))

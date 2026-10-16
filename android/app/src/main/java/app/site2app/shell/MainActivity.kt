package app.site2app.shell

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.net.URISyntaxException

/**
 * The whole app: the customer's website in a WebView, with the native parts a
 * website lacks (launch screen, tab bar, pull to refresh, offline screen) and
 * the plumbing a WebView needs to behave like a browser (uploads, downloads,
 * fullscreen video, links to other apps). Every customer runs this same code;
 * [ShellConfig] is the only difference between their apps.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var config: ShellConfig
    private lateinit var links: LinkPolicy

    private lateinit var root: View
    private lateinit var content: View
    private lateinit var statusScrim: View
    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var errorScreen: View
    private lateinit var errorMessage: TextView
    private lateinit var tabs: BottomNavigationView
    private lateinit var fullscreen: FrameLayout

    private val handler = Handler(Looper.getMainLooper())

    /** The launch screen stays up until the first page has painted, or the timeout. */
    private var launching = true

    /** The main-frame load in flight failed; cleared when a new one starts. */
    private var pageFailed = false
    private var failedUrl: String? = null
    private var loadingUrl: String? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** The WebView was destroyed after its renderer died; the activity is on its way out. */
    private var webGone = false

    /** Injection scripts to run after each load, on WebViews too old for document-start scripts. */
    private var lateScripts: List<String> = emptyList()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        callback.onReceiveValue(chosenFiles(result.resultCode, result.data))
    }

    // Enabled only while there is something to go back to, so that with
    // predictive back the system animates leaving the app when there is not.
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> exitFullscreen()
                web.canGoBack() -> web.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { if (errorScreen.isVisible) retry() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { launching }
        handler.postDelayed({ launching = false }, LAUNCH_TIMEOUT_MS)

        config = ShellConfig.parse(assets.open(ShellConfig.ASSET).bufferedReader().use { it.readText() })
        links = LinkPolicy(config.internalHosts)

        // The status bar area is painted with the theme colour by statusScrim;
        // the system bars themselves are transparent.
        enableEdgeToEdge(
            statusBarStyle = if (config.statusBarContent == ShellConfig.StatusBarContent.DARK) {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            } else {
                SystemBarStyle.dark(Color.TRANSPARENT)
            },
            navigationBarStyle = if (Colors.isLight(config.backgroundColor)) {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            } else {
                SystemBarStyle.dark(Color.TRANSPARENT)
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false

        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        statusScrim = findViewById(R.id.status_scrim)
        web = findViewById(R.id.web)
        refresh = findViewById(R.id.refresh)
        progress = findViewById(R.id.progress)
        errorScreen = findViewById(R.id.error_screen)
        errorMessage = findViewById(R.id.error_message)
        tabs = findViewById(R.id.tabs)
        fullscreen = findViewById(R.id.fullscreen)

        applyColors()
        applyInsets()
        setUpTabs()
        setUpWebView()
        findViewById<Button>(R.id.retry).setOnClickListener { retry() }
        onBackPressedDispatcher.addCallback(this, back)

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(config.startUrl)
        }
    }

    private fun applyColors() {
        val background = config.backgroundColor
        val onBackground = Colors.onColor(background)
        val accent = Colors.accentOn(background, config.themeColor)

        root.setBackgroundColor(background)
        statusScrim.setBackgroundColor(config.themeColor)
        web.setBackgroundColor(background)
        errorScreen.setBackgroundColor(background)
        errorMessage.setTextColor(onBackground)
        findViewById<Button>(R.id.retry).apply {
            backgroundTintList = ColorStateList.valueOf(accent)
            setTextColor(Colors.onColor(accent))
        }
        progress.setIndicatorColor(accent)
        progress.trackColor = Color.TRANSPARENT
        refresh.setColorSchemeColors(accent)
        refresh.setProgressBackgroundColorSchemeColor(if (Colors.isLight(background)) Colors.WHITE else 0xFF2A2A2A.toInt())

        tabs.setBackgroundColor(background)
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, Colors.withAlpha(onBackground, 0.62f)),
        )
        tabs.itemIconTintList = tint
        tabs.itemTextColor = tint
        tabs.isItemActiveIndicatorEnabled = false
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())

            statusScrim.updateLayoutParams { height = bars.top }
            tabs.isVisible = config.tabs.isNotEmpty() && !keyboardOpen
            tabs.updatePadding(bottom = bars.bottom)
            val bottom = when {
                keyboardOpen -> keyboard.bottom
                tabs.isVisible -> 0
                else -> bars.bottom
            }
            content.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setUpTabs() {
        if (config.tabs.isEmpty()) {
            tabs.isVisible = false
            return
        }
        tabs.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
        config.tabs.forEachIndexed { index, tab ->
            tabs.menu.add(Menu.NONE, index + 1, index, tab.label).setIcon(TabIcons.drawable(tab.icon))
        }
        // Selecting a tab opens its page; tapping the current tab again goes
        // back to the top of that section, as native apps do.
        tabs.setOnItemSelectedListener { item ->
            web.loadUrl(config.tabs[item.itemId - 1].url)
            true
        }
        tabs.setOnItemReselectedListener { item -> web.loadUrl(config.tabs[item.itemId - 1].url) }
    }

    /** Marks the tab whose page is showing, without opening it again. */
    private fun syncTab(url: String) {
        val index = config.tabs.indexOfFirst { LinkPolicy.samePage(it.url, url) }
        if (index >= 0) tabs.menu.findItem(index + 1)?.isChecked = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Lay out like Chrome: honour the viewport tag, and fit pages
            // without one to the screen.
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // window.open and target=_blank load in this WebView.
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (config.userAgentSuffix.isNotBlank()) userAgentString = "$userAgentString ${config.userAgentSuffix.trim()}"
        }
        // Third-party cookies: payment and sign-in frames rely on them.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.webViewClient = ShellClient()
        web.webChromeClient = ShellChrome()
        // Files the site offers for download open in the browser, which has
        // the download manager and storage access this app does without.
        web.setDownloadListener { url, _, _, _, _ ->
            if (LinkPolicy.schemeOf(url) in WEB_SCHEMES) openInBrowser(url, customTab = false)
        }
        installScripts()

        refresh.isEnabled = config.pullToRefresh
        refresh.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        refresh.setOnRefreshListener { web.reload() }
    }

    private fun installScripts() {
        val scripts = listOfNotNull(Injection.cssScript(config.injectCss), Injection.jsScript(config.injectJs))
        if (scripts.isEmpty()) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                val rules = Injection.originRules(config.internalHosts)
                for (script in scripts) WebViewCompat.addDocumentStartJavaScript(web, script, rules)
                return
            } catch (_: IllegalArgumentException) {
                // An origin rule the WebView rejects: run the scripts after
                // each load instead. Their guards stop a second run.
            }
        }
        lateScripts = scripts
    }

    private fun showError(url: String, certificateError: Boolean) {
        pageFailed = true
        failedUrl = url
        errorMessage.text = when {
            certificateError -> getString(R.string.page_insecure)
            isOnline() -> getString(R.string.page_failed)
            else -> config.offlineMessage
        }
        errorScreen.isVisible = true
        launching = false
        refresh.isRefreshing = false
        progress.isVisible = false
    }

    private fun retry() {
        val url = failedUrl ?: config.startUrl
        errorScreen.isVisible = false
        pageFailed = false
        if (web.url == url) web.reload() else web.loadUrl(url)
    }

    private fun isOnline(): Boolean {
        val connectivity = getSystemService<ConnectivityManager>() ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Opens [url] the way the router asked: in the app, a browser or another app. */
    private fun follow(route: Route) {
        when (route) {
            Route.Stay, Route.Block -> Unit
            is Route.Load -> web.loadUrl(route.url)
            is Route.Browser -> openInBrowser(route.url, customTab = config.externalLinks == ShellConfig.ExternalLinks.IN_APP_BROWSER)
            is Route.Handoff -> handOff(route.url)
        }
    }

    private fun openInBrowser(url: String, customTab: Boolean) {
        val uri = url.toUri()
        if (customTab) {
            val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(config.themeColor).build()
            val intent = CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build()
            try {
                intent.launchUrl(this, uri)
                return
            } catch (_: ActivityNotFoundException) {
                // No browser that supports custom tabs; fall through.
            }
        }
        start(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun handOff(url: String) {
        if (LinkPolicy.schemeOf(url) != "intent") {
            start(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
            return
        }
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) {
            return
        }
        // As Chrome does: only activities that accept links from a browser,
        // never a component the page names, and no URI permission grants.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = intent.flags and URI_GRANT_FLAGS.inv()
        if (start(intent)) return

        val fallback = intent.getStringExtra("browser_fallback_url")
        val target = intent.`package`
        when {
            fallback != null && LinkPolicy.schemeOf(fallback) in WEB_SCHEMES ->
                when (val route = links.route(fallback, tapped = true, redirect = false)) {
                    Route.Stay -> web.loadUrl(fallback)
                    else -> follow(route)
                }
            target != null -> start(Intent(Intent.ACTION_VIEW, "market://details?id=$target".toUri()))
        }
    }

    private fun start(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun chosenFiles(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
        return data.data?.let { arrayOf(it) }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreen.isVisible = true
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        back.isEnabled = true
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreen.removeView(view)
        fullscreen.isVisible = false
        customView = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        back.isEnabled = web.canGoBack()
    }

    private inner class ShellClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Frames inside a page (payment forms, videos, maps) load anything.
            if (!request.isForMainFrame) return false
            val route = links.route(request.url.toString(), tapped = request.hasGesture(), redirect = request.isRedirect)
            if (route == Route.Stay) return false
            follow(route)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageFailed = false
            loadingUrl = url
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (!pageFailed) {
                errorScreen.isVisible = false
                failedUrl = null
            }
            launching = false
            refresh.isRefreshing = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            refresh.isRefreshing = false
            back.isEnabled = customView != null || view.canGoBack()
            val host = LinkPolicy.hostOf(url)
            if (lateScripts.isNotEmpty() && host != null && links.isInternal(host)) {
                for (script in lateScripts) view.evaluateJavascript(script, null)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            back.isEnabled = customView != null || view.canGoBack()
            syncTab(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showError(request.url.toString(), certificateError = false)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never proceed past a certificate error.
            handler.cancel()
            if (error.url == loadingUrl) showError(error.url, certificateError = true)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was reclaimed for memory. Start
            // the app again rather than let the WebView take it down.
            webGone = true
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            finish()
            startActivity(Intent(this@MainActivity, MainActivity::class.java))
            return true
        }
    }

    private inner class ShellChrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.isVisible = newProgress < 100 && !launching && !refresh.isRefreshing && !errorScreen.isVisible
            progress.setProgressCompat(newProgress, true)
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            val intent = params.createIntent()
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return try {
                pickFiles.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                filePathCallback = null
                false
            }
        }

        // The app holds no location, camera or microphone permission, so a
        // page's request for any of them is refused rather than prompted.
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)

        override fun onHideCustomView() = exitFullscreen()
    }

    override fun onStart() {
        super.onStart()
        getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(network)
    }

    override fun onStop() {
        runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(network) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!webGone) web.onResume()
    }

    override fun onPause() {
        if (!webGone) web.onPause()
        // Keep the sign-in cookies if the system ends the process.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webGone) web.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (!webGone) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 8_000L
        val WEB_SCHEMES = setOf("http", "https")
        const val URI_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}

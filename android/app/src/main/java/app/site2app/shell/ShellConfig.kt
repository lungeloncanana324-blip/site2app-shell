package app.site2app.shell

import org.json.JSONArray
import org.json.JSONObject

/**
 * The parts of the Site2App app config the shell reads at launch, from the
 * app-config.json asset. The same document the customer edited on the site:
 * config, never generated source.
 *
 * The build has already validated the document, so a failure here means a
 * broken build. Parsing is still strict, so that shows up the first time the
 * app opens rather than as quietly wrong behaviour.
 */
data class ShellConfig(
    val startUrl: String,
    val themeColor: Int,
    val backgroundColor: Int,
    val statusBarContent: StatusBarContent,
    val internalHosts: List<String>,
    val externalLinks: ExternalLinks,
    val pullToRefresh: Boolean,
    val tabs: List<Tab>,
    val offlineMessage: String,
    val userAgentSuffix: String,
    val injectCss: String,
    val injectJs: String,
) {
    /** Colour of the clock and battery icons drawn over the theme colour. */
    enum class StatusBarContent { LIGHT, DARK }

    enum class ExternalLinks { IN_APP_BROWSER, SYSTEM_BROWSER }

    data class Tab(val label: String, val icon: String, val url: String)

    companion object {
        const val ASSET = "app-config.json"
        const val SCHEMA_VERSION = 1
        const val MAX_TABS = 5

        fun parse(json: String): ShellConfig {
            val root = JSONObject(json)
            val version = root.getInt("schemaVersion")
            require(version == SCHEMA_VERSION) { "This shell reads config schema $SCHEMA_VERSION, not $version" }

            val app = root.getJSONObject("app")
            val branding = root.getJSONObject("branding")
            val navigation = root.getJSONObject("navigation")
            val webview = root.getJSONObject("webview")

            val startUrl = app.getString("startUrl")
            require(startUrl.startsWith("https://")) { "startUrl must be https" }

            val tabs = navigation.getJSONArray("tabs").objects().map { tab ->
                Tab(tab.getString("label"), tab.getString("icon"), tab.getString("url").also {
                    require(it.startsWith("https://")) { "Tab addresses must be https" }
                })
            }
            require(tabs.size <= MAX_TABS) { "At most $MAX_TABS tabs" }

            val hosts = navigation.getJSONArray("internalHosts").strings()
            require(hosts.isNotEmpty()) { "internalHosts is empty" }

            return ShellConfig(
                startUrl = startUrl,
                themeColor = Colors.parse(branding.getString("themeColor")),
                backgroundColor = Colors.parse(branding.getString("backgroundColor")),
                statusBarContent = when (val value = branding.getString("statusBarContent")) {
                    "light" -> StatusBarContent.LIGHT
                    "dark" -> StatusBarContent.DARK
                    else -> throw IllegalArgumentException("statusBarContent: $value")
                },
                internalHosts = hosts,
                externalLinks = when (val value = navigation.getString("externalLinks")) {
                    "in-app-browser" -> ExternalLinks.IN_APP_BROWSER
                    "system-browser" -> ExternalLinks.SYSTEM_BROWSER
                    else -> throw IllegalArgumentException("externalLinks: $value")
                },
                pullToRefresh = navigation.getBoolean("pullToRefresh"),
                tabs = tabs,
                offlineMessage = root.getJSONObject("offline").getString("message"),
                userAgentSuffix = webview.getString("userAgentSuffix"),
                injectCss = webview.getString("injectCss"),
                injectJs = webview.getString("injectJs"),
            )
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}

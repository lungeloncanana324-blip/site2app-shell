package app.site2app.shell

/** Where one main-frame navigation should go. */
sealed interface Route {
    /** Let the WebView load it. */
    data object Stay : Route

    /** Load this address in the app instead: an internal http link, moved to https. */
    data class Load(val url: String) : Route

    /** Leave the app for a web browser. */
    data class Browser(val url: String) : Route

    /** Hand the link to another app: phone, mail, maps, an intent: link. */
    data class Handoff(val url: String) : Route

    /**
     * A sign-in page whose provider refuses to work inside a WebView: Google
     * answers "disallowed_useragent", and Facebook ended WebView login in 2021.
     * The app explains, rather than load the provider's error page.
     */
    data class SignInRefused(val url: String, val provider: String) : Route

    /** Ignore it: local files, and app links nobody tapped. */
    data object Block : Route
}

/**
 * Decides, for every main-frame navigation, whether the app keeps it. Plain
 * string work, so it is tested without Android.
 *
 * The rule a customer configures is the host list: links to those hosts stay
 * in the app, links elsewhere leave it. Two kinds of navigation to other hosts
 * stay anyway, because sending them out would break the site: server
 * redirects, and navigations a script started without a tap. That is how a
 * checkout reaches a payment page and comes back with its session intact.
 *
 * A host pattern is a host ("example.com", that host only) or "*." and a host
 * ("*.example.com", that host and every subdomain of it).
 */
class LinkPolicy(hostPatterns: List<String>) {
    private val exact = mutableSetOf<String>()
    private val withSubdomains = mutableListOf<String>()

    init {
        for (raw in hostPatterns) {
            val pattern = raw.trim().lowercase().trimEnd('.')
            when {
                pattern.startsWith("*.") -> withSubdomains += pattern.removePrefix("*.")
                pattern.isNotEmpty() -> exact += pattern
            }
        }
    }

    fun isInternal(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        if (name.isEmpty()) return false
        return name in exact || withSubdomains.any { name == it || name.endsWith(".$it") }
    }

    /**
     * @param tapped the navigation came from a user gesture (a tap on a link).
     * @param redirect the navigation is a server redirect.
     */
    fun route(url: String, tapped: Boolean, redirect: Boolean): Route {
        val scheme = schemeOf(url) ?: return Route.Block
        return when (scheme) {
            "https", "http" -> {
                val host = hostOf(url) ?: return Route.Block
                val refusing = if (isInternal(host)) null else signInRefusedBy(host, url)
                when {
                    isInternal(host) && scheme == "http" ->
                        // A redirect from https back to http would loop if
                        // upgraded again; let it fail and show the error screen.
                        if (redirect) Route.Stay else Route.Load("https" + url.substring(scheme.length))
                    isInternal(host) -> Route.Stay
                    refusing != null -> Route.SignInRefused(url, refusing)
                    redirect || !tapped -> Route.Stay
                    else -> Route.Browser(url)
                }
            }
            "about", "data", "blob", "javascript" -> Route.Stay
            in LOCAL_SCHEMES -> Route.Block
            // tel:, mailto:, whatsapp:, intent: and the rest open other apps,
            // which a page may only do when someone tapped something.
            else -> if (tapped) Route.Handoff(url) else Route.Block
        }
    }

    companion object {
        private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
        private val LOCAL_SCHEMES = setOf("file", "content", "chrome", "android-app", "jar")

        private val FACEBOOK_HOSTS = setOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com")
        private val FACEBOOK_LOGIN = Regex("^/(v\\d+(\\.\\d+)?/)?dialog/oauth(/.*)?$")

        fun schemeOf(url: String): String? = SCHEME.find(url)?.groupValues?.get(1)?.lowercase()

        /**
         * The provider whose sign-in page this is, when that provider refuses
         * WebViews; null otherwise. Google's passive checks (passive=) never
         * show a page and come straight back, so they are left alone.
         */
        fun signInRefusedBy(host: String, url: String): String? = when {
            host == "accounts.google.com" && !url.contains("passive=") -> "Google"
            host in FACEBOOK_HOSTS && FACEBOOK_LOGIN.matches(pathOf(url)) -> "Facebook"
            else -> null
        }

        /** The host of a hierarchical URL, lower case, without user info or port. */
        fun hostOf(url: String): String? {
            val rest = url.substringAfter("://", missingDelimiterValue = "")
            if (rest.isEmpty()) return null
            val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }.substringAfterLast('@')
            val host = if (authority.startsWith("[")) {
                authority.substringBefore(']') + "]"
            } else {
                authority.substringBefore(':')
            }
            return host.lowercase().trimEnd('.').ifEmpty { null }
        }

        /** The path of a URL with no trailing slash, "/" for the root. */
        fun pathOf(url: String): String {
            val rest = url.substringAfter("://", missingDelimiterValue = "")
            val start = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (start < 0 || rest[start] != '/') return "/"
            return rest.substring(start).takeWhile { it != '?' && it != '#' }.trimEnd('/').ifEmpty { "/" }
        }

        /**
         * Whether two addresses are the same page for the tab bar: same host
         * (ignoring "www.") and path. Scheme, query and fragment are ignored.
         */
        fun samePage(a: String, b: String): Boolean {
            val hostA = hostOf(a)?.removePrefix("www.") ?: return false
            val hostB = hostOf(b)?.removePrefix("www.") ?: return false
            return hostA == hostB && pathOf(a) == pathOf(b)
        }
    }
}

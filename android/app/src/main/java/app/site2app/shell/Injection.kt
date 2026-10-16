package app.site2app.shell

import org.json.JSONObject

/**
 * The customer's own CSS and JavaScript, wrapped to run in every page of the
 * site: typically to hide the site's header or cookie banner inside the app.
 * Each wrapper runs once per document, and only on the internal hosts.
 */
object Injection {
    /** Adds the CSS as a style element as soon as the document element exists. */
    fun cssScript(css: String): String? {
        if (css.isBlank()) return null
        return """
            (function () {
              if (window.__site2appCss) return;
              window.__site2appCss = true;
              var css = ${JSONObject.quote(css)};
              function add() {
                var style = document.createElement("style");
                style.setAttribute("data-site2app", "");
                style.textContent = css;
                (document.head || document.documentElement).appendChild(style);
              }
              if (document.documentElement) {
                add();
              } else {
                new MutationObserver(function (records, observer) {
                  if (!document.documentElement) return;
                  observer.disconnect();
                  add();
                }).observe(document, { childList: true });
              }
            })();
        """.trimIndent()
    }

    /**
     * The customer's script, once per document. An exception it throws is
     * logged rather than left to break the page's own scripts. The newline
     * before the closing brace keeps a trailing // comment from swallowing it.
     */
    fun jsScript(js: String): String? {
        if (js.isBlank()) return null
        return buildString {
            append("(function () {\n")
            append("if (window.__site2appJs) return;\n")
            append("window.__site2appJs = true;\n")
            append("try {\n")
            append(js)
            append("\n} catch (error) {\n")
            append("console.error(\"site2app injectJs\", error);\n")
            append("}\n")
            append("})();")
        }
    }

    /**
     * Hides the site's own bottom tab bar when the app has native tabs, so the
     * two never stack. It hides only an element pinned to the bottom of the
     * screen that links to at least two of the app's tab pages: a sticky "Add
     * to cart" bar or a cookie notice is left alone. It re-checks as the page
     * renders, because sites draw their navigation late and single-page apps
     * redraw it. Learned from readmanna.vercel.app, whose own bar sat directly
     * above the native one.
     */
    fun siteTabBarScript(tabs: List<ShellConfig.Tab>): String? {
        val keys = tabs.mapNotNull { tabKey(it.url) }.distinct()
        if (keys.size < 2) return null
        val list = keys.joinToString(", ") { JSONObject.quote(it) }
        return """
            (function () {
              if (window.__site2appTabBar) return;
              window.__site2appTabBar = true;
              var keys = {};
              [$list].forEach(function (key) { keys[key] = true; });
              function key(href) {
                try {
                  var url = new URL(href, location.href);
                  return url.host.replace(/^www\./, "") + (url.pathname.replace(/\/+${'$'}/, "") || "/");
                } catch (error) {
                  return null;
                }
              }
              function pinnedToBottom(element) {
                var style = getComputedStyle(element);
                if (style.position !== "fixed" && style.position !== "sticky") return false;
                var box = element.getBoundingClientRect();
                return box.height > 0 && box.height < innerHeight * 0.3 && box.width >= innerWidth * 0.6 &&
                  Math.abs(innerHeight - box.bottom) < 4;
              }
              function sweep() {
                var found = new Map();
                document.querySelectorAll("a[href]").forEach(function (link) {
                  var target = key(link.getAttribute("href"));
                  if (!target || !keys[target]) return;
                  for (var element = link.parentElement, depth = 0; element && element !== document.body && depth < 8; element = element.parentElement, depth++) {
                    if (pinnedToBottom(element)) {
                      var seen = found.get(element) || {};
                      seen[target] = true;
                      found.set(element, seen);
                      break;
                    }
                  }
                });
                found.forEach(function (seen, element) {
                  if (Object.keys(seen).length < 2 || element.hasAttribute("data-site2app-hidden")) return;
                  element.style.setProperty("display", "none", "important");
                  element.setAttribute("data-site2app-hidden", "tab-bar");
                });
              }
              var pending = null;
              function soon() {
                if (pending) return;
                pending = setTimeout(function () { pending = null; sweep(); }, 120);
              }
              if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", soon);
              else soon();
              addEventListener("load", soon);
              new MutationObserver(soon).observe(document, { childList: true, subtree: true });
            })();
        """.trimIndent()
    }

    /** A tab's page as the tab bar script compares it: host without "www." and path without a trailing slash. */
    fun tabKey(url: String): String? {
        val host = LinkPolicy.hostOf(url)?.removePrefix("www.") ?: return null
        return host + LinkPolicy.pathOf(url)
    }

    /**
     * Origin rules for WebViewCompat.addDocumentStartJavaScript: the internal
     * hosts, over https. A "*.host" pattern also covers the bare host.
     */
    fun originRules(hostPatterns: List<String>): Set<String> = buildSet {
        for (raw in hostPatterns) {
            val pattern = raw.trim().lowercase().trimEnd('.')
            when {
                pattern.startsWith("*.") -> {
                    add("https://$pattern")
                    add("https://${pattern.removePrefix("*.")}")
                }
                pattern.isNotEmpty() -> add("https://$pattern")
            }
        }
    }
}

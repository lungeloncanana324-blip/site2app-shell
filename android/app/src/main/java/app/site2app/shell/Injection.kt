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

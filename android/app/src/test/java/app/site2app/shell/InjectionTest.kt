package app.site2app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionTest {
    @Test
    fun blankCodeInjectsNothing() {
        assertNull(Injection.cssScript(""))
        assertNull(Injection.cssScript("  \n"))
        assertNull(Injection.jsScript(""))
    }

    @Test
    fun cssIsQuotedAsOneJavaScriptString() {
        val css = "header { content: \"a\\\"b\"; }\n</script><script>alert(1)</script>"
        val script = Injection.cssScript(css)!!
        // The CSS appears only as a JSON string literal: its quotes and
        // newline escaped, so it cannot end the string and run as code.
        assertTrue(script.contains("var css = \"header { content: \\\"a\\\\\\\"b\\\"; }\\n<\\/script><script>alert(1)<\\/script>\";"))
        assertFalse(script.contains("\n</script>"))
    }

    @Test
    fun jsRunsOnceAndCannotThrowIntoThePage() {
        val script = Injection.jsScript("doThing() // trailing comment")!!
        assertTrue(script.startsWith("(function () {\nif (window.__site2appJs) return;"))
        // The newline after the customer's code keeps their comment from
        // swallowing the closing brace.
        assertTrue(script.contains("doThing() // trailing comment\n} catch (error) {"))
        assertTrue(script.endsWith("})();"))
    }

    @Test
    fun siteTabBarScriptNeedsTwoTabPages() {
        assertNull(Injection.siteTabBarScript(emptyList()))
        assertNull(Injection.siteTabBarScript(listOf(ShellConfig.Tab("Home", "home", "https://shop.co.za/"))))
        val script = Injection.siteTabBarScript(
            listOf(
                ShellConfig.Tab("Home", "home", "https://www.shop.co.za/"),
                ShellConfig.Tab("Cart", "cart", "https://shop.co.za/cart/"),
                ShellConfig.Tab("Cart again", "cart", "https://shop.co.za/cart"),
            ),
        )!!
        // Two distinct pages, keyed the way the script keys the page's links.
        assertTrue(script.contains("[\"shop.co.za/\", \"shop.co.za/cart\"]"))
        // The script trims trailing slashes the same way tabKey does.
        assertTrue(script.contains("""url.pathname.replace(/\/+$/, "")"""))
    }

    @Test
    fun tabKeysIgnoreWwwAndTrailingSlashes() {
        assertEquals("readmanna.vercel.app/", Injection.tabKey("https://readmanna.vercel.app/"))
        assertEquals("readmanna.vercel.app/study", Injection.tabKey("https://readmanna.vercel.app/study/"))
        assertEquals("shop.co.za/cart", Injection.tabKey("https://WWW.Shop.co.za/cart?x=1"))
    }

    @Test
    fun originRulesCoverTheHostsOverHttps() {
        assertEquals(
            setOf("https://shop.co.za", "https://*.shop.co.za", "https://blog.example.com"),
            Injection.originRules(listOf("shop.co.za", "*.shop.co.za", "Blog.Example.com")),
        )
    }
}

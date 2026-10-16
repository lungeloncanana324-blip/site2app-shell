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
    fun originRulesCoverTheHostsOverHttps() {
        assertEquals(
            setOf("https://shop.co.za", "https://*.shop.co.za", "https://blog.example.com"),
            Injection.originRules(listOf("shop.co.za", "*.shop.co.za", "Blog.Example.com")),
        )
    }
}

package app.site2app.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellConfigTest {
    /** The smoke-test config from scripts/samples, which has tabs and injected code. */
    private val sample: String = File("../../scripts/samples/wikipedia-tabs.json").readText()

    @Test
    fun readsTheSampleConfig() {
        val config = ShellConfig.parse(sample)
        assertEquals("https://en.m.wikipedia.org/", config.startUrl)
        assertEquals(0xFFEAECF0.toInt(), config.themeColor)
        assertEquals(ShellConfig.StatusBarContent.DARK, config.statusBarContent)
        assertEquals(listOf("wikipedia.org", "*.wikipedia.org"), config.internalHosts)
        assertEquals(ShellConfig.ExternalLinks.IN_APP_BROWSER, config.externalLinks)
        assertEquals(3, config.tabs.size)
        assertEquals(ShellConfig.Tab("Random", "article", "https://en.m.wikipedia.org/wiki/Special:Random"), config.tabs[1])
        assertTrue(config.injectJs.contains("Injected script ran"))
    }

    @Test
    fun everyIconTheSampleUsesExists() {
        val config = ShellConfig.parse(sample)
        // keys(), not keySet(): tests compile against Android's org.json, which lacks keySet.
        val names = JSONObject(File("../../icons/tab-icons.json").readText()).getJSONObject("icons").keys().asSequence().toSet()
        for (tab in config.tabs) assertTrue("missing icon ${tab.icon}", tab.icon in names)
    }

    @Test
    fun rejectsAnotherSchemaVersion() {
        val json = JSONObject(sample).put("schemaVersion", 2).toString()
        assertThrows(IllegalArgumentException::class.java) { ShellConfig.parse(json) }
    }

    @Test
    fun rejectsAPlainHttpStartAddress() {
        val json = JSONObject(sample)
        json.getJSONObject("app").put("startUrl", "http://en.m.wikipedia.org/")
        assertThrows(IllegalArgumentException::class.java) { ShellConfig.parse(json.toString()) }
    }

    @Test
    fun rejectsABadColour() {
        val json = JSONObject(sample)
        json.getJSONObject("branding").put("themeColor", "red")
        assertThrows(IllegalArgumentException::class.java) { ShellConfig.parse(json.toString()) }
    }

    @Test
    fun rejectsAnUnknownLinkMode() {
        val json = JSONObject(sample)
        json.getJSONObject("navigation").put("externalLinks", "popup")
        assertThrows(IllegalArgumentException::class.java) { ShellConfig.parse(json.toString()) }
    }
}

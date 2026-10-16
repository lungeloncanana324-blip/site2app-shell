package app.site2app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {
    // What the site derives for a store at www.shop.co.za.
    private val policy = LinkPolicy(listOf("shop.co.za", "*.shop.co.za"))

    @Test
    fun exactHostMatchesOnlyItself() {
        val exact = LinkPolicy(listOf("shop.co.za"))
        assertTrue(exact.isInternal("shop.co.za"))
        assertFalse(exact.isInternal("www.shop.co.za"))
        assertFalse(exact.isInternal("myshop.co.za"))
    }

    @Test
    fun wildcardMatchesTheHostAndEverySubdomain() {
        val wildcard = LinkPolicy(listOf("*.shop.co.za"))
        assertTrue(wildcard.isInternal("shop.co.za"))
        assertTrue(wildcard.isInternal("www.shop.co.za"))
        assertTrue(wildcard.isInternal("a.b.shop.co.za"))
        assertFalse(wildcard.isInternal("evilshop.co.za"))
        assertFalse(wildcard.isInternal("shop.co.za.evil.com"))
    }

    @Test
    fun hostMatchingIgnoresCaseAndATrailingDot() {
        assertTrue(policy.isInternal("WWW.Shop.CO.za"))
        assertTrue(policy.isInternal("www.shop.co.za."))
        assertFalse(policy.isInternal(""))
    }

    @Test
    fun internalHttpsStays() {
        assertEquals(Route.Stay, policy.route("https://www.shop.co.za/cart", tapped = true, redirect = false))
    }

    @Test
    fun internalHttpIsMovedToHttps() {
        assertEquals(
            Route.Load("https://www.shop.co.za/sale?x=1"),
            policy.route("http://www.shop.co.za/sale?x=1", tapped = true, redirect = false),
        )
        assertEquals(Route.Load("https://shop.co.za/"), policy.route("HTTP://shop.co.za/", tapped = false, redirect = false))
    }

    @Test
    fun anInternalRedirectToHttpIsNotUpgradedIntoALoop() {
        assertEquals(Route.Stay, policy.route("http://www.shop.co.za/", tapped = false, redirect = true))
    }

    @Test
    fun aTappedExternalLinkLeavesTheApp() {
        assertEquals(
            Route.Browser("https://instagram.com/shop"),
            policy.route("https://instagram.com/shop", tapped = true, redirect = false),
        )
    }

    @Test
    fun redirectsAndScriptNavigationsToOtherHostsStay() {
        // A checkout handing over to a payment page, and coming back.
        assertEquals(Route.Stay, policy.route("https://www.payfast.co.za/eng/process", tapped = true, redirect = true))
        assertEquals(Route.Stay, policy.route("https://checkout.stripe.com/c/pay", tapped = false, redirect = false))
    }

    @Test
    fun signInThatRefusesWebViewsIsExplainedHoweverItWasReached() {
        // A site's "Sign in with Google": a form post that redirects, a script,
        // or Google's own button opening its chooser.
        val google = "https://accounts.google.com/o/oauth2/v2/auth?client_id=1&redirect_uri=https%3A%2F%2Fshop.co.za%2Fcb"
        for ((tapped, redirect) in listOf(true to true, false to false, true to false)) {
            assertEquals(Route.SignInRefused(google, "Google"), policy.route(google, tapped, redirect))
        }
        val chooser = "https://accounts.google.com/gsi/select?client_id=1"
        assertEquals(Route.SignInRefused(chooser, "Google"), policy.route(chooser, tapped = true, redirect = false))
        val facebook = "https://www.facebook.com/v19.0/dialog/oauth?client_id=1"
        assertEquals(Route.SignInRefused(facebook, "Facebook"), policy.route(facebook, tapped = false, redirect = true))
    }

    @Test
    fun otherGoogleAndFacebookPagesAreOrdinaryLinks() {
        // A passive check comes straight back without showing anything.
        val passive = "https://accounts.google.com/ServiceLogin?passive=true&continue=https%3A%2F%2Fshop.co.za%2F"
        assertEquals(Route.Stay, policy.route(passive, tapped = false, redirect = true))
        assertEquals(Route.Browser("https://www.facebook.com/shop"), policy.route("https://www.facebook.com/shop", tapped = true, redirect = false))
        assertEquals(Route.Stay, policy.route("https://www.facebook.com/tr?id=1", tapped = false, redirect = false))
        // A customer whose own site is listed as internal keeps it all.
        val own = LinkPolicy(listOf("*.google.com"))
        assertEquals(Route.Stay, own.route("https://accounts.google.com/signin", tapped = true, redirect = false))
    }

    @Test
    fun linksToOtherAppsNeedATap() {
        assertEquals(Route.Handoff("tel:+27311234567"), policy.route("tel:+27311234567", tapped = true, redirect = false))
        assertEquals(Route.Handoff("mailto:hi@shop.co.za"), policy.route("mailto:hi@shop.co.za", tapped = true, redirect = false))
        assertEquals(Route.Block, policy.route("whatsapp://send?text=hi", tapped = false, redirect = false))
        val intent = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        assertEquals(Route.Handoff(intent), policy.route(intent, tapped = true, redirect = false))
        assertEquals(Route.Block, policy.route(intent, tapped = false, redirect = true))
    }

    @Test
    fun localSchemesAreBlockedEvenWhenTapped() {
        assertEquals(Route.Block, policy.route("file:///sdcard/secret.txt", tapped = true, redirect = false))
        assertEquals(Route.Block, policy.route("content://com.android.contacts/data", tapped = true, redirect = false))
    }

    @Test
    fun pageInternalSchemesStay() {
        assertEquals(Route.Stay, policy.route("about:blank", tapped = false, redirect = false))
        assertEquals(Route.Stay, policy.route("blob:https://www.shop.co.za/1234", tapped = true, redirect = false))
    }

    @Test
    fun malformedAddressesAreBlocked() {
        assertEquals(Route.Block, policy.route("not a url", tapped = true, redirect = false))
        assertEquals(Route.Block, policy.route("https://", tapped = true, redirect = false))
    }

    @Test
    fun hostOfStripsUserInfoPortAndCase() {
        assertEquals("shop.co.za", LinkPolicy.hostOf("https://user:pw@Shop.co.za:8443/a?b#c"))
        assertEquals("shop.co.za", LinkPolicy.hostOf("https://shop.co.za?q=1"))
        assertEquals("[::1]", LinkPolicy.hostOf("http://[::1]:8080/"))
        // The last @ ends the user info, as browsers parse it.
        assertEquals("evil.com", LinkPolicy.hostOf("https://shop.co.za@evil.com/"))
        assertNull(LinkPolicy.hostOf("mailto:a@b.c"))
    }

    @Test
    fun samePageComparesHostAndPathOnly() {
        assertTrue(LinkPolicy.samePage("https://shop.co.za/", "https://www.shop.co.za"))
        assertTrue(LinkPolicy.samePage("https://shop.co.za/cart/", "http://shop.co.za/cart?step=2#top"))
        assertFalse(LinkPolicy.samePage("https://shop.co.za/cart", "https://shop.co.za/"))
        assertFalse(LinkPolicy.samePage("https://shop.co.za/", "https://other.co.za/"))
    }

    @Test
    fun pathOfRoot() {
        assertEquals("/", LinkPolicy.pathOf("https://shop.co.za"))
        assertEquals("/", LinkPolicy.pathOf("https://shop.co.za?x=1"))
        assertEquals("/a/b", LinkPolicy.pathOf("https://shop.co.za/a/b/"))
    }
}

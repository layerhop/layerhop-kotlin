package com.layerhop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebhooksTest {
    private val secret = "whsec_test"
    private val body = """{"id":"evt_1","type":"order.filled","data":{}}"""
    private val now = 1760000000L

    @Test
    fun knownVector() {
        assertEquals(
            "t=1760000000,v1=27def4e410e1269461f8318e4922b3fcc86ea21bc8d720c44c0bd5ded8770849",
            Webhooks.sign(secret, body, now),
        )
    }

    @Test
    fun table() {
        val hdr = Webhooks.sign(secret, body, now)
        val sig = hdr.substring(16)
        data class Case(val name: String, val secret: String, val body: String, val header: String?, val now: Long, val want: Boolean)
        val cases = listOf(
            Case("valid", secret, body, hdr, now, true),
            Case("valid within tolerance", secret, body, hdr, now + 299, true),
            Case("valid with rotated extra v1", secret, body, "t=1760000000,v1=00" + hdr.substring(18) + ",v1=" + sig, now, true),
            Case("spaces tolerated", secret, body, "t=1760000000, v1=$sig", now, true),
            Case("uppercase hex", secret, body, "t=1760000000,v1=${sig.uppercase()}", now, true),
            Case("too old", secret, body, hdr, now + 301, false),
            Case("from the future", secret, body, hdr, now - 301, false),
            Case("wrong secret", "other", body, hdr, now, false),
            Case("tampered body", secret, """{"id":"evt_2"}""", hdr, now, false),
            Case("missing t", secret, body, "v1=$sig", now, false),
            Case("missing v1", secret, body, "t=1760000000", now, false),
            Case("bad t", secret, body, "t=abc,v1=$sig", now, false),
            Case("garbage", secret, body, "nonsense", now, false),
            Case("null header", secret, body, null, now, false),
        )
        for (c in cases) {
            assertEquals(c.want, Webhooks.verifySignature(c.secret, c.body, c.header, nowSeconds = c.now), c.name)
        }
    }

    @Test
    fun constructEvent() {
        val hdr = Webhooks.sign(secret, body, now)
        val ev = Webhooks.constructEvent(secret, body, hdr, nowSeconds = now)
        assertEquals("evt_1", ev.id)
        assertEquals(WebhookEventTypes.ORDER_FILLED, ev.type)
        assertFailsWith<WebhookSignatureException> { Webhooks.constructEvent("other", body, hdr, nowSeconds = now) }
    }

    @Test
    fun byteArrayBody() {
        val bytes = body.toByteArray()
        val hdr = Webhooks.sign(secret, bytes, now)
        assertEquals(true, Webhooks.verifySignature(secret, bytes, hdr, nowSeconds = now))
    }
}

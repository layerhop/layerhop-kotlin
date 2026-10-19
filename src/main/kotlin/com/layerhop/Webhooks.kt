package com.layerhop

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook signature verification.
 *
 * LayerHop signs every delivery with the `LayerHop-Signature` header:
 * `t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>`. Several v1 values may be
 * present (secret rotation). Always verify the raw request body, before parsing JSON.
 */
public object Webhooks {
    public const val SIGNATURE_HEADER: String = "LayerHop-Signature"
    public const val EVENT_HEADER: String = "LayerHop-Event"
    public const val DELIVERY_HEADER: String = "LayerHop-Delivery"
    public const val DEFAULT_TOLERANCE_SECONDS: Long = 300

    /** Hex HMAC-SHA256 of "<timestamp>.<body>". */
    public fun computeSignature(secret: String, timestampSeconds: Long, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$timestampSeconds.".toByteArray(Charsets.US_ASCII))
        return mac.doFinal(body).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Builds a `LayerHop-Signature` header value; useful for testing your webhook handler. */
    public fun sign(secret: String, body: String, timestampSeconds: Long = nowSeconds()): String =
        sign(secret, body.toByteArray(Charsets.UTF_8), timestampSeconds)

    public fun sign(secret: String, body: ByteArray, timestampSeconds: Long = nowSeconds()): String =
        "t=$timestampSeconds,v1=${computeSignature(secret, timestampSeconds, body)}"

    /**
     * True when [header] is a valid, fresh signature of [body] for [secret]. False when the header
     * is missing or malformed, the timestamp is more than [toleranceSeconds] from [nowSeconds], or
     * no v1 value matches. Comparison is constant-time.
     */
    public fun verifySignature(
        secret: String,
        body: String,
        header: String?,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long = nowSeconds(),
    ): Boolean = verifySignature(secret, body.toByteArray(Charsets.UTF_8), header, toleranceSeconds, nowSeconds)

    public fun verifySignature(
        secret: String,
        body: ByteArray,
        header: String?,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long = nowSeconds(),
    ): Boolean {
        if (header.isNullOrEmpty()) return false
        val tol = if (toleranceSeconds <= 0) DEFAULT_TOLERANCE_SECONDS else toleranceSeconds
        var t: Long? = null
        val sigs = ArrayList<String>()
        for (part in header.split(",")) {
            val p = part.trim()
            val i = p.indexOf('=')
            if (i < 0) continue
            val k = p.substring(0, i)
            val v = p.substring(i + 1).trim()
            when (k) {
                "t" -> t = v.toLongOrNull() ?: return false
                "v1" -> sigs.add(v.lowercase())
            }
        }
        val ts = t ?: return false
        if (sigs.isEmpty()) return false
        if (Math.abs(nowSeconds - ts) > tol) return false
        val expected = computeSignature(secret, ts, body).toByteArray(Charsets.US_ASCII)
        var ok = false
        for (s in sigs) {
            if (MessageDigest.isEqual(expected, s.toByteArray(Charsets.US_ASCII))) ok = true
        }
        return ok
    }

    /** Verifies the signature and parses the event, or throws [WebhookSignatureException]. */
    public fun constructEvent(
        secret: String,
        body: String,
        header: String?,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long = nowSeconds(),
    ): WebhookEvent {
        if (!verifySignature(secret, body, header, toleranceSeconds, nowSeconds)) {
            throw WebhookSignatureException("Invalid or expired LayerHop-Signature")
        }
        return LayerHopJson.decodeFromString(WebhookEvent.serializer(), body)
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}

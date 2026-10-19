package com.layerhop

/**
 * Thrown for every non-2xx response. [code] is a stable error code such as QUOTE_EXPIRED or
 * NO_ROUTE (see [docsUrl]). Network failures surface as [java.io.IOException] instead.
 */
public open class LayerHopException(
    public val code: String,
    message: String,
    public val status: Int,
    public val requestId: String? = null,
) : RuntimeException(buildString {
    append("layerhop: ").append(code).append(" (").append(status).append("): ").append(message)
    if (!requestId.isNullOrEmpty()) append(" [requestId ").append(requestId).append("]")
}) {
    /** The message returned by the API, without the code / status prefix. */
    public val apiMessage: String = message

    /** The error catalog page for this code. */
    public val docsUrl: String get() = "https://docs.layerhop.com/errors/$code"
}

/** Thrown by [LayerHop.watch] when the order is not final before the timeout. */
public class WatchTimeoutException : RuntimeException("layerhop: watch timed out before the order was final")

/** Thrown by [Webhooks.constructEvent] for a missing, malformed, stale or wrong signature. */
public class WebhookSignatureException(message: String) : RuntimeException(message)

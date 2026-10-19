package com.layerhop

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The JSON configuration used by the SDK: lenient decoding, null fields omitted on encode. */
public val LayerHopJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
}

/** Options for [LayerHop]. Every field has a default. */
public data class LayerHopOptions(
    /** Defaults to https://api.layerhop.com. A trailing slash is removed. */
    val baseUrl: String = LayerHop.DEFAULT_BASE_URL,
    /** Sent as the x-api-key header. lh_test_ keys use the sandbox. */
    val apiKey: String? = null,
    /** Fee channel sent with every quote. */
    val channel: String = "api",
    /** Your partner id, sent as `integrator` on every quote. */
    val integrator: String? = null,
    /** Overrides the partner fee; honored only with a partner API key. */
    val integratorFeeBps: Int? = null,
    /** Defaults to a client with 30 s timeouts. Inject your own to share a connection pool. */
    val httpClient: OkHttpClient? = null,
    val userAgent: String = "layerhop-kotlin/${LayerHop.VERSION}",
)

/**
 * Client for the LayerHop REST API. Thread-safe; share one instance.
 *
 * Non-custodial: the client never holds private keys and never signs. It returns unsigned
 * transactions ([BuiltTx]) that your own wallet signs and broadcasts. Amounts are integer strings
 * in base units.
 */
public class LayerHop(options: LayerHopOptions = LayerHopOptions()) {
    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.layerhop.com"
        public const val VERSION: String = "0.6.0"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    private val opts = options
    private val baseUrl: HttpUrl = options.baseUrl.trimEnd('/').ifEmpty { DEFAULT_BASE_URL }.toHttpUrl()
    private val http: OkHttpClient = options.httpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // -----------------------------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------------------------

    private suspend fun <T> call(
        method: String,
        path: List<String>,
        out: KSerializer<T>,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
    ): T {
        val url = baseUrl.newBuilder().addPathSegment("v1").apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        val rb = Request.Builder().url(url)
            .header("accept", "application/json")
            .header("user-agent", opts.userAgent)
        opts.apiKey?.takeIf { it.isNotEmpty() }?.let { rb.header("x-api-key", it) }
        val reqBody = body?.let { LayerHopJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON_TYPE) }
        rb.method(method, reqBody)
        if (reqBody != null) rb.header("content-type", "application/json")
        val (status, raw, requestIdHeader) = execute(http.newCall(rb.build()))
        if (status !in 200..299) throw decodeError(status, raw, requestIdHeader)
        return try {
            LayerHopJson.decodeFromString(out, raw)
        } catch (e: Exception) {
            throw IOException("layerhop: decode $method /v1/${path.joinToString("/")}: ${e.message}", e)
        }
    }

    private suspend fun execute(c: Call): Triple<Int, String, String?> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { c.cancel() }
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        cont.resume(Triple(it.code, it.body?.string() ?: "", it.header("x-request-id")))
                    }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                }
            }
        })
    }

    private fun decodeError(status: Int, raw: String, requestIdHeader: String?): LayerHopException {
        var code = "INTERNAL"
        var message = statusText(status)
        var requestId = requestIdHeader
        try {
            val err = LayerHopJson.parseToJsonElement(raw).jsonObject["error"]
            if (err is JsonObject) {
                (err["code"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { code = it }
                (err["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { message = it }
                (err["requestId"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { requestId = it }
            }
        } catch (_: Exception) {
            // Not JSON: keep the defaults.
        }
        return LayerHopException(code, message, status, requestId)
    }

    private fun statusText(status: Int): String = when (status) {
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        409 -> "Conflict"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"
        502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        else -> "HTTP $status"
    }

    private fun <T> json(ser: KSerializer<T>, value: T): JsonElement = LayerHopJson.encodeToJsonElement(ser, value)

    private val empty: JsonElement = JsonObject(emptyMap())

    private fun obj(vararg pairs: Pair<String, Any?>): JsonElement = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            is String -> put(k, v)
            is Boolean -> put(k, v)
            is Number -> put(k, v)
        }
    }

    private fun page(cursor: String?, limit: Int?): Map<String, String?> =
        mapOf("cursor" to cursor?.ifEmpty { null }, "limit" to limit?.takeIf { it > 0 }?.toString())

    // -----------------------------------------------------------------------------------------
    // Swaps
    // -----------------------------------------------------------------------------------------

    /** GET /v1/health. */
    public suspend fun health(): Health = call("GET", listOf("health"), Health.serializer())

    /** Every chain LayerHop can route from or to (GET /v1/chains). */
    public suspend fun chains(): List<Chain> = call("GET", listOf("chains"), ChainsBody.serializer()).chains

    /** Built-in tokens of one chain, by id or slug (GET /v1/tokens). */
    public suspend fun tokens(chain: String): List<Token> =
        call("GET", listOf("tokens"), TokensBody.serializer(), mapOf("chain" to chain)).tokens

    /**
     * USD prices for up to 100 `chainId:address` ids (GET /v1/prices). Ids without a known price
     * are left out of the result.
     */
    public suspend fun prices(ids: List<String>): Map<String, Double> =
        call("GET", listOf("prices"), PricesBody.serializer(), mapOf("ids" to ids.joinToString(","))).prices

    /** Asks every provider for a price (GET /v1/quote). `best` is the highest output for the user. */
    public suspend fun quote(p: QuoteParams): QuoteResponse {
        val q = linkedMapOf<String, String?>(
            "srcChain" to p.srcChain,
            "srcToken" to p.srcToken,
            "amount" to p.amount,
            "dstChain" to p.dstChain,
            "dstToken" to p.dstToken,
            "sender" to p.sender,
            "recipient" to p.recipient,
            "slippageBps" to (p.slippageBps?.takeIf { it != 0 } ?: 50).toString(),
            "channel" to opts.channel,
            "integrator" to opts.integrator?.ifEmpty { null },
            "integratorFeeBps" to opts.integratorFeeBps?.toString(),
        )
        val usd = p.gasOnArrivalUsd
        if (usd != null && usd > 0) {
            q["gasOnArrivalUsd"] = formatNumber(usd)
        } else if (p.gasOnArrival) {
            q["gasOnArrival"] = "true"
        }
        return call("GET", listOf("quote"), QuoteResponse.serializer(), q)
    }

    /** The unsigned transaction for a quote (POST /v1/orders). EVM txs may carry an approval. */
    public suspend fun buildTx(quoteId: String): BuiltTx =
        call("POST", listOf("orders"), TxBody.serializer(), body = obj("quoteId" to quoteId)).tx

    /**
     * Sends a wallet-signed Solana tx (base64) for submitViaApi quotes (POST /v1/orders/submit).
     * Returns the transaction signature.
     */
    public suspend fun submit(quoteId: String, signedTx: String): String =
        call("POST", listOf("orders", "submit"), TxHashBody.serializer(), body = obj("quoteId" to quoteId, "signedTransaction" to signedTx)).txHash

    /** Tells LayerHop the tx for a quote was broadcast (POST /v1/orders/{quoteId}/submitted). Idempotent. */
    public suspend fun reportSubmitted(quoteId: String, txHash: String): ReportSubmittedResponse =
        call("POST", listOf("orders", quoteId, "submitted"), ReportSubmittedResponse.serializer(), body = obj("txHash" to txHash))

    /** Builds the tx for a quote and dry-runs it without recording an order (POST /v1/simulate). */
    public suspend fun simulate(quoteId: String): SimulateResponse =
        call("POST", listOf("simulate"), SimulateResponse.serializer(), body = obj("quoteId" to quoteId))

    /** Current state of an order (GET /v1/orders/{provider}/{id}); id is the order id or source tx hash. */
    public suspend fun status(provider: String, id: String): OrderStatus =
        call("GET", listOf("orders", provider, id), OrderStatus.serializer())

    /** Orders by sender or recipient (GET /v1/orders). */
    public suspend fun listOrders(p: ListOrdersParams = ListOrdersParams()): ListOrdersResponse =
        call("GET", listOf("orders"), ListOrdersResponse.serializer(),
            mapOf("address" to p.address?.ifEmpty { null }, "status" to p.status?.ifEmpty { null }) + page(p.cursor, p.limit))

    /**
     * Polls [status] until the order is final (filled / refunded / failed), emitting each state
     * change. Throws [WatchTimeoutException] after [timeout]; cancel the collecting coroutine to stop.
     */
    public fun watch(provider: String, id: String, interval: Duration = 3.seconds, timeout: Duration = 30.minutes): Flow<OrderStatus> = flow {
        val started = TimeSource.Monotonic.markNow()
        var last: String? = null
        while (true) {
            val st = status(provider, id)
            if (st.state != last) {
                last = st.state
                emit(st)
            }
            if (OrderStates.isFinal(st.state)) return@flow
            if (started.elapsedNow() + interval >= timeout) throw WatchTimeoutException()
            delay(interval)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Checkout sessions, products, refunds
    // -----------------------------------------------------------------------------------------

    /** Creates a checkout session (POST /v1/checkout/sessions). Needs an API key. */
    public suspend fun createCheckoutSession(p: CreateCheckoutParams): CheckoutSession =
        call("POST", listOf("checkout", "sessions"), CheckoutSession.serializer(), body = json(CreateCheckoutParams.serializer(), p))

    /** GET /v1/checkout/sessions/{id}. Public: reference and metadata need the owner's key. */
    public suspend fun getCheckoutSession(id: String): CheckoutSession =
        call("GET", listOf("checkout", "sessions", id), CheckoutSession.serializer())

    /** Your sessions of the key's mode, newest first (GET /v1/checkout/sessions). */
    public suspend fun listCheckoutSessions(p: ListCheckoutSessionsParams = ListCheckoutSessionsParams()): ListCheckoutSessionsResponse =
        call("GET", listOf("checkout", "sessions"), ListCheckoutSessionsResponse.serializer(),
            mapOf("status" to p.status?.ifEmpty { null }) + page(p.cursor, p.limit))

    /** Cancels an open session (POST /v1/checkout/sessions/{id}/expire). */
    public suspend fun expireCheckoutSession(id: String): CheckoutSession =
        call("POST", listOf("checkout", "sessions", id, "expire"), CheckoutSession.serializer(), body = empty)

    /** Prices paying a session with a token (POST /v1/checkout/sessions/{id}/quote). Public. */
    public suspend fun quoteCheckoutSession(id: String, p: CheckoutQuoteParams): CheckoutQuoteResponse =
        call("POST", listOf("checkout", "sessions", id, "quote"), CheckoutQuoteResponse.serializer(), body = json(CheckoutQuoteParams.serializer(), p))

    /** Creates a product (POST /v1/checkout/products). Needs an API key. */
    public suspend fun createCheckoutProduct(p: CreateCheckoutProductParams): CheckoutProduct =
        call("POST", listOf("checkout", "products"), CheckoutProduct.serializer(), body = json(CreateCheckoutProductParams.serializer(), p))

    /** GET /v1/checkout/products/{id}. */
    public suspend fun getCheckoutProduct(id: String): CheckoutProduct =
        call("GET", listOf("checkout", "products", id), CheckoutProduct.serializer())

    /** GET /v1/checkout/products. */
    public suspend fun listCheckoutProducts(p: ListCheckoutProductsParams = ListCheckoutProductsParams()): ListCheckoutProductsResponse =
        call("GET", listOf("checkout", "products"), ListCheckoutProductsResponse.serializer(),
            mapOf("active" to p.active?.toString()) + page(p.cursor, p.limit))

    /** Changes only the set fields (PATCH /v1/checkout/products/{id}). */
    public suspend fun updateCheckoutProduct(id: String, p: UpdateCheckoutProductParams): CheckoutProduct =
        call("PATCH", listOf("checkout", "products", id), CheckoutProduct.serializer(), body = p.toJson())

    /** Starts a session on a product's terms (POST /v1/checkout/products/{id}/sessions). Public. */
    public suspend fun startProductCheckout(id: String, p: StartProductCheckoutParams = StartProductCheckoutParams()): CheckoutSession =
        call("POST", listOf("checkout", "products", id, "sessions"), CheckoutSession.serializer(), body = json(StartProductCheckoutParams.serializer(), p))

    /**
     * Refunds (part of) a paid session (POST /v1/checkout/sessions/{id}/refunds). Non-custodial:
     * build the returned quote with [buildTx], sign it from quote.from, then [reportSubmitted].
     */
    public suspend fun createRefund(sessionId: String, p: CreateRefundParams = CreateRefundParams()): CreateRefundResponse =
        call("POST", listOf("checkout", "sessions", sessionId, "refunds"), CreateRefundResponse.serializer(), body = json(CreateRefundParams.serializer(), p))

    /** A session's refunds, newest first (GET /v1/checkout/sessions/{id}/refunds). */
    public suspend fun listRefunds(sessionId: String): ListRefundsResponse =
        call("GET", listOf("checkout", "sessions", sessionId, "refunds"), ListRefundsResponse.serializer())

    // -----------------------------------------------------------------------------------------
    // Invoices
    // -----------------------------------------------------------------------------------------

    /** POST /v1/invoices. Draft unless send = true. Needs an API key. */
    public suspend fun createInvoice(p: CreateInvoiceParams): Invoice =
        call("POST", listOf("invoices"), Invoice.serializer(), body = json(CreateInvoiceParams.serializer(), p))

    public suspend fun getInvoice(id: String): Invoice = call("GET", listOf("invoices", id), Invoice.serializer())

    public suspend fun listInvoices(p: ListInvoicesParams = ListInvoicesParams()): ListInvoicesResponse =
        call("GET", listOf("invoices"), ListInvoicesResponse.serializer(), mapOf("status" to p.status?.ifEmpty { null }) + page(p.cursor, p.limit))

    /** Edits a draft (PATCH /v1/invoices/{id}). */
    public suspend fun updateInvoice(id: String, p: UpdateInvoiceParams): Invoice =
        call("PATCH", listOf("invoices", id), Invoice.serializer(), body = p.toJson())

    /** Finalizes and emails a draft, or re-sends an open invoice (POST /v1/invoices/{id}/send). */
    public suspend fun sendInvoice(id: String): Invoice = call("POST", listOf("invoices", id, "send"), Invoice.serializer(), body = empty)

    /** POST /v1/invoices/{id}/void. */
    public suspend fun voidInvoice(id: String): Invoice = call("POST", listOf("invoices", id, "void"), Invoice.serializer(), body = empty)

    /** The customer's view of a sent invoice (GET /v1/invoices/{id}/public). Public. */
    public suspend fun getPublicInvoice(id: String): Invoice = call("GET", listOf("invoices", id, "public"), Invoice.serializer())

    /** Starts (or reuses) the checkout session paying an open invoice (POST /v1/invoices/{id}/pay). */
    public suspend fun payInvoice(id: String): PayInvoiceResponse =
        call("POST", listOf("invoices", id, "pay"), PayInvoiceResponse.serializer(), body = empty)

    // -----------------------------------------------------------------------------------------
    // Deposit addresses
    // -----------------------------------------------------------------------------------------

    /**
     * Creates a deposit address (POST /v1/deposit-addresses). integrator / integratorFeeBps default
     * to the client's options. Needs an API key.
     */
    public suspend fun createDepositAddress(p: CreateDepositAddressParams): DepositAddress {
        val withDefaults = p.copy(
            integrator = p.integrator?.ifEmpty { null } ?: opts.integrator,
            integratorFeeBps = p.integratorFeeBps ?: opts.integratorFeeBps,
        )
        return call("POST", listOf("deposit-addresses"), DepositAddress.serializer(), body = json(CreateDepositAddressParams.serializer(), withDefaults))
    }

    public suspend fun getDepositAddress(id: String): DepositAddress =
        call("GET", listOf("deposit-addresses", id), DepositAddress.serializer())

    public suspend fun listDepositAddresses(p: ListDepositAddressesParams = ListDepositAddressesParams()): ListDepositAddressesResponse =
        call("GET", listOf("deposit-addresses"), ListDepositAddressesResponse.serializer(),
            mapOf("status" to p.status?.ifEmpty { null }, "label" to p.label?.ifEmpty { null }) + page(p.cursor, p.limit))

    /** Checks the address for deposits now (POST /v1/deposit-addresses/{id}/refresh). */
    public suspend fun refreshDepositAddress(id: String): DepositAddress =
        call("POST", listOf("deposit-addresses", id, "refresh"), DepositAddress.serializer(), body = empty)

    /** Stops tracking the address (POST /v1/deposit-addresses/{id}/disable). */
    public suspend fun disableDepositAddress(id: String): DepositAddress =
        call("POST", listOf("deposit-addresses", id, "disable"), DepositAddress.serializer(), body = empty)

    /** Records a test deposit on a test-mode address (POST /v1/deposit-addresses/{id}/simulate). */
    public suspend fun simulateDeposit(id: String, amount: String): SimulateDepositResponse =
        call("POST", listOf("deposit-addresses", id, "simulate"), SimulateDepositResponse.serializer(), body = obj("amount" to amount))

    // -----------------------------------------------------------------------------------------
    // Subscriptions
    // -----------------------------------------------------------------------------------------

    /** POST /v1/subscriptions. LayerHop emails an invoice every period. Needs an API key. */
    public suspend fun createSubscription(p: CreateSubscriptionParams): Subscription =
        call("POST", listOf("subscriptions"), Subscription.serializer(), body = json(CreateSubscriptionParams.serializer(), p))

    public suspend fun getSubscription(id: String): Subscription = call("GET", listOf("subscriptions", id), Subscription.serializer())

    public suspend fun listSubscriptions(p: ListSubscriptionsParams = ListSubscriptionsParams()): ListSubscriptionsResponse =
        call("GET", listOf("subscriptions"), ListSubscriptionsResponse.serializer(),
            mapOf("status" to p.status?.ifEmpty { null }, "customerEmail" to p.customerEmail?.ifEmpty { null }) + page(p.cursor, p.limit))

    /** PATCH /v1/subscriptions/{id}; changes apply from the next invoice. */
    public suspend fun updateSubscription(id: String, p: UpdateSubscriptionParams): Subscription =
        call("PATCH", listOf("subscriptions", id), Subscription.serializer(), body = p.toJson())

    /** Cancels now, or at the end of the current period when [atPeriodEnd] is true. */
    public suspend fun cancelSubscription(id: String, atPeriodEnd: Boolean = false): Subscription =
        call("POST", listOf("subscriptions", id, "cancel"), Subscription.serializer(), body = obj("atPeriodEnd" to atPeriodEnd))

    public suspend fun pauseSubscription(id: String): Subscription =
        call("POST", listOf("subscriptions", id, "pause"), Subscription.serializer(), body = empty)

    public suspend fun resumeSubscription(id: String): Subscription =
        call("POST", listOf("subscriptions", id, "resume"), Subscription.serializer(), body = empty)

    /** Invoices issued for a subscription (up to 100), newest first. */
    public suspend fun listSubscriptionInvoices(id: String): List<Invoice> =
        call("GET", listOf("subscriptions", id, "invoices"), InvoicesBody.serializer()).invoices

    // -----------------------------------------------------------------------------------------
    // Limit orders (Solana, beta)
    // -----------------------------------------------------------------------------------------

    /**
     * Creates a Solana limit order and returns it with the unsigned tx (POST /v1/limit-orders).
     * Have the wallet sign it (sign only), then [submitLimitOrder]. channel / integrator /
     * integratorFeeBps default to the client's options.
     */
    public suspend fun createLimitOrder(p: CreateLimitOrderParams): LimitOrder {
        val withDefaults = p.copy(
            channel = p.channel?.ifEmpty { null } ?: opts.channel,
            integrator = p.integrator?.ifEmpty { null } ?: opts.integrator,
            integratorFeeBps = p.integratorFeeBps ?: opts.integratorFeeBps,
        )
        return call("POST", listOf("limit-orders"), LimitOrder.serializer(), body = json(CreateLimitOrderParams.serializer(), withDefaults))
    }

    /** Sends the wallet-signed create or cancel tx (base64) (POST /v1/limit-orders/{id}/submit). */
    public suspend fun submitLimitOrder(id: String, signedTx: String): SubmitLimitOrderResponse =
        call("POST", listOf("limit-orders", id, "submit"), SubmitLimitOrderResponse.serializer(), body = obj("signedTransaction" to signedTx))

    public suspend fun getLimitOrder(id: String): LimitOrder = call("GET", listOf("limit-orders", id), LimitOrder.serializer())

    /** Orders of a wallet, or of the API key's account without one (GET /v1/limit-orders). */
    public suspend fun listLimitOrders(p: ListLimitOrdersParams = ListLimitOrdersParams()): ListLimitOrdersResponse =
        call("GET", listOf("limit-orders"), ListLimitOrdersResponse.serializer(),
            mapOf("wallet" to p.wallet?.ifEmpty { null }, "status" to p.status?.ifEmpty { null }) + page(p.cursor, p.limit))

    /** Cancels a limit order; an open order returns the cancel tx to sign and submit. */
    public suspend fun cancelLimitOrder(id: String): CancelLimitOrderResponse =
        call("POST", listOf("limit-orders", id, "cancel"), CancelLimitOrderResponse.serializer(), body = empty)

    /** Test mode: fill, half-fill or expire an open order (see [LimitOrderSimulations]). */
    public suspend fun simulateLimitOrder(id: String, outcome: String): LimitOrder =
        call("POST", listOf("limit-orders", id, "simulate"), LimitOrder.serializer(), body = obj("outcome" to outcome))

    // -----------------------------------------------------------------------------------------
    // Misc
    // -----------------------------------------------------------------------------------------

    /** LayerHop's public service status (GET /v1/status), as on status.layerhop.com. */
    public suspend fun serviceStatus(): StatusResponse = call("GET", listOf("status"), StatusResponse.serializer())

    /** Creates a sample event in test mode (POST /v1/webhooks/trigger). Test keys only. */
    public suspend fun triggerWebhookEvent(type: String): TriggerWebhookResponse =
        call("POST", listOf("webhooks", "trigger"), TriggerWebhookResponse.serializer(), body = obj("type" to type))
}

private fun formatNumber(d: Double): String =
    if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

@Serializable
private class ChainsBody(val chains: List<Chain> = emptyList())

@Serializable
private class TokensBody(val tokens: List<Token> = emptyList())

@Serializable
private class PricesBody(val prices: Map<String, Double> = emptyMap())

@Serializable
private class TxBody(val tx: BuiltTx = BuiltTx())

@Serializable
private class TxHashBody(val txHash: String = "")

@Serializable
private class InvoicesBody(val invoices: List<Invoice> = emptyList())

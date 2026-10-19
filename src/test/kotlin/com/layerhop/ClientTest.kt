package com.layerhop

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClientTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun client(opts: LayerHopOptions = LayerHopOptions()) =
        LayerHop(opts.copy(baseUrl = server.url("/").toString()))

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    @Test
    fun quoteBuildsQueryAndHeaders() = runBlocking {
        server.enqueue(json("""{"best":{"quoteId":"q1","provider":"relay","dstAmount":"99","dstAmountMin":"98","estimatedSeconds":20,"fees":{"ourFeeBps":10},"expiresAt":1760000000,"gasOnArrival":{"amount":"1000","token":"0x0","symbol":"ETH","decimals":18,"usd":2.0}},"quotes":[],"errors":[{"provider":"x","code":"NO_ROUTE","message":"no"}],"extra":1}"""))
        val lh = client(LayerHopOptions(apiKey = "lh_test_abc", integrator = "myapp", integratorFeeBps = 25))
        val r = lh.quote(QuoteParams("base", "USDC", "100000000", "arbitrum", "USDC", "0xS", "0xR", gasOnArrival = true))
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/quote", req.requestUrl!!.encodedPath)
        val u = req.requestUrl!!
        assertEquals("base", u.queryParameter("srcChain"))
        assertEquals("100000000", u.queryParameter("amount"))
        assertEquals("50", u.queryParameter("slippageBps"))
        assertEquals("api", u.queryParameter("channel"))
        assertEquals("myapp", u.queryParameter("integrator"))
        assertEquals("25", u.queryParameter("integratorFeeBps"))
        assertEquals("true", u.queryParameter("gasOnArrival"))
        assertNull(u.queryParameter("gasOnArrivalUsd"))
        assertEquals("lh_test_abc", req.getHeader("x-api-key"))
        assertEquals("layerhop-kotlin/0.6.0", req.getHeader("user-agent"))
        assertEquals("application/json", req.getHeader("accept"))
        assertEquals("q1", r.best.quoteId)
        assertEquals("1000", r.best.gasOnArrival!!.amount)
        assertEquals("NO_ROUTE", r.errors[0].code)
    }

    @Test
    fun quoteGasOnArrivalUsd() = runBlocking {
        server.enqueue(json("""{"best":{},"quotes":[],"errors":[]}"""))
        client().quote(QuoteParams("base", "USDC", "1", "arbitrum", "ETH", "0xS", "0xR", slippageBps = 100, gasOnArrival = true, gasOnArrivalUsd = 5.5))
        val u = server.takeRequest().requestUrl!!
        assertEquals("5.5", u.queryParameter("gasOnArrivalUsd"))
        assertNull(u.queryParameter("gasOnArrival"))
        assertEquals("100", u.queryParameter("slippageBps"))
        assertNull(u.queryParameter("integrator"))
        assertNull(server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun chainsTokensPrices() = runBlocking {
        server.enqueue(json("""{"chains":[{"id":7565164,"slug":"solana","name":"Solana","vm":"svm","nativeSymbol":"SOL","nativeDecimals":9,"explorerTx":"https://solscan.io/tx/"}]}"""))
        server.enqueue(json("""{"tokens":[{"chainId":8453,"address":"0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913","symbol":"USDC","decimals":6}]}"""))
        server.enqueue(json("""{"prices":{"8453:0xA":0.9998,"7565164:So1":103}}"""))
        val lh = client()
        assertEquals(SOLANA_CHAIN_ID, lh.chains()[0].id)
        assertEquals("/v1/chains", server.takeRequest().path)
        assertEquals("USDC", lh.tokens("base")[0].symbol)
        assertEquals("/v1/tokens?chain=base", server.takeRequest().path)
        val p = lh.prices(listOf("8453:0xA", "7565164:So1"))
        assertEquals(103.0, p["7565164:So1"])
        assertEquals("8453:0xA,7565164:So1", server.takeRequest().requestUrl!!.queryParameter("ids"))
    }

    @Test
    fun buildTxEvmAndSvm() = runBlocking {
        server.enqueue(json("""{"tx":{"vm":"evm","chainId":8453,"to":"0xT","data":"0xdead","value":"0","approval":{"token":"0xU","spender":"0xS","amount":"100"},"extraTxs":[{"to":"0xF","data":"0x01","value":"0"}]}}"""))
        server.enqueue(json("""{"tx":{"vm":"svm","serializedTx":"AQID","submitViaApi":true,"sandbox":true}}"""))
        val lh = client()
        val evm = lh.buildTx("q1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/orders", req.path)
        assertEquals("application/json", req.getHeader("content-type")!!.substringBefore(";"))
        assertEquals("""{"quoteId":"q1"}""", req.body.readUtf8())
        assertTrue(evm.isEvm)
        val e = evm.asEvm()
        assertEquals(8453L, e.chainId)
        assertEquals("0xS", e.approval!!.spender)
        assertEquals("0xF", e.extraTxs[0].to)
        val svm = lh.buildTx("q2").asSvm()
        assertEquals("AQID", svm.serializedTx)
        assertTrue(svm.submitViaApi)
        assertTrue(svm.sandbox)
        assertFailsWith<IllegalStateException> { evm.asSvm() }
    }

    @Test
    fun submitReportSimulate() = runBlocking {
        server.enqueue(json("""{"txHash":"sig1"}"""))
        server.enqueue(json("""{"quoteId":"q/1","status":"submitted"}"""))
        server.enqueue(json("""{"ok":true,"vm":"evm","gasEstimate":123456789012345678901234567890,"computeUnits":"42"}"""))
        val lh = client()
        assertEquals("sig1", lh.submit("q1", "BASE64"))
        assertEquals("""{"quoteId":"q1","signedTransaction":"BASE64"}""", server.takeRequest().body.readUtf8())
        assertEquals("submitted", lh.reportSubmitted("q/1", "0xH").status)
        assertEquals("/v1/orders/q%2F1/submitted", server.takeRequest().path)
        val sim = lh.simulate("q1")
        assertEquals("123456789012345678901234567890", sim.gasEstimate)
        assertEquals("42", sim.computeUnits)
    }

    @Test
    fun errorsFromBodyAndHeader() = runBlocking {
        server.enqueue(json("""{"error":{"code":"QUOTE_EXPIRED","message":"Quote expired","requestId":"req_body"}}""", 410).setHeader("x-request-id", "req_hdr"))
        server.enqueue(MockResponse().setResponseCode(502).setHeader("x-request-id", "req_hdr2").setBody("<html>bad gateway</html>"))
        val lh = client()
        val e1 = assertFailsWith<LayerHopException> { lh.buildTx("q1") }
        assertEquals("QUOTE_EXPIRED", e1.code)
        assertEquals(410, e1.status)
        assertEquals("req_body", e1.requestId)
        assertEquals("Quote expired", e1.apiMessage)
        assertEquals("https://docs.layerhop.com/errors/QUOTE_EXPIRED", e1.docsUrl)
        assertTrue(e1.message!!.contains("[requestId req_body]"))
        val e2 = assertFailsWith<LayerHopException> { lh.health() }
        assertEquals("INTERNAL", e2.code)
        assertEquals(502, e2.status)
        assertEquals("req_hdr2", e2.requestId)
        assertEquals("Bad Gateway", e2.apiMessage)
    }

    @Test
    fun watchEmitsStateChangesUntilFinal() = runBlocking {
        server.enqueue(json("""{"provider":"relay","id":"0xH","state":"pending"}"""))
        server.enqueue(json("""{"provider":"relay","id":"0xH","state":"pending"}"""))
        server.enqueue(json("""{"provider":"relay","id":"0xH","state":"filled","dstTxHash":"0xD"}"""))
        val states = client().watch("relay", "0xH", interval = 10.milliseconds).toList()
        assertEquals(listOf("pending", "filled"), states.map { it.state })
        assertEquals("0xD", states.last().dstTxHash)
        assertEquals(3, server.requestCount)
        assertEquals("/v1/orders/relay/0xH", server.takeRequest().path)
    }

    @Test
    fun watchTimesOut() = runBlocking {
        repeat(5) { server.enqueue(json("""{"provider":"relay","id":"0xH","state":"pending"}""")) }
        assertFailsWith<WatchTimeoutException> {
            client().watch("relay", "0xH", interval = 20.milliseconds, timeout = 50.milliseconds).toList()
        }
        Unit
    }

    @Test
    fun listOrdersQuery() = runBlocking {
        server.enqueue(json("""{"orders":[{"quoteId":"q1","status":"filled","livemode":false}],"nextCursor":"c2"}"""))
        val r = client().listOrders(ListOrdersParams(address = "0xA", limit = 10))
        assertEquals("/v1/orders?address=0xA&limit=10", server.takeRequest().path)
        assertEquals("c2", r.nextCursor)
        assertEquals(false, r.orders[0].livemode)
    }

    @Test
    fun checkoutSessions() = runBlocking {
        val session = """{"id":"cs_1","object":"checkout.session","livemode":false,"status":"open","amount":"49000000","settlement":{"chainId":8453,"token":"0xU","symbol":"USDC","decimals":6},"recipient":"0xR","url":"https://layerhop.com/pay/cs_1","expiresAt":"2026-10-19T00:00:00Z","payment":null,"createdAt":"x","updatedAt":"y"}"""
        server.enqueue(json(session))
        server.enqueue(json(session.replace("\"open\"", "\"expired\"")))
        server.enqueue(json("""{"sessionId":"cs_1","livemode":false,"amountDue":"49000000","best":{"quoteId":"q1","srcAmount":"50000000"},"quotes":[],"errors":[]}"""))
        server.enqueue(json("""{"sessions":[$session]}"""))
        val lh = client(LayerHopOptions(apiKey = "lh_test_x"))
        val s = lh.createCheckoutSession(CreateCheckoutParams("49000000", "base", "USDC", "0xR", successUrl = "https://x"))
        val req = server.takeRequest()
        assertEquals("/v1/checkout/sessions", req.path)
        assertEquals("""{"amount":"49000000","settleChain":"base","settleToken":"USDC","recipient":"0xR","successUrl":"https://x"}""", req.body.readUtf8())
        assertEquals("checkout.session", s.objectType)
        assertNull(s.payment)
        assertEquals(CheckoutStatuses.EXPIRED, lh.expireCheckoutSession("cs_1").status)
        val exp = server.takeRequest()
        assertEquals("/v1/checkout/sessions/cs_1/expire", exp.path)
        assertEquals("{}", exp.body.readUtf8())
        assertEquals("50000000", lh.quoteCheckoutSession("cs_1", CheckoutQuoteParams("solana", "SOL", "Payer")).best.srcAmount)
        assertEquals("""{"srcChain":"solana","srcToken":"SOL","sender":"Payer"}""", server.takeRequest().body.readUtf8())
        assertEquals(1, lh.listCheckoutSessions(ListCheckoutSessionsParams(status = "open")).sessions.size)
        assertEquals("/v1/checkout/sessions?status=open", server.takeRequest().path)
    }

    @Test
    fun productsUpdateSendsOnlySetFieldsAndNullClears() = runBlocking {
        server.enqueue(json("""{"id":"prod_1","object":"checkout.product","active":false,"name":"Pro","amount":"1","url":"u"}"""))
        server.enqueue(json("""{"id":"cs_9","status":"open","url":"https://layerhop.com/pay/cs_9"}"""))
        server.enqueue(json("""{"products":[],"nextCursor":null}"""))
        val lh = client()
        val p = lh.updateCheckoutProduct("prod_1", UpdateCheckoutProductParams(name = "Pro", description = "", allowedOrigins = emptyList(), active = false))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("""{"name":"Pro","description":null,"allowedOrigins":[],"active":false}""", req.body.readUtf8())
        assertFalse(p.active)
        assertEquals("cs_9", lh.startProductCheckout("prod_1", StartProductCheckoutParams(reference = "order-1")).id)
        assertEquals("""{"reference":"order-1"}""", server.takeRequest().body.readUtf8())
        assertNull(lh.listCheckoutProducts(ListCheckoutProductsParams(active = true)).nextCursor)
        assertEquals("/v1/checkout/products?active=true", server.takeRequest().path)
    }

    @Test
    fun invoices() = runBlocking {
        val inv = """{"id":"inv_1","object":"invoice","number":"A-1","status":"draft","lineItems":[{"description":"Pro","quantity":2,"unitAmount":"5000000"}],"amount":"10000000","url":"u","createdAt":"c"}"""
        server.enqueue(json(inv))
        server.enqueue(json(inv))
        server.enqueue(json("""{"sessionId":"cs_1","url":"https://layerhop.com/pay/cs_1"}"""))
        val lh = client()
        lh.createInvoice(CreateInvoiceParams(listOf(InvoiceLineItem("Pro", 1, "5000000")), "base", "USDC", "0xR", customerEmail = "a@b.c"))
        val c = server.takeRequest().bodyJson()
        assertEquals("1", c["lineItems"]!!.toString().substringAfter("\"quantity\":").substringBefore(","))
        assertFalse(c.containsKey("send"))
        val u = lh.updateInvoice("inv_1", UpdateInvoiceParams(memo = "", lineItems = listOf(InvoiceLineItem("Pro", 2, "5000000"))))
        assertEquals("""{"memo":null,"lineItems":[{"description":"Pro","quantity":2,"unitAmount":"5000000"}]}""", server.takeRequest().body.readUtf8())
        assertEquals(2, u.lineItems[0].quantity)
        assertEquals("cs_1", lh.payInvoice("inv_1").sessionId)
        assertEquals("/v1/invoices/inv_1/pay", server.takeRequest().path)
    }

    @Test
    fun depositAddressesDefaultIntegrator() = runBlocking {
        server.enqueue(json("""{"id":"da_1","object":"deposit_address","status":"active","provider":"sandbox","address":"0xD","chainId":8453,"reusable":true,"fees":{"ourFeeBps":10,"integratorFeeBps":25,"integrator":"myapp"},"estimate":{"amount":"1","dstAmount":"1","dstAmountMin":"1","estimatedSeconds":5}}"""))
        server.enqueue(json("""{"order":{"quoteId":"q1","depositAddressId":"da_1"}}"""))
        val lh = client(LayerHopOptions(integrator = "myapp", integratorFeeBps = 25))
        val d = lh.createDepositAddress(CreateDepositAddressParams("base", "USDC", "solana", "SOL", "SolR", "0xRefund"))
        val b = server.takeRequest().bodyJson()
        assertEquals("\"myapp\"", b["integrator"].toString())
        assertEquals("25", b["integratorFeeBps"].toString())
        assertFalse(b.containsKey("reusable"))
        assertEquals("0xD", d.address)
        assertEquals(5, d.estimate!!.estimatedSeconds)
        assertEquals("da_1", lh.simulateDeposit("da_1", "4001").order.depositAddressId)
        assertEquals("""{"amount":"4001"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun subscriptions() = runBlocking {
        val sub = """{"id":"sub_1","object":"subscription","status":"active","customerEmail":"a@b.c","description":"Pro","amount":"1","interval":"month","intervalCount":1,"cancelAtPeriodEnd":true}"""
        server.enqueue(json(sub))
        server.enqueue(json(sub))
        server.enqueue(json(sub))
        server.enqueue(json("""{"invoices":[{"id":"inv_1","subscriptionId":"sub_1"}]}"""))
        val lh = client()
        lh.createSubscription(CreateSubscriptionParams("a@b.c", SubscriptionIntervals.MONTH, productId = "prod_1"))
        assertEquals("""{"customerEmail":"a@b.c","interval":"month","productId":"prod_1"}""", server.takeRequest().body.readUtf8())
        lh.updateSubscription("sub_1", UpdateSubscriptionParams(customerName = "", metadata = emptyMap(), cancelAtPeriodEnd = false))
        assertEquals("""{"customerName":null,"metadata":null,"cancelAtPeriodEnd":false}""", server.takeRequest().body.readUtf8())
        assertTrue(lh.cancelSubscription("sub_1", atPeriodEnd = true).cancelAtPeriodEnd)
        assertEquals("""{"atPeriodEnd":true}""", server.takeRequest().body.readUtf8())
        assertEquals("sub_1", lh.listSubscriptionInvoices("sub_1")[0].subscriptionId)
    }

    @Test
    fun limitOrders() = runBlocking {
        server.enqueue(json("""{"id":"lo_1","object":"limit_order","status":"built","provider":"sandbox","chainId":7565164,"tx":{"vm":"svm","serializedTx":"AA==","submitViaApi":true}}"""))
        server.enqueue(json("""{"limitOrder":{"id":"lo_1","status":"open"},"txHash":"sig"}"""))
        server.enqueue(json("""{"limitOrder":{"id":"lo_1","status":"cancelled"},"tx":null}"""))
        server.enqueue(json("""{"limitOrders":[],"nextCursor":null}"""))
        val lh = client(LayerHopOptions(channel = "mobile", integrator = "myapp"))
        val lo = lh.createLimitOrder(CreateLimitOrderParams("Wallet", "USDC", "SOL", "10000000", "50000000"))
        val b = server.takeRequest().bodyJson()
        assertEquals("\"mobile\"", b["channel"].toString())
        assertEquals("\"myapp\"", b["integrator"].toString())
        assertFalse(b.containsKey("expiresAt"))
        assertEquals("AA==", lo.tx!!.asSvm().serializedTx)
        assertEquals("open", lh.submitLimitOrder("lo_1", "SIGNED").limitOrder.status)
        assertEquals("""{"signedTransaction":"SIGNED"}""", server.takeRequest().body.readUtf8())
        assertNull(lh.cancelLimitOrder("lo_1").tx)
        server.takeRequest()
        lh.listLimitOrders(ListLimitOrdersParams(wallet = "Wallet", status = "open"))
        assertEquals("/v1/limit-orders?wallet=Wallet&status=open", server.takeRequest().path)
    }

    @Test
    fun refundsAndStatus() = runBlocking {
        server.enqueue(json("""{"refund":{"id":"re_1","status":"pending","amount":"1","to":{"chainId":8453,"token":"0xU","address":"0xP"}},"quote":{"quoteId":"q9","from":"0xR"},"next":"build"}"""))
        server.enqueue(json("""{"status":"operational","components":[{"id":"api","latencyMs":null,"days":[{"date":"d","uptime":null}]}],"incidents":[]}"""))
        val lh = client()
        val r = lh.createRefund("cs_1", CreateRefundParams(to = RefundDestinations.SETTLEMENT))
        assertEquals("""{"to":"settlement"}""", server.takeRequest().body.readUtf8())
        assertEquals("0xR", r.quote.from)
        val st = lh.serviceStatus()
        assertNull(st.components[0].latencyMs)
        assertEquals("operational", st.status)
    }

    @Test
    fun baseUrlTrailingSlashAndDefaultTimeouts() = runBlocking {
        server.enqueue(json("""{"ok":true,"providers":["relay"]}"""))
        val lh = LayerHop(LayerHopOptions(baseUrl = server.url("/").toString() + "/"))
        assertTrue(lh.health().ok)
        assertEquals("/v1/health", server.takeRequest().path)
        assertEquals(30.seconds.inWholeSeconds, 30L)
    }
}

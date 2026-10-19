# LayerHop Kotlin SDK

Kotlin client for the [LayerHop](https://layerhop.com) cross-chain swap API, for the JVM and
Android. You get best-price swaps across EVM chains and Solana, checkout sessions, products,
invoices, refunds, deposit addresses, subscriptions and Solana limit orders, all through
coroutines.

**Non-custodial.** The SDK never holds private keys and never signs anything. It fetches quotes
and builds *unsigned* transactions, and your app's wallet signs and broadcasts them (for example
Reown AppKit / WalletConnect, or the Solana Mobile Wallet Adapter). Amounts are integer strings in
base units (100 USDC = `"100000000"`).

- JVM 8+ and Android (minSdk 21). Java 8 bytecode, no Android Gradle plugin needed.
- Dependencies: OkHttp 4, kotlinx.coroutines, kotlinx.serialization.
- Docs: <https://docs.layerhop.com/sdks/kotlin>

## Install

Through [JitPack](https://jitpack.io/#layerhop/layerhop-kotlin) (builds each release tag from this repository):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.layerhop:layerhop-kotlin:v0.6.0")
}
```

Maven Central (`com.layerhop:layerhop`) follows once the `com.layerhop` namespace is verified there.

## Quickstart

```kotlin
import com.layerhop.*

val lh = LayerHop(LayerHopOptions(integrator = "myapp"))

suspend fun swap() {
    val quote = lh.quote(
        QuoteParams(
            srcChain = "base", srcToken = "USDC", amount = "100000000", // 100 USDC
            dstChain = "solana", dstToken = "SOL",
            sender = "0xYourEvmAddress", recipient = "YourSolanaAddress",
            gasOnArrival = false,
        )
    )
    val best = quote.best
    println("${best.provider}: ${best.dstAmount} lamports in ~${best.estimatedSeconds}s")

    val tx = lh.buildTx(best.quoteId) // unsigned
    if (tx.isEvm) {
        val evm = tx.asEvm()
        // 1. evm.approval?.let { send ERC-20 approve(spender, amount) and wait for it }
        // 2. send { to = evm.to, data = evm.data, value = evm.value } on evm.chainId with your wallet
        // 3. send each of evm.extraTxs in order
        val txHash = "0x..." // returned by your wallet
        lh.reportSubmitted(best.quoteId, txHash)
        lh.watch(best.provider, txHash).collect { println(it.state) } // pending -> filled
    } else {
        val svm = tx.asSvm() // base64 VersionedTransaction
        // svm.submitViaApi: sign only, then lh.submit(best.quoteId, signedBase64)
    }
}
```

Gas on arrival: set `gasOnArrival = true` (about $2 of native gas delivered to the recipient on the
destination chain) or `gasOnArrivalUsd = 5.0`. Check `quote.best.gasOnArrival` for whether the route
includes it.

## Android

Add the internet permission to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Create one client (for example in your `Application` or DI graph) and call it from a coroutine
scope. Requests run on OkHttp's own threads and are cancelled with the coroutine.

```kotlin
class SwapViewModel : ViewModel() {
    private val lh = LayerHop(LayerHopOptions(integrator = "myapp"))
    val state = MutableStateFlow("idle")

    fun quote(sender: String, recipient: String) = viewModelScope.launch {
        try {
            val q = lh.quote(QuoteParams("base", "USDC", "25000000", "arbitrum", "ETH", sender, recipient))
            state.value = "Receive ${q.best.dstAmount} wei via ${q.best.provider}"
        } catch (e: LayerHopException) {
            state.value = "${e.code}: ${e.apiMessage}" // e.g. NO_ROUTE, see e.docsUrl
        } catch (e: java.io.IOException) {
            state.value = "Network error"
        }
    }
}
```

Hand the unsigned transaction to the wallet: `tx.asEvm()` fields map directly onto an
`eth_sendTransaction` request through Reown AppKit / WalletConnect; `tx.asSvm().serializedTx` is a
base64 transaction for `signAndSendTransactions` (or `signTransactions` when `submitViaApi`) in the
Solana Mobile Wallet Adapter. Do not ship an API key inside an app: quotes and swaps work without
one, and keyed calls (checkout, invoices, subscriptions, deposit addresses) belong on your server.

R8 / ProGuard: the SDK uses kotlinx.serialization's generated serializers (no reflection), so the
default rules shipped with kotlinx.serialization are enough.

## Checkout, products, invoices, subscriptions

```kotlin
val server = LayerHop(LayerHopOptions(apiKey = System.getenv("LAYERHOP_API_KEY")))

val session = server.createCheckoutSession(
    CreateCheckoutParams(amount = "49000000", settleChain = "base", settleToken = "USDC",
        recipient = "0xYourPayoutAddress", successUrl = "https://shop.example.com/thanks")
)
// send the customer to session.url; fulfil on checkout.session.completed

val sub = server.createSubscription(
    CreateSubscriptionParams(customerEmail = "a@example.com", interval = SubscriptionIntervals.MONTH, productId = "prod_123")
)

// PATCH helpers send only the fields you set; "" clears a nullable field (sends null).
server.updateInvoice("inv_123", UpdateInvoiceParams(memo = ""))
```

Also available: `getCheckoutSession`, `listCheckoutSessions`, `expireCheckoutSession`,
`quoteCheckoutSession`, `createCheckoutProduct` / `updateCheckoutProduct` / `startProductCheckout`,
`createInvoice` / `sendInvoice` / `voidInvoice` / `payInvoice`, `createRefund` / `listRefunds`,
`createDepositAddress` / `refreshDepositAddress` / `disableDepositAddress` / `simulateDeposit`,
`pauseSubscription` / `resumeSubscription` / `cancelSubscription`, `createLimitOrder` /
`submitLimitOrder` / `cancelLimitOrder` / `simulateLimitOrder`, `prices`, `serviceStatus`,
`triggerWebhookEvent`.

## Webhooks

Verify the `LayerHop-Signature` header against the raw body before parsing it. Ktor:

```kotlin
post("/webhooks/layerhop") {
    val raw = call.receiveText()
    val event = try {
        Webhooks.constructEvent(System.getenv("LAYERHOP_WEBHOOK_SECRET"), raw, call.request.header(Webhooks.SIGNATURE_HEADER))
    } catch (e: WebhookSignatureException) {
        return@post call.respond(HttpStatusCode.BadRequest)
    }
    when (event.type) {
        WebhookEventTypes.CHECKOUT_SESSION_COMPLETED -> fulfil(event.data.session!!)
        WebhookEventTypes.ORDER_FILLED -> println(event.data.order?.dstTxHash)
    }
    call.respond(HttpStatusCode.OK)
}
```

Spring: take the body as `@RequestBody raw: String` and the header as
`@RequestHeader("LayerHop-Signature") sig: String`, then call `Webhooks.verifySignature(secret, raw, sig)`.

## Errors

Every non-2xx response throws `LayerHopException` with a stable `code` (`QUOTE_EXPIRED`,
`NO_ROUTE`, ...), the HTTP `status`, the `requestId` to quote to support, and `docsUrl`. Network
failures throw `java.io.IOException`.

## Test mode

Use an `lh_test_...` key and every call is served by the sandbox: live-priced quotes, placeholder
transactions (`tx.sandbox == true`, never broadcast them) and orders that fill in about 10 seconds.

## License

MIT

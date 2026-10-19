package com.layerhop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** LayerHop's internal chain id for Solana. */
public const val SOLANA_CHAIN_ID: Long = 7565164L

/** Order states reported by [LayerHop.status]. Filled, refunded and failed are final. */
public object OrderStates {
    public const val PENDING: String = "pending"
    public const val FILLED: String = "filled"
    public const val REFUNDED: String = "refunded"
    public const val FAILED: String = "failed"
    public const val UNKNOWN: String = "unknown"

    /** True when the state will not change any more. */
    public fun isFinal(state: String?): Boolean = state == FILLED || state == REFUNDED || state == FAILED
}

/**
 * An integer kept as a decimal string. Decodes from a JSON string or number, so big values (gas,
 * base-unit amounts) never lose precision.
 */
public object NumStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.layerhop.NumString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder is JsonDecoder) {
            val el = decoder.decodeJsonElement()
            if (el is JsonNull) return ""
            if (el is JsonPrimitive) return el.content
            return el.toString()
        }
        return decoder.decodeString()
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

@Serializable
public data class Health(val ok: Boolean = false, val providers: List<String> = emptyList())

/** One entry of the chain registry. */
@Serializable
public data class Chain(
    val id: Long = 0,
    val slug: String = "",
    val name: String = "",
    val vm: String = "",
    val nativeSymbol: String = "",
    val nativeDecimals: Int = 0,
    val explorerTx: String = "",
)

/** One entry of the built-in token list. */
@Serializable
public data class Token(
    val chainId: Long = 0,
    val address: String = "",
    val symbol: String = "",
    val decimals: Int = 0,
)

/** Inputs of [LayerHop.quote]. */
public data class QuoteParams(
    /** Chain id or slug ("base", "solana", "8453"). */
    val srcChain: String,
    /** Symbol from the token list ("USDC") or an address / mint. */
    val srcToken: String,
    /** Integer string in base units of [srcToken]. */
    val amount: String,
    val dstChain: String,
    val dstToken: String,
    val sender: String,
    val recipient: String,
    /** Defaults to 50 when null or 0. */
    val slippageBps: Int? = null,
    /**
     * Also deliver native gas to [recipient] on the destination chain, worth $2 by default (sent as
     * gasOnArrival=true). Check [Quote.gasOnArrival] for whether the route includes it.
     */
    val gasOnArrival: Boolean = false,
    /** USD value of that gas (0.5-20); implies [gasOnArrival] (sent as gasOnArrivalUsd). */
    val gasOnArrivalUsd: Double? = null,
)

/** Native gas a route delivers on top of dstAmount; its cost is already taken out of dstAmount. */
@Serializable
public data class GasOnArrival(
    val amount: String = "",
    val token: String = "",
    val symbol: String = "",
    val decimals: Int = 0,
    val usd: Double? = null,
)

@Serializable
public data class FeeBreakdown(
    val ourFeeBps: Int = 0,
    val providerFeeUsd: Double? = null,
    val gasUsd: Double? = null,
    val integratorFeeBps: Int? = null,
    val integrator: String? = null,
)

/** One provider's priced route. */
@Serializable
public data class Quote(
    val quoteId: String = "",
    val provider: String = "",
    val dstAmount: String = "",
    val dstAmountMin: String = "",
    val estimatedSeconds: Int = 0,
    val fees: FeeBreakdown = FeeBreakdown(),
    /** Unix seconds. */
    val expiresAt: Long = 0,
    val gasOnArrival: GasOnArrival? = null,
)

@Serializable
public data class ProviderFailure(val provider: String = "", val code: String = "", val message: String = "")

/** Result of [LayerHop.quote]. best == quotes[0]. */
@Serializable
public data class QuoteResponse(
    val best: Quote = Quote(),
    val quotes: List<Quote> = emptyList(),
    val errors: List<ProviderFailure> = emptyList(),
    /** False when the request used an lh_test_ key. Null means live. */
    val livemode: Boolean? = null,
)

/** ERC-20 approve(spender, amount) that must be mined before the main transaction. */
@Serializable
public data class Approval(val token: String = "", val spender: String = "", val amount: String = "")

/** A plain EVM call: target, hex calldata and native value in wei. */
@Serializable
public data class EvmCall(val to: String = "", val data: String = "", val value: String = "")

/**
 * The unsigned transaction for the user's wallet, discriminated by [vm] ("evm" | "svm"). Use
 * [asEvm] / [asSvm] for a typed view. The SDK never signs: your wallet does.
 */
@Serializable
public data class BuiltTx(
    val vm: String = "",
    val chainId: Long = 0,
    val to: String = "",
    val data: String = "",
    val value: String = "",
    val approval: Approval? = null,
    /** Further calls to send after the main transaction, in order. Report the main tx hash. */
    val extraTxs: List<EvmCall> = emptyList(),
    /** Base64 serialized Solana VersionedTransaction. */
    val serializedTx: String = "",
    /** Sign only, then send through [LayerHop.submit] instead of an RPC node. */
    val submitViaApi: Boolean = false,
    /** True in test mode: a placeholder that moves no funds. Never broadcast it. */
    val sandbox: Boolean = false,
) {
    val isEvm: Boolean get() = vm == "evm"
    val isSvm: Boolean get() = vm == "svm"

    /** The EVM view; throws [IllegalStateException] for a Solana transaction. */
    public fun asEvm(): EvmTx {
        check(isEvm) { "not an EVM transaction (vm=$vm)" }
        return EvmTx(chainId, to, data, value, approval, extraTxs, sandbox)
    }

    /** The Solana view; throws [IllegalStateException] for an EVM transaction. */
    public fun asSvm(): SvmTx {
        check(isSvm) { "not a Solana transaction (vm=$vm)" }
        return SvmTx(serializedTx, submitViaApi, sandbox)
    }
}

/** An unsigned EVM transaction. Send [approval] first if set, then this, then [extraTxs]. */
public data class EvmTx(
    val chainId: Long,
    val to: String,
    val data: String,
    val value: String,
    val approval: Approval?,
    val extraTxs: List<EvmCall>,
    val sandbox: Boolean,
)

/** An unsigned Solana transaction (base64 VersionedTransaction). */
public data class SvmTx(val serializedTx: String, val submitViaApi: Boolean, val sandbox: Boolean)

@Serializable
public data class ReportSubmittedResponse(val quoteId: String = "", val status: String = "")

@Serializable
public data class SimulationError(val code: String = "", val message: String = "", val logs: List<String> = emptyList())

@Serializable
public data class SimulateResponse(
    val quoteId: String? = null,
    val provider: String? = null,
    val ok: Boolean = false,
    val vm: String = "",
    /** "tx" or "approval". */
    val simulated: String? = null,
    @Serializable(with = NumStringSerializer::class) val gasEstimate: String? = null,
    @Serializable(with = NumStringSerializer::class) val computeUnits: String? = null,
    val needsApproval: Boolean = false,
    val error: SimulationError? = null,
    val tx: BuiltTx? = null,
)

@Serializable
public data class OrderStatus(
    val provider: String = "",
    val id: String = "",
    val state: String = "",
    val srcTxHash: String? = null,
    val dstTxHash: String? = null,
)

/** One order row. Fields are optional because the listing is lenient. */
@Serializable
public data class Order(
    val quoteId: String? = null,
    val provider: String? = null,
    val status: String? = null,
    val statusDetail: String? = null,
    val channel: String? = null,
    val ourFeeBps: Int? = null,
    val partnerFeeBps: Int? = null,
    val srcChainId: Long? = null,
    val srcToken: String? = null,
    val dstChainId: Long? = null,
    val dstToken: String? = null,
    val amount: String? = null,
    val dstAmount: String? = null,
    val sender: String? = null,
    val recipient: String? = null,
    val srcTxHash: String? = null,
    val dstTxHash: String? = null,
    val livemode: Boolean? = null,
    val checkoutSessionId: String? = null,
    val depositAddressId: String? = null,
    val refundId: String? = null,
    val limitOrderId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

public data class ListOrdersParams(
    /** Matches the sender or the recipient. */
    val address: String? = null,
    val status: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
)

@Serializable
public data class ListOrdersResponse(val orders: List<Order> = emptyList(), val nextCursor: String? = null)

// ---------------------------------------------------------------------------------------------
// Checkout
// ---------------------------------------------------------------------------------------------

/** Checkout session statuses. Paid is final; treat processing as not yet paid. */
public object CheckoutStatuses {
    public const val OPEN: String = "open"
    public const val PROCESSING: String = "processing"
    public const val PAID: String = "paid"
    public const val EXPIRED: String = "expired"
    public const val NEEDS_REVIEW: String = "needs_review"
}

@Serializable
public data class CreateCheckoutParams(
    /** Amount due in base units of the settlement token (49 USDC = "49000000"). */
    val amount: String,
    val settleChain: String,
    val settleToken: String,
    val recipient: String,
    val merchantName: String? = null,
    val description: String? = null,
    val reference: String? = null,
    val metadata: Map<String, String>? = null,
    val successUrl: String? = null,
    val cancelUrl: String? = null,
    val expiresInMinutes: Int? = null,
)

@Serializable
public data class CheckoutSettlement(
    val chainId: Long = 0,
    val token: String = "",
    val symbol: String = "",
    val decimals: Int = 0,
)

@Serializable
public data class CheckoutPayment(
    val quoteId: String = "",
    val payer: String? = null,
    val srcChainId: Long? = null,
    val srcToken: String? = null,
    val srcAmount: String? = null,
    val srcTxHash: String? = null,
    val dstTxHash: String? = null,
    val paidAt: String? = null,
)

@Serializable
public data class CheckoutSession(
    val id: String = "",
    @SerialName("object") val objectType: String = "",
    val livemode: Boolean = false,
    val status: String = "",
    val statusDetail: String? = null,
    val amount: String = "",
    val settlement: CheckoutSettlement = CheckoutSettlement(),
    val recipient: String = "",
    val merchantName: String? = null,
    val description: String? = null,
    /** Hosted checkout page: send the payer here. */
    val url: String = "",
    val productId: String? = null,
    val invoiceId: String? = null,
    val refundedAmount: String? = null,
    val successUrl: String? = null,
    val cancelUrl: String? = null,
    val expiresAt: String = "",
    val payment: CheckoutPayment? = null,
    val reference: String? = null,
    val metadata: Map<String, String>? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

public data class ListCheckoutSessionsParams(val status: String? = null, val cursor: String? = null, val limit: Int? = null)

@Serializable
public data class ListCheckoutSessionsResponse(val sessions: List<CheckoutSession> = emptyList(), val nextCursor: String? = null)

@Serializable
public data class CheckoutQuoteParams(
    val srcChain: String,
    val srcToken: String,
    val sender: String,
    val slippageBps: Int? = null,
)

@Serializable
public data class CheckoutQuote(
    val quoteId: String = "",
    val provider: String = "",
    val dstAmount: String = "",
    val dstAmountMin: String = "",
    val estimatedSeconds: Int = 0,
    val fees: FeeBreakdown = FeeBreakdown(),
    val expiresAt: Long = 0,
    val gasOnArrival: GasOnArrival? = null,
    /** What the payer sends, in base units of the chosen token. */
    val srcAmount: String = "",
)

@Serializable
public data class CheckoutQuoteResponse(
    val sessionId: String = "",
    val livemode: Boolean = false,
    val amountDue: String = "",
    val best: CheckoutQuote = CheckoutQuote(),
    val quotes: List<CheckoutQuote> = emptyList(),
    val errors: List<ProviderFailure> = emptyList(),
)

@Serializable
public data class CreateCheckoutProductParams(
    val name: String,
    val amount: String,
    val settleChain: String,
    val settleToken: String,
    val recipient: String,
    val description: String? = null,
    val merchantName: String? = null,
    val successUrl: String? = null,
    val cancelUrl: String? = null,
    val expiresInMinutes: Int? = null,
    val allowedOrigins: List<String>? = null,
    val active: Boolean? = null,
)

@Serializable
public data class CheckoutProduct(
    val id: String = "",
    @SerialName("object") val objectType: String = "",
    val livemode: Boolean = false,
    val active: Boolean = false,
    val name: String = "",
    val description: String? = null,
    val merchantName: String? = null,
    val amount: String = "",
    val settlement: CheckoutSettlement = CheckoutSettlement(),
    /** https://layerhop.com/buy/{id}. */
    val url: String = "",
    val recipient: String? = null,
    val successUrl: String? = null,
    val cancelUrl: String? = null,
    val expiresInMinutes: Int? = null,
    val allowedOrigins: List<String>? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

public data class ListCheckoutProductsParams(val active: Boolean? = null, val cursor: String? = null, val limit: Int? = null)

@Serializable
public data class ListCheckoutProductsResponse(val products: List<CheckoutProduct> = emptyList(), val nextCursor: String? = null)

@Serializable
public data class StartProductCheckoutParams(val reference: String? = null, val metadata: Map<String, String>? = null)

// ---------------------------------------------------------------------------------------------
// Invoices
// ---------------------------------------------------------------------------------------------

public object InvoiceStatuses {
    public const val DRAFT: String = "draft"
    public const val OPEN: String = "open"
    public const val PAID: String = "paid"
    public const val VOID: String = "void"
}

@Serializable
public data class InvoiceLineItem(val description: String = "", val quantity: Int = 1, val unitAmount: String = "")

@Serializable
public data class CreateInvoiceParams(
    val lineItems: List<InvoiceLineItem>,
    val settleChain: String,
    val settleToken: String,
    val recipient: String,
    val customerEmail: String? = null,
    val customerName: String? = null,
    val merchantName: String? = null,
    val memo: String? = null,
    /** ISO 8601 date or date-time. */
    val dueAt: String? = null,
    /** Finalize and email right away. */
    val send: Boolean? = null,
)

@Serializable
public data class Invoice(
    val id: String = "",
    @SerialName("object") val objectType: String = "",
    val livemode: Boolean = false,
    val number: String = "",
    val status: String = "",
    val merchantName: String? = null,
    val customerName: String? = null,
    val memo: String? = null,
    val lineItems: List<InvoiceLineItem> = emptyList(),
    val amount: String = "",
    val settlement: CheckoutSettlement = CheckoutSettlement(),
    val dueAt: String? = null,
    val paidAt: String? = null,
    val url: String = "",
    val receiptUrl: String? = null,
    val createdAt: String = "",
    val customerEmail: String? = null,
    val recipient: String? = null,
    val sessionId: String? = null,
    val subscriptionId: String? = null,
    val sentAt: String? = null,
    val receiptSentAt: String? = null,
    val voidedAt: String? = null,
    val updatedAt: String? = null,
)

public data class ListInvoicesParams(val status: String? = null, val cursor: String? = null, val limit: Int? = null)

@Serializable
public data class ListInvoicesResponse(val invoices: List<Invoice> = emptyList(), val nextCursor: String? = null)

@Serializable
public data class PayInvoiceResponse(val sessionId: String = "", val url: String = "")

// ---------------------------------------------------------------------------------------------
// Deposit addresses
// ---------------------------------------------------------------------------------------------

public object DepositAddressStatuses {
    public const val ACTIVE: String = "active"
    public const val DISABLED: String = "disabled"
}

@Serializable
public data class CreateDepositAddressParams(
    val srcChain: String,
    val srcToken: String,
    val dstChain: String,
    val dstToken: String,
    val recipient: String,
    /** The user's address on srcChain for refunds. Never a CEX deposit address. */
    val refundTo: String,
    /** Defaults to true (API). False makes a one-time address for exactly [amount]. */
    val reusable: Boolean? = null,
    val amount: String? = null,
    val label: String? = null,
    val metadata: Map<String, String>? = null,
    /** Default to the client's options when null. */
    val integrator: String? = null,
    val integratorFeeBps: Int? = null,
)

@Serializable
public data class DepositAddressFees(val ourFeeBps: Int = 0, val integratorFeeBps: Int = 0, val integrator: String? = null)

@Serializable
public data class DepositAddressEstimate(
    val amount: String = "",
    val dstAmount: String = "",
    val dstAmountMin: String = "",
    val estimatedSeconds: Int = 0,
)

@Serializable
public data class DepositAddress(
    val id: String = "",
    @SerialName("object") val objectType: String = "",
    val livemode: Boolean = false,
    val status: String = "",
    val provider: String = "",
    /** What your user sends [token] to on [chainId]. */
    val address: String = "",
    val chainId: Long = 0,
    val token: String = "",
    val dstChainId: Long = 0,
    val dstToken: String = "",
    val recipient: String = "",
    val refundTo: String = "",
    val reusable: Boolean = false,
    val amount: String? = null,
    val fees: DepositAddressFees = DepositAddressFees(),
    val label: String? = null,
    val metadata: Map<String, String>? = null,
    val depositCount: Int = 0,
    val lastDepositAt: String? = null,
    val createdAt: String = "",
    val estimate: DepositAddressEstimate? = null,
    val deposits: List<Order> = emptyList(),
)

public data class ListDepositAddressesParams(
    val status: String? = null,
    val label: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
)

@Serializable
public data class ListDepositAddressesResponse(val depositAddresses: List<DepositAddress> = emptyList(), val nextCursor: String? = null)

@Serializable
public data class SimulateDepositResponse(val order: Order = Order())

// ---------------------------------------------------------------------------------------------
// Subscriptions
// ---------------------------------------------------------------------------------------------

public object SubscriptionStatuses {
    public const val ACTIVE: String = "active"
    public const val PAST_DUE: String = "past_due"
    public const val PAUSED: String = "paused"
    public const val CANCELED: String = "canceled"
}

public object SubscriptionIntervals {
    public const val DAY: String = "day"
    public const val WEEK: String = "week"
    public const val MONTH: String = "month"
    public const val YEAR: String = "year"
}

/**
 * Body of POST /v1/subscriptions. Set [productId], or [amount], [settleChain], [settleToken],
 * [recipient] and [description].
 */
@Serializable
public data class CreateSubscriptionParams(
    val customerEmail: String,
    val interval: String,
    val intervalCount: Int? = null,
    val productId: String? = null,
    val amount: String? = null,
    val settleChain: String? = null,
    val settleToken: String? = null,
    val recipient: String? = null,
    val description: String? = null,
    val customerName: String? = null,
    val merchantName: String? = null,
    val memo: String? = null,
    val daysUntilDue: Int? = null,
    val startAt: String? = null,
    val metadata: Map<String, String>? = null,
)

@Serializable
public data class Subscription(
    val id: String = "",
    @SerialName("object") val objectType: String = "",
    val livemode: Boolean = false,
    val status: String = "",
    val customerEmail: String = "",
    val customerName: String? = null,
    val merchantName: String? = null,
    val description: String = "",
    val memo: String? = null,
    val productId: String? = null,
    val amount: String = "",
    val settlement: CheckoutSettlement = CheckoutSettlement(),
    val recipient: String = "",
    val interval: String = "",
    val intervalCount: Int = 1,
    val daysUntilDue: Int = 0,
    val currentPeriodStart: String? = null,
    val currentPeriodEnd: String? = null,
    val nextInvoiceAt: String? = null,
    val cancelAt: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val canceledAt: String? = null,
    val cancellationReason: String? = null,
    val pausedAt: String? = null,
    val latestInvoiceId: String? = null,
    val metadata: Map<String, String>? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

public data class ListSubscriptionsParams(
    val status: String? = null,
    val customerEmail: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
)

@Serializable
public data class ListSubscriptionsResponse(val subscriptions: List<Subscription> = emptyList(), val nextCursor: String? = null)

// ---------------------------------------------------------------------------------------------
// Limit orders
// ---------------------------------------------------------------------------------------------

/** Limit order statuses. Filled, cancelled, expired and failed are final. */
public object LimitOrderStatuses {
    public const val BUILT: String = "built"
    public const val OPEN: String = "open"
    public const val FILLED: String = "filled"
    public const val CANCELLED: String = "cancelled"
    public const val EXPIRED: String = "expired"
    public const val FAILED: String = "failed"
}

/** Outcomes for [LayerHop.simulateLimitOrder] (test mode only). */
public object LimitOrderSimulations {
    public const val FILL: String = "fill"
    public const val PARTIAL_FILL: String = "partial_fill"
    public const val EXPIRE: String = "expire"
}

@Serializable
public data class CreateLimitOrderParams(
    /** Maker wallet (base58): signs the order and receives the output. */
    val wallet: String,
    val srcToken: String,
    val dstToken: String,
    /** Base units of srcToken to sell. */
    val amount: String,
    /** Minimum base units of dstToken for the whole amount. */
    val dstAmount: String,
    /** Only "solana" for now; API default when null. */
    val chain: String? = null,
    /** Unix seconds; null means good-till-cancelled. */
    val expiresAt: Long? = null,
    val channel: String? = null,
    val metadata: Map<String, String>? = null,
    val integrator: String? = null,
    val integratorFeeBps: Int? = null,
)

@Serializable
public data class LimitOrderFees(val ourFeeBps: Int = 0, val integratorFeeBps: Int = 0, val integrator: String? = null)

@Serializable
public data class LimitOrder(
    val id: String = "",
    @SerialName("object") val objectType: String = "",
    val livemode: Boolean = false,
    val status: String = "",
    val statusDetail: String? = null,
    val provider: String = "",
    val chainId: Long = 0,
    val wallet: String = "",
    val srcToken: String = "",
    val dstToken: String = "",
    val amount: String = "",
    val dstAmount: String = "",
    val expiresAt: String? = null,
    val fees: LimitOrderFees = LimitOrderFees(),
    val providerOrderId: String = "",
    val filledAmount: String = "",
    val filledDstAmount: String = "",
    val fillCount: Int = 0,
    val openTxHash: String? = null,
    val cancelTxHash: String? = null,
    val channel: String = "",
    val metadata: Map<String, String>? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    /** Unsigned tx for the wallet to sign (sign only, then [LayerHop.submitLimitOrder]). */
    val tx: BuiltTx? = null,
    val fills: List<Order> = emptyList(),
)

public data class ListLimitOrdersParams(
    val wallet: String? = null,
    val status: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
)

@Serializable
public data class ListLimitOrdersResponse(val limitOrders: List<LimitOrder> = emptyList(), val nextCursor: String? = null)

@Serializable
public data class SubmitLimitOrderResponse(val limitOrder: LimitOrder = LimitOrder(), val txHash: String = "")

@Serializable
public data class CancelLimitOrderResponse(val limitOrder: LimitOrder = LimitOrder(), val tx: BuiltTx? = null)

// ---------------------------------------------------------------------------------------------
// Refunds
// ---------------------------------------------------------------------------------------------

public object RefundStatuses {
    public const val PENDING: String = "pending"
    public const val SUBMITTED: String = "submitted"
    public const val COMPLETED: String = "completed"
    public const val FAILED: String = "failed"
}

/** Refund destinations for [CreateRefundParams.to]. */
public object RefundDestinations {
    public const val ORIGINAL: String = "original"
    public const val SETTLEMENT: String = "settlement"
}

@Serializable
public data class CreateRefundParams(
    val amount: String? = null,
    val reason: String? = null,
    val from: String? = null,
    val to: String? = null,
)

@Serializable
public data class RefundDestination(val chainId: Long = 0, val token: String = "", val address: String = "")

@Serializable
public data class Refund(
    val id: String = "",
    @SerialName("object") val objectType: String = "",
    val sessionId: String? = null,
    val livemode: Boolean = false,
    val status: String = "",
    val statusDetail: String? = null,
    val amount: String = "",
    val reason: String? = null,
    val quoteId: String? = null,
    val to: RefundDestination = RefundDestination(),
    val txHash: String? = null,
    val createdAt: String = "",
    val updatedAt: String? = null,
)

/** The route that executes a refund: buildTx(quoteId), sign from [from], reportSubmitted. */
@Serializable
public data class RefundQuote(
    val quoteId: String = "",
    val provider: String = "",
    val srcAmount: String = "",
    val dstAmount: String = "",
    val dstAmountMin: String = "",
    val estimatedSeconds: Int = 0,
    val expiresAt: Long = 0,
    val from: String = "",
)

@Serializable
public data class CreateRefundResponse(val refund: Refund = Refund(), val quote: RefundQuote = RefundQuote(), val next: String = "")

@Serializable
public data class ListRefundsResponse(val refunds: List<Refund> = emptyList())

// ---------------------------------------------------------------------------------------------
// Service status
// ---------------------------------------------------------------------------------------------

@Serializable
public data class StatusDay(val date: String = "", val uptime: Double? = null)

@Serializable
public data class StatusComponent(
    val id: String = "",
    val name: String = "",
    val group: String = "",
    val status: String = "",
    val latencyMs: Int? = null,
    val uptime90d: Double? = null,
    val days: List<StatusDay> = emptyList(),
)

@Serializable
public data class StatusIncident(
    val id: String = "",
    val componentId: String = "",
    val component: String = "",
    val title: String = "",
    val startedAt: String = "",
    val resolvedAt: String? = null,
)

@Serializable
public data class StatusResponse(
    val status: String = "",
    val updatedAt: String = "",
    val components: List<StatusComponent> = emptyList(),
    val incidents: List<StatusIncident> = emptyList(),
    val url: String = "",
)

// ---------------------------------------------------------------------------------------------
// Webhook events
// ---------------------------------------------------------------------------------------------

/** Webhook event types. Dashboard samples have type "webhook.test". */
public object WebhookEventTypes {
    public const val ORDER_SUBMITTED: String = "order.submitted"
    public const val ORDER_FILLED: String = "order.filled"
    public const val ORDER_REFUNDED: String = "order.refunded"
    public const val ORDER_FAILED: String = "order.failed"
    public const val CHECKOUT_SESSION_PROCESSING: String = "checkout.session.processing"
    public const val CHECKOUT_SESSION_COMPLETED: String = "checkout.session.completed"
    public const val CHECKOUT_SESSION_PAYMENT_FAILED: String = "checkout.session.payment_failed"
    public const val CHECKOUT_SESSION_EXPIRED: String = "checkout.session.expired"
    public const val CHECKOUT_SESSION_REFUNDED: String = "checkout.session.refunded"
    public const val INVOICE_SENT: String = "invoice.sent"
    public const val INVOICE_PAID: String = "invoice.paid"
    public const val SUBSCRIPTION_CREATED: String = "subscription.created"
    public const val SUBSCRIPTION_RENEWED: String = "subscription.renewed"
    public const val SUBSCRIPTION_PAST_DUE: String = "subscription.past_due"
    public const val SUBSCRIPTION_CANCELED: String = "subscription.canceled"
    public const val LIMIT_ORDER_OPENED: String = "limit_order.opened"
    public const val LIMIT_ORDER_FILLED: String = "limit_order.filled"
    public const val LIMIT_ORDER_CANCELLED: String = "limit_order.cancelled"
    public const val LIMIT_ORDER_EXPIRED: String = "limit_order.expired"
    public const val LIMIT_ORDER_FAILED: String = "limit_order.failed"
}

/** The objects an event is about (see the Go SDK's WebhookEventData for which events set which). */
@Serializable
public data class WebhookEventData(
    val order: Order? = null,
    val session: CheckoutSession? = null,
    val refund: Refund? = null,
    val invoice: Invoice? = null,
    val subscription: Subscription? = null,
    val limitOrder: LimitOrder? = null,
    val fill: Order? = null,
)

/** The JSON body LayerHop POSTs to your webhook endpoints. */
@Serializable
public data class WebhookEvent(
    val id: String = "",
    val type: String = "",
    val livemode: Boolean = false,
    val sample: Boolean = false,
    val createdAt: String = "",
    val data: WebhookEventData = WebhookEventData(),
)

@Serializable
public data class TriggerWebhookResponse(val eventId: String = "", val deliveries: Int = 0, val payload: WebhookEvent = WebhookEvent())

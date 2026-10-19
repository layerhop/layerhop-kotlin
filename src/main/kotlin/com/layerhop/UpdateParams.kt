package com.layerhop

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// PATCH bodies: only non-null fields are sent. For nullable API fields, an empty string "" clears
// the field by sending JSON null (same convention as the Go SDK).

private fun MutableMap<String, JsonElement>.set(key: String, v: String?) {
    if (v != null) this[key] = JsonPrimitive(v)
}

private fun MutableMap<String, JsonElement>.nullable(key: String, v: String?) {
    if (v != null) this[key] = if (v.isEmpty()) JsonNull else JsonPrimitive(v)
}

/**
 * Body of PATCH /v1/checkout/products/{id}. "" clears description, merchantName, successUrl and
 * cancelUrl; a non-null empty [allowedOrigins] sends [] (any origin).
 */
public data class UpdateCheckoutProductParams(
    val name: String? = null,
    val description: String? = null,
    val merchantName: String? = null,
    val amount: String? = null,
    val settleChain: String? = null,
    val settleToken: String? = null,
    val recipient: String? = null,
    val successUrl: String? = null,
    val cancelUrl: String? = null,
    val expiresInMinutes: Int? = null,
    val allowedOrigins: List<String>? = null,
    val active: Boolean? = null,
) {
    public fun toJson(): JsonObject {
        val m = linkedMapOf<String, JsonElement>()
        m.set("name", name)
        m.nullable("description", description)
        m.nullable("merchantName", merchantName)
        m.set("amount", amount)
        m.set("settleChain", settleChain)
        m.set("settleToken", settleToken)
        m.set("recipient", recipient)
        m.nullable("successUrl", successUrl)
        m.nullable("cancelUrl", cancelUrl)
        expiresInMinutes?.let { m["expiresInMinutes"] = JsonPrimitive(it) }
        allowedOrigins?.let { list -> m["allowedOrigins"] = kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) }) }
        active?.let { m["active"] = JsonPrimitive(it) }
        return JsonObject(m)
    }
}

/**
 * Body of PATCH /v1/invoices/{id} (drafts only). "" clears customerEmail, customerName,
 * merchantName, memo and dueAt; a non-null [lineItems] replaces every line.
 */
public data class UpdateInvoiceParams(
    val customerEmail: String? = null,
    val customerName: String? = null,
    val merchantName: String? = null,
    val memo: String? = null,
    val lineItems: List<InvoiceLineItem>? = null,
    val settleChain: String? = null,
    val settleToken: String? = null,
    val recipient: String? = null,
    val dueAt: String? = null,
) {
    public fun toJson(): JsonObject {
        val m = linkedMapOf<String, JsonElement>()
        m.nullable("customerEmail", customerEmail)
        m.nullable("customerName", customerName)
        m.nullable("merchantName", merchantName)
        m.nullable("memo", memo)
        lineItems?.let { m["lineItems"] = LayerHopJson.encodeToJsonElement(ListSerializer(InvoiceLineItem.serializer()), it) }
        m.set("settleChain", settleChain)
        m.set("settleToken", settleToken)
        m.set("recipient", recipient)
        m.nullable("dueAt", dueAt)
        return JsonObject(m)
    }
}

/**
 * Body of PATCH /v1/subscriptions/{id}; changes apply from the next invoice. "" clears
 * customerName, merchantName and memo; a non-null empty [metadata] sends null.
 * [cancelAtPeriodEnd] true schedules a cancel at the end of the period; false undoes it.
 */
public data class UpdateSubscriptionParams(
    val customerEmail: String? = null,
    val customerName: String? = null,
    val merchantName: String? = null,
    val description: String? = null,
    val memo: String? = null,
    val amount: String? = null,
    val daysUntilDue: Int? = null,
    val metadata: Map<String, String>? = null,
    val cancelAtPeriodEnd: Boolean? = null,
) {
    public fun toJson(): JsonObject {
        val m = linkedMapOf<String, JsonElement>()
        m.set("customerEmail", customerEmail)
        m.nullable("customerName", customerName)
        m.nullable("merchantName", merchantName)
        m.set("description", description)
        m.nullable("memo", memo)
        m.set("amount", amount)
        daysUntilDue?.let { m["daysUntilDue"] = JsonPrimitive(it) }
        metadata?.let { md ->
            m["metadata"] = if (md.isEmpty()) JsonNull else buildJsonObject { md.forEach { (k, v) -> put(k, v) } }
        }
        cancelAtPeriodEnd?.let { m["cancelAtPeriodEnd"] = JsonPrimitive(it) }
        return JsonObject(m)
    }
}

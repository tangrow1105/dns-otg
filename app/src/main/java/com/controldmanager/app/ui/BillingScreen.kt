package com.controldmanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.controldmanager.app.api.objects
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

private data class BillingInfo(val product: JSONObject?, val active: JSONObject?, val payments: List<JSONObject>, val email: String)

/** "¥2,750" / "$20.00": the currency's own symbol and decimals. */
private fun money(amount: Double, code: String): String = runCatching {
    val c = Currency.getInstance(code.uppercase())
    NumberFormat.getCurrencyInstance(Locale.US).apply {
        currency = c
        // Use the currency's own decimals (yen has none).
        minimumFractionDigits = c.defaultFractionDigits.coerceAtLeast(0)
        maximumFractionDigits = c.defaultFractionDigits.coerceAtLeast(0)
    }.format(amount)
}.getOrDefault("%.2f %s".format(amount, code.uppercase()))

private fun every(months: Int) = when {
    months == 1 -> "monthly"
    months == 12 -> "yearly"
    months > 12 && months % 12 == 0 -> "every ${months / 12} years"
    months > 0 -> "every $months months"
    else -> ""
}

private fun method(m: String) = when (m.lowercase()) {
    "stripe", "credit card", "card" -> "Credit Card"
    "paypal" -> "PayPal"
    else -> m.replaceFirstChar { it.uppercase() }
}

/**
 * Billing, as the dashboard's Billing page: plan, subscription status, payment method, rebill, activation,
 * credit balance and receipts. Read-only here; cancelling or changing the card stays on the website.
 */
@Composable
fun BillingScreen(nav: NavHostController) {
    val session = LocalSession.current
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val loader = rememberLoader {
        val api = session.api
        val products = api.rawBilling("/billing/products").optJSONArray("products").objects()
        val subs = api.rawBilling("/billing/subscriptions").optJSONArray("subscriptions").objects()
        // Newest receipt first (by payment time, then date), like the dashboard.
        val payments = runCatching { api.rawBilling("/billing/payments").optJSONArray("payments").objects() }.getOrDefault(emptyList())
            .sortedWith(compareByDescending<JSONObject> { it.optLong("ts") }.thenByDescending { it.optString("date") })
        val email = runCatching { api.user().email }.getOrDefault("")
        BillingInfo(products.firstOrNull(), subs.firstOrNull { it.optString("state") == "active" }, payments, email)
    }
    var receipt by remember { mutableStateOf<Receipt?>(null) }
    Scaffold(
        topBar = { BackTopBar(nav, "Billing") },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LoaderBox(loader, Modifier.padding(pad)) { b ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                val product = b.product
                val sub = b.active ?: product?.optJSONObject("subscription")?.takeIf { it.optInt("status") == 1 }
                val currency = sub?.optString("currency")?.ifBlank { null } ?: b.payments.firstOrNull()?.optString("currency") ?: "usd"

                SectionHeader("Plan")
                CdCard(Modifier.fillMaxWidth()) {
                    Column {
                        BillRow("Plan Type", product?.optString("name")?.ifBlank { null } ?: "None")
                        GroupDivider()
                        if (sub != null) {
                            BillRow("Subscription Status", sub.optString("state").replaceFirstChar { it.uppercase() }) {
                                TextButton(onClick = { uri.openUri("https://controld.com/dashboard/billing") }) { Text("Cancel", color = Palette.Red) }
                            }
                            GroupDivider()
                            BillRow("Payment Method", method(sub.optString("method"))) {
                                TextButton(onClick = { uri.openUri("https://controld.com/dashboard/billing") }) { Text("Change Card", color = Palette.Muted) }
                            }
                            GroupDivider()
                            val months = product?.optJSONObject("price")?.optInt("duration") ?: 0
                            val amount = sub.optDouble("currency_amount").takeIf { !it.isNaN() } ?: sub.optDouble("amount", 0.0)
                            val next = sub.optLong("next_bill", 0).takeIf { it > 0 }
                                ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000)) }
                                ?: sub.optString("next_rebill_date").ifBlank { null }
                            BillRow("Rebilled", listOfNotNull("${money(amount, currency)} ${every(months)}".trim() + ".", next?.let { "Next payment on $it." }).joinToString(" "))
                            GroupDivider()
                            BillRow("Activated on", sub.optString("started"))
                        } else {
                            BillRow("Subscription Status", "Inactive")
                            product?.optString("expiry")?.ifBlank { null }?.let {
                                GroupDivider()
                                BillRow("Product Expiry", it)
                            }
                        }
                        GroupDivider()
                        val balance = b.payments.firstOrNull()?.optDouble("balance", 0.0) ?: 0.0
                        BillRow("Credit Balance", money(balance, currency).replace(Regex("[.,]00$"), ""))
                    }
                }

                SectionHeader("Receipts")
                if (b.payments.isEmpty()) EmptyState(Solar.ReceiptLong, "No receipts")
                b.payments.forEach { p ->
                    val code = p.optString("currency", "usd")
                    val billed = p.optJSONObject("price_point")?.optInt("already_billed") == 1
                    val amt = if (billed) 0.0 else p.optDouble("currency_amount").takeIf { !it.isNaN() } ?: p.optDouble("amount", 0.0)
                    val status = when {
                        p.optInt("tx_refunded") == 1 -> "Refunded" to Palette.Orange
                        p.optInt("tx_status") == 1 -> "Success" to Palette.Teal
                        else -> "Failed" to Palette.Red
                    }
                    val tx = p.optString("tx_id")
                    // Tap a receipt to view it (the website's "Open Receipt"), with Save PDF.
                    CdCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = { receipt = receiptOf(p, b.email) }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.optJSONObject("product")?.optString("name") ?: "-", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${money(amt, code)} (${code.uppercase()})", fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${p.optString("date")} · ${method(p.optString("method"))}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.weight(1f))
                                Pill(status.first, status.second)
                            }
                            if (tx.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tx, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { copyToClipboard(ctx, "Transaction ID", tx) }) {
                                    Icon(Solar.ContentCopy, "Copy transaction ID", tint = Palette.Muted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Text(
                    "Tap a receipt to view or save it as PDF. Cancelling or changing your card opens the Control D dashboard.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    receipt?.let { ReceiptSheet(it) { receipt = null } }
}

@Composable
private fun BillRow(title: String, value: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(value, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        action?.invoke()
    }
}


// ---------- Receipt (same content as controld.com/receipt, built from /users + /billing/payments) ----------

private data class Receipt(val tx: String, val date: String, val email: String, val product: String, val method: String, val amount: String)

private fun receiptOf(p: JSONObject, email: String): Receipt {
    val code = p.optString("currency", "usd")
    val billed = p.optJSONObject("price_point")?.optInt("already_billed") == 1
    val amt = if (billed) 0.0 else p.optDouble("currency_amount").takeIf { !it.isNaN() } ?: p.optDouble("amount", 0.0)
    return Receipt(
        tx = p.optString("tx_id"), date = p.optString("date"), email = email,
        product = p.optJSONObject("product")?.optString("name") ?: "-",
        method = method(p.optString("method")), amount = "${money(amt, code)} (${code.uppercase()})",
    )
}

private const val CD_ADDRESS = "ControlD Inc.\n555 Richmond St West #918\nToronto, ON, M5V 3B1\nCanada"

@Composable
private fun ReceiptSheet(r: Receipt, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    CdSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Paper card like the website's receipt: white in light mode, dark in dark mode (the saved PDF stays white).
            val ink = if (Palette.light) androidx.compose.ui.graphics.Color(0xFF111111) else Palette.Text
            Column(
                Modifier.fillMaxWidth()
                    .clip(CdShape)
                    .background(if (Palette.light) androidx.compose.ui.graphics.Color.White else Palette.CardHigh)
                    .padding(20.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides ink) {
                    Text("Control D", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = ink)
                    Spacer(Modifier.height(16.dp))
                    ReceiptLine("Receipt #", r.tx)
                    ReceiptLine("Date", r.date)
                    ReceiptBand("BILLED TO")
                    ReceiptLine("Email", r.email)
                    ReceiptBand("TRANSACTION DETAILS")
                    ReceiptLine("Product", r.product)
                    ReceiptLine("Payment Method", r.method)
                    ReceiptLine("Amount", r.amount)
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = ink)
                    Text(
                        CD_ADDRESS, style = MaterialTheme.typography.labelSmall, color = ink,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CdButton("Copy ID", { copyToClipboard(ctx, "Transaction ID", r.tx) }, Modifier.weight(1f), icon = Solar.ContentCopy)
                CdButton("Save PDF", { printReceipt(ctx, r) }, Modifier.weight(1f), icon = Solar.Pdf, tint = Palette.Teal)
            }
        }
    }
}

@Composable
private fun ReceiptLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun ReceiptBand(text: String) {
    Text(
        text, color = if (Palette.light) androidx.compose.ui.graphics.Color.White else Palette.Bg, fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(if (Palette.light) androidx.compose.ui.graphics.Color.Black else Palette.Text).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Opens Android's print screen (which offers Save as PDF) with the receipt laid out like the website's. */
private fun printReceipt(ctx: android.content.Context, r: Receipt) {
    fun line(l: String, v: String) = "<tr><th>${esc(l)}</th><td>${esc(v)}</td></tr>"
    val logo = """<svg width="34" height="27" viewBox="0 0 43 34"><path d="M2.6 16C1.73 16 1.04 15.27 1.18 14.41 2.4 7.34 8.52 2 16.24 2h3.14c.66 0 1.2.54 1.2 1.2C20.58 10.27 14.85 16 7.78 16z"/><path d="M2.44 17.94c-.87 0-1.57.73-1.42 1.59C2.25 26.61 8.52 32 16.24 32h3.14c.66 0 1.2-.54 1.2-1.2 0-7.08-5.72-12.82-12.8-12.84z"/><path d="M35.39 18c-7.07 0-12.8 5.73-12.8 12.8 0 .66.54 1.2 1.2 1.2h3.14c7.72 0 13.84-5.34 15.05-12.41.15-.85-.55-1.59-1.41-1.59z"/><path d="M40.57 16c.86 0 1.56-.73 1.41-1.59C40.77 7.34 34.64 2 26.93 2h-3.22c-.67 0-1.21.54-1.21 1.21.05 7.08 5.8 12.79 12.87 12.79z"/></svg>"""
    val html = """
        <html><head><meta charset="utf-8"><style>
        body{font-family:sans-serif;color:#111;margin:40px}
        .card{max-width:420px;margin:auto}
        .brand{display:flex;align-items:center;gap:8px;font-size:24px;font-weight:700;margin-bottom:18px}
        table{width:100%;border-collapse:collapse;font-size:14px}
        th{text-align:left;padding:6px 0}
        td{text-align:right;padding:6px 0;word-break:break-all}
        .band td{background:#000;color:#fff;text-align:left;font-size:11px;font-weight:700;padding:2px 4px}
        .addr{text-align:center;font-size:11px;border-top:1px solid #111;margin-top:10px;padding-top:10px;white-space:pre-line}
        </style></head><body><div class="card">
        <div class="brand">$logo Control D</div>
        <table>
        ${line("Receipt #", r.tx)}${line("Date", r.date)}
        <tr class="band"><td colspan="2">BILLED TO</td></tr>${line("Email", r.email)}
        <tr class="band"><td colspan="2">TRANSACTION DETAILS</td></tr>
        ${line("Product", r.product)}${line("Payment Method", r.method)}${line("Amount", r.amount)}
        </table>
        <div class="addr">${esc(CD_ADDRESS)}</div>
        </div></body></html>
    """.trimIndent()
    val web = android.webkit.WebView(ctx)
    web.webViewClient = object : android.webkit.WebViewClient() {
        override fun onPageFinished(view: android.webkit.WebView, url: String?) {
            val pm = ctx.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
            val name = "ControlD-receipt-${r.date}"
            pm.print(name, view.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

package com.controldmanager.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.controldmanager.app.api.Device
import java.io.File
import java.util.UUID

/**
 * An Apple configuration profile (.mobileconfig) that sets an iPhone, iPad or Mac to use [doh], the endpoint's
 * DNS-over-HTTPS address: Apple's standard "DNS Settings" payload, as Control D's own setup files use. Opening it
 * on the Apple device offers to install it (Settings, Profile Downloaded).
 */
internal fun appleDnsProfile(endpointName: String, doh: String, resolverId: String): String {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val name = esc(endpointName)
    val inner = UUID.randomUUID().toString().uppercase()
    val outer = UUID.randomUUID().toString().uppercase()
    val id = resolverId.filter { it.isLetterOrDigit() }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>PayloadContent</key>
            <array>
                <dict>
                    <key>DNSSettings</key>
                    <dict>
                        <key>DNSProtocol</key>
                        <string>HTTPS</string>
                        <key>ServerURL</key>
                        <string>${esc(doh)}</string>
                    </dict>
                    <key>PayloadDescription</key>
                    <string>Sends this device's DNS to Control D (endpoint $name), encrypted.</string>
                    <key>PayloadDisplayName</key>
                    <string>Control D: $name</string>
                    <key>PayloadIdentifier</key>
                    <string>com.apple.dnsSettings.managed.$inner</string>
                    <key>PayloadType</key>
                    <string>com.apple.dnsSettings.managed</string>
                    <key>PayloadUUID</key>
                    <string>$inner</string>
                    <key>PayloadVersion</key>
                    <integer>1</integer>
                </dict>
            </array>
            <key>PayloadDescription</key>
            <string>Control D DNS for the endpoint $name. Made with DNS OTG.</string>
            <key>PayloadDisplayName</key>
            <string>Control D: $name</string>
            <key>PayloadIdentifier</key>
            <string>app.dnsotg.controld.$id</string>
            <key>PayloadRemovalDisallowed</key>
            <false/>
            <key>PayloadType</key>
            <string>Configuration</string>
            <key>PayloadUUID</key>
            <string>$outer</string>
            <key>PayloadVersion</key>
            <integer>1</integer>
        </dict>
        </plist>
    """.trimIndent() + "\n"
}

/** Endpoint types an Apple setup profile is for: iPhone, iPad and Mac. */
internal fun isAppleProfileDevice(icon: String) = icon == "mobile-ios" || icon == "desktop-mac" || icon == "tablet-ios"

/** Writes the endpoint's profile to the app's cache and opens the share sheet (send it to the iPhone or Mac). */
internal fun shareAppleProfile(ctx: Context, d: Device) {
    val doh = d.resolvers?.doh ?: return
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    // One file at a time: an older share is replaced rather than left behind.
    dir.listFiles()?.forEach { it.delete() }
    val safe = d.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "endpoint" }
    val file = File(dir, "Control D - $safe.mobileconfig")
    file.writeText(appleDnsProfile(d.name, doh, d.resolvers.uid))
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/x-apple-aspen-config")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Control D setup for ${d.name}")
        .putExtra(Intent.EXTRA_TEXT, "Open this file on your iPhone, iPad or Mac, then install it in Settings (Profile Downloaded) to use Control D.")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Send the setup file"))
}

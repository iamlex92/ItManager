package com.itmanager.app.core.nettools

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Offline MAC vendor lookup over the IEEE list bundled in assets/oui.tsv, so it works with no internet or server. */
object OuiDatabase {
    @Volatile private var table: Map<String, String>? = null

    private suspend fun load(ctx: Context): Map<String, String> {
        table?.let { return it }
        // Parsing takes a moment; do not abandon it when the caller (a text field being typed into) restarts.
        return withContext(Dispatchers.IO + NonCancellable) {
            val text = ctx.applicationContext.assets.open("oui.tsv").use { it.bufferedReader(Charsets.UTF_8).readText() }
            parseOui(text).also { table = it }
        }
    }

    /** Vendor of a MAC (or of its first 6 digits); null when unknown or when the text is not a MAC. */
    suspend fun vendor(ctx: Context, mac: String): String? {
        val hex = macHex(mac) ?: return null
        return try {
            ouiLookup(load(ctx), hex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a missing database must not crash a lookup
        }
    }
}

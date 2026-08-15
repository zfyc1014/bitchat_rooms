package com.bitchat.android.model

import com.google.gson.Gson
import java.util.Base64

/**
 * Encodes and decodes a [ReplyQuote] into the plain-text message content so quote metadata survives
 * every transport (mesh broadcast, Noise private messages, and Nostr relay events) without touching
 * any wire format.
 *
 * The app already carries `@mentions` and `#channels` as text conventions inside the content field;
 * quotes follow the same approach. The payload is base64-encoded JSON bracketed by ASCII unit
 * separators (0x1F), an invisible control character that never appears in user-typed text, so a
 * client that predates this feature still renders the reply text and only carries a stray invisible
 * prefix.
 *
 * Layout: `\u001F<base64-json>\u001F<user text>`
 */
object ReplyQuoteCodec {
    private const val MARKER = "\u001F"
    private val gson = Gson()

    /** Returns [content] unchanged when there is nothing to quote. */
    fun encode(content: String, replyTo: ReplyQuote?): String {
        if (replyTo == null) return content
        val json = gson.toJson(replyTo)
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return MARKER + encoded + MARKER + content
    }

    /** Splits raw wire content into (cleanText, quote) or (raw, null) when no quote is present. */
    fun decode(raw: String): Pair<String, ReplyQuote?> {
        if (raw.isEmpty() || !raw.startsWith(MARKER)) return raw to null
        val end = raw.indexOf(MARKER, MARKER.length)
        if (end < 0) return raw to null
        val encoded = raw.substring(MARKER.length, end)
        val text = raw.substring(end + MARKER.length)
        val quote = try {
            val json = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            gson.fromJson(json, ReplyQuote::class.java)
        } catch (_: Exception) {
            null
        }
        return text to quote
    }
}

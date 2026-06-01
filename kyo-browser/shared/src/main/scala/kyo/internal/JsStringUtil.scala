package kyo.internal

/** Single source of truth for escaping a String so it can be safely embedded inside a JS single-quoted string literal.
  *
  * Handles backslash, single quote, and the three control characters (`\n`, `\r`, `\t`) that would otherwise either produce invalid JS
  * (newline inside a single-quoted string is a SyntaxError) or alter the string's content. Other control characters are left alone; if
  * callers pass arbitrary binary data they should base64-encode it themselves.
  *
  * Used by every callsite that injects an arbitrary string into JS (selectors, JS-injection helpers, and the navigation recorder snippet)
  * so they all escape identically.
  */
private[kyo] object JsStringUtil:

    def escapeJsString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

end JsStringUtil

/** Cross-platform RFC-3986 percent-encoder used to embed a page's HTML inside a `data:` URL.
  *
  * Equivalent to `URLEncoder.encode(s, "UTF-8").replace("+", "%20")`: unreserved chars (`A-Z a-z 0-9 - _ . ~`) pass through; everything
  * else is UTF-8 encoded and percent-escaped. Pure Scala, runs identically on JVM, JS, and Native.
  *
  * The `URLEncoder` from `java.net` uses `+` for space, which `data:` URLs interpret as a literal `+`; encoding via this helper avoids that
  * trap.
  */
private[kyo] object PercentEncode:
    def apply(s: String): String =
        val hex = "0123456789ABCDEF"
        def encodeByte(b: Byte): String =
            val u = b & 0xff
            val c = u.toChar
            val isUnreserved =
                (c >= 'A' && c <= 'Z') ||
                    (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9') ||
                    c == '-' || c == '_' || c == '.' || c == '~'
            if isUnreserved then c.toString
            else
                val sb = new StringBuilder(3)
                sb.append('%')
                sb.append(hex.charAt((u >>> 4) & 0x0f))
                sb.append(hex.charAt(u & 0x0f))
                sb.toString
            end if
        end encodeByte
        s.getBytes(java.nio.charset.StandardCharsets.UTF_8).iterator.map(encodeByte).mkString
    end apply
end PercentEncode

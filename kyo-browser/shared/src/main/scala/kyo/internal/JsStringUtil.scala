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

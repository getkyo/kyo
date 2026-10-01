package kyo.internal

import kyo.*

/** Case folding and character classes for protocol text: header and parameter names, media types, charset labels, protocol keywords.
  *
  * Those are case-insensitive over US-ASCII only (RFC 5234 section 2.3, RFC 9110 section 5.1, RFC 2045 section 5.1). The JDK's
  * `toLowerCase`, `toUpperCase` and `equalsIgnoreCase` fold by the default locale and by Unicode, so under a Turkish locale `I` lowers to a
  * dotless `ı`, and `ı`, `İ` and the Kelvin sign fold onto ASCII letters. `Char.isDigit` accepts every Unicode decimal digit. Here only
  * `A` to `Z` and `a` to `z` fold, only `0` to `9` are digits, and every other character passes through unchanged, on every platform.
  */
private[kyo] object Ascii:

    def isUpper(c: Char): Boolean = c >= 'A' && c <= 'Z'

    def isLower(c: Char): Boolean = c >= 'a' && c <= 'z'

    def isAlpha(c: Char): Boolean = isUpper(c) || isLower(c)

    def isDigit(c: Char): Boolean = c >= '0' && c <= '9'

    def isAlphaNumeric(c: Char): Boolean = isAlpha(c) || isDigit(c)

    def isHexDigit(c: Char): Boolean = isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

    def toLower(c: Char): Char = if isUpper(c) then (c + 32).toChar else c

    def toUpper(c: Char): Char = if isLower(c) then (c - 32).toChar else c

    def toLower(text: String): String = if text.exists(isUpper) then text.map(toLower) else text

    def toUpper(text: String): String = if text.exists(isLower) then text.map(toUpper) else text

    def equalsIgnoreCase(a: String, b: String): Boolean =
        a.length == b.length && a.indices.forall(i => toLower(a.charAt(i)) == toLower(b.charAt(i)))

    def startsWithIgnoreCase(text: String, prefix: String): Boolean =
        text.length >= prefix.length && prefix.indices.forall(i => toLower(text.charAt(i)) == toLower(prefix.charAt(i)))

    /** The value of a non-empty string of ASCII digits, when it is one and fits in an `Int`. */
    def parseDigits(text: String): Maybe[Int] =
        if text.isEmpty || text.length > 9 || !text.forall(isDigit) then Absent
        else Present(text.foldLeft(0)((acc, c) => acc * 10 + (c - '0')))

end Ascii

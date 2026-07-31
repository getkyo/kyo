package kyo.internal

import kyo.*

/** The pure character-level logic behind `inputFilter` and `inputMask`.
  *
  * Both transports enforce the constraints client-side, and both need the same decisions: which characters a filter
  * admits, which class a mask position expects, how a raw value formats into a mask, and how a formatted value reduces
  * back to raw. That logic lives here, free of any DOM dependency, so it can be tested directly on every platform.
  *
  * The SPA transport calls these methods from `DomBackend`; the server-push transport carries a hand-written JavaScript
  * mirror in `HtmlRenderer.inputMaskJs`. The two must agree, so a change here needs the matching change there;
  * `kyo.InputMaskingJsParityTest` holds both to the case table in `InputMaskingTest`, so adding a row there covers
  * both transports.
  *
  * @see
  *   [[kyo.UI.ConstrainedInput.inputFilter]] and [[kyo.UI.ConstrainedInput.inputMask]] for the declarative API
  */
private[kyo] object InputMasking:

    /** The `chars:` prefix marking an explicit character set, so a set can never collide with a keyword. */
    private val CharsPrefix = "chars:"

    /** Encodes a filter for the `data-kyo-filter` attribute. */
    def filterWire(filter: UI.InputFilter): String =
        filter match
            case UI.InputFilter.Digits         => "digits"
            case UI.InputFilter.Decimal        => "decimal"
            case UI.InputFilter.Allowed(chars) => CharsPrefix + chars

    /** Keeps only the characters `pat` admits, dropping the rest.
      *
      * `curVal` is the field's current text, consulted only by `decimal` to decide whether a separator is still
      * available. A wire value this build does not recognize admits everything: a page cached from an older build
      * must stay usable rather than become impossible to type into.
      */
    def filterStr(pat: String, str: String, curVal: String): String =
        val isChars = pat.startsWith(CharsPrefix)
        val allowed = if isChars then pat.drop(CharsPrefix.length) else ""
        if !isChars && pat != "digits" && pat != "decimal" then str
        else
            val sb     = new StringBuilder
            var hasSep = pat == "decimal" && (curVal.contains(".") || curVal.contains(","))
            str.foreach { ch =>
                if isChars then
                    if allowed.contains(ch) then sb.append(ch)
                else if pat == "digits" then
                    if ch >= '0' && ch <= '9' then sb.append(ch)
                else if ch >= '0' && ch <= '9' then sb.append(ch)
                else if (ch == '.' || ch == ',') && !hasSep then
                    sb.append(ch); hasSep = true
            }
            sb.toString
        end if
    end filterStr

    /** One position of a parsed mask: a character class the reader fills in, or a literal the mask inserts itself. */
    enum MaskToken derives CanEqual:
        case Class(cls: Char)
        case Literal(ch: Char)
    end MaskToken

    /** Parses a mask pattern into its positions.
      *
      * `9`, `a` and `*` are the digit, letter and alphanumeric classes; every other character is a literal. A
      * backslash escapes the next character, so `"+4\\9 999"` keeps a literal `9` where the pattern would otherwise
      * open an input position. A trailing lone backslash is itself a literal.
      */
    def parseMask(mask: String): Chunk[MaskToken] =
        val b = Chunk.newBuilder[MaskToken]
        var i = 0
        while i < mask.length do
            val c = mask.charAt(i)
            if c == '\\' && i + 1 < mask.length then
                b += MaskToken.Literal(mask.charAt(i + 1))
                i += 2
            else
                if c == '9' || c == 'a' || c == '*' then b += MaskToken.Class(c)
                else b += MaskToken.Literal(c)
                i += 1
            end if
        end while
        b.result()
    end parseMask

    /** The class governing the `idx`-th input position, or [[Absent]] past the mask's capacity. */
    def maskClassAt(tokens: Chunk[MaskToken], idx: Int): Maybe[Char] =
        var c   = 0
        var i   = 0
        var res = Maybe.empty[Char]
        while i < tokens.length && res.isEmpty do
            tokens(i) match
                case MaskToken.Class(cls) =>
                    if c == idx then res = Present(cls)
                    c += 1
                case MaskToken.Literal(_) => ()
            end match
            i += 1
        end while
        res
    end maskClassAt

    /** Whether `ch` satisfies the class token `cls`. ASCII only: no locale letters, no digits beyond `0-9`. */
    def maskOk(cls: Char, ch: Char): Boolean =
        if cls == '9' then ch >= '0' && ch <= '9'
        else if cls == 'a' then (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
        else (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')

    /** Formats `raw` into the mask, inserting literals ahead of the characters that follow them.
      *
      * Formatting stops at the first position `raw` cannot fill, so a partial value renders without trailing literals.
      */
    def maskFormat(tokens: Chunk[MaskToken], raw: String): String =
        val sb   = new StringBuilder
        var ri   = 0
        var i    = 0
        var stop = false
        while i < tokens.length && !stop do
            tokens(i) match
                case MaskToken.Class(_) =>
                    if ri < raw.length then
                        sb.append(raw.charAt(ri)); ri += 1
                    else stop = true
                case MaskToken.Literal(ch) =>
                    if ri < raw.length then sb.append(ch)
                    else stop = true
            end match
            i += 1
        end while
        sb.toString
    end maskFormat

    /** Reduces a formatted value back to the raw characters the reader supplied, dropping the mask's own literals.
      *
      * A character sitting at a literal position without matching it is kept: the value is then out of step with the
      * mask, and treating it as content preserves what the reader typed rather than silently deleting it.
      */
    def maskRaw(tokens: Chunk[MaskToken], value: String): String =
        val sb = new StringBuilder
        var vi = 0
        var i  = 0
        while i < tokens.length && vi < value.length do
            tokens(i) match
                case MaskToken.Class(_) =>
                    sb.append(value.charAt(vi)); vi += 1
                case MaskToken.Literal(ch) =>
                    if value.charAt(vi) == ch then vi += 1
                    else
                        sb.append(value.charAt(vi)); vi += 1
            end match
            i += 1
        end while
        sb.toString
    end maskRaw

    /** Formats a complete value through `mask`, dropping whatever the pattern cannot hold.
      *
      * Both callers need to correct a value they did not watch being typed: the renderer formats a value the
      * application supplied, and the `compositionend` handler formats text an IME produced without per-character
      * events. Idempotent, so a value the mask already formatted comes back unchanged.
      */
    def maskNormalize(mask: String, value: String): String =
        val tokens = parseMask(mask)
        maskFormat(tokens, maskRaw(tokens, value))

end InputMasking

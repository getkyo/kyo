package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** The WHATWG ISO-2022-JP decoder: ASCII until an escape sequence selects Roman (`ESC ( J`), half-width katakana (`ESC ( I`), JIS X 0208
  * through index jis0208 (`ESC $ @` or `ESC $ B`) or ASCII again (`ESC ( B`).
  *
  * The output flag makes two escape sequences with nothing decoded between them an error. An invalid escape is an error that puts the
  * bytes after ESC back and decodes them in the last state a valid escape selected. The end of the input in a shifted state is not an
  * error; the end of the input inside a pair or an escape is one.
  */
private[kyo] object Iso2022JpDecoder extends Decoder:

    def charset: Charset = Charset.Iso2022Jp

    private enum State derives CanEqual:
        case Ascii, Roman, Katakana, LeadByte, TrailByte, EscapeStart, Escape

    private inline val Esc = 0x1b

    def run(bytes: Span[Byte], out: Decoder.Output): Unit =
        val index = IndexJis0208.table
        // The decoder's state, named as in the WHATWG algorithm; confined to this call. The end of the input is read as -1 and never
        // consumed, so a state that answers it with an error is visited again and then finishes. A restore moves `i` back over bytes
        // just read.
        var state       = State.Ascii
        var outputState = State.Ascii
        var leading     = 0
        var output      = false
        var finished    = false
        var i           = 0
        while !finished do
            val b = if i < bytes.size then bytes(i) & 0xff else -1
            if b >= 0 then i += 1
            state match
                case State.Ascii | State.Roman | State.Katakana | State.LeadByte if b == Esc => state = State.EscapeStart
                case State.Ascii | State.Roman | State.Katakana | State.LeadByte if b < 0    => finished = true
                case State.Ascii                                                             =>
                    output = false
                    if b <= 0x7f && b != 0x0e && b != 0x0f then discard(out.append(b.toChar))
                    else out.replacement(i)
                case State.Roman =>
                    output = false
                    if b == 0x5c then discard(out.append(0x00a5.toChar))
                    else if b == 0x7e then discard(out.append(0x203e.toChar))
                    else if b <= 0x7f && b != 0x0e && b != 0x0f then discard(out.append(b.toChar))
                    else out.replacement(i)
                    end if
                case State.Katakana =>
                    output = false
                    if b >= 0x21 && b <= 0x5f then discard(out.append((0xff61 - 0x21 + b).toChar))
                    else out.replacement(i)
                case State.LeadByte =>
                    output = false
                    if b >= 0x21 && b <= 0x7e then
                        leading = b
                        state = State.TrailByte
                    else out.replacement(i)
                    end if
                case State.TrailByte =>
                    if b == Esc then
                        state = State.EscapeStart
                        out.replacement(i)
                    else
                        state = State.LeadByte
                        val codePoint = if b >= 0x21 && b <= 0x7e then index((leading - 0x21) * 94 + b - 0x21) else -1
                        if codePoint >= 0 then discard(out.appendCodePoint(codePoint))
                        else out.replacement(i)
                    end if
                case State.EscapeStart =>
                    if b == 0x24 || b == 0x28 then
                        leading = b
                        state = State.Escape
                    else
                        if b >= 0 then i -= 1
                        output = false
                        state = outputState
                        out.replacement(i)
                    end if
                case State.Escape =>
                    val lead = leading
                    leading = 0
                    val selected: Maybe[State] =
                        if lead == 0x28 && b == 0x42 then Present(State.Ascii)
                        else if lead == 0x28 && b == 0x4a then Present(State.Roman)
                        else if lead == 0x28 && b == 0x49 then Present(State.Katakana)
                        else if lead == 0x24 && (b == 0x40 || b == 0x42) then Present(State.LeadByte)
                        else Absent
                    selected match
                        case Present(next) =>
                            state = next
                            outputState = next
                            if output then out.replacement(i)
                            output = true
                        case Absent =>
                            i -= (if b >= 0 then 2 else 1)
                            output = false
                            state = outputState
                            out.replacement(i)
                    end match
            end match
        end while
    end run

end Iso2022JpDecoder

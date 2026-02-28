package kyo.internal

import kyo.Chunk
import kyo.ChunkBuilder
import scala.annotation.tailrec

/** ADT for terminal input events. */
sealed abstract class InputEvent derives CanEqual

object InputEvent:
    final case class Key(
        key: String,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false
    ) extends InputEvent derives CanEqual

    final case class Mouse(
        kind: MouseKind,
        x: Int,
        y: Int,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false
    ) extends InputEvent derives CanEqual

    final case class Paste(text: String) extends InputEvent derives CanEqual

    enum MouseKind derives CanEqual:
        case LeftPress, LeftRelease, MiddlePress, MiddleRelease,
            RightPress, RightRelease, LeftDrag, MiddleDrag, RightDrag,
            Move, ScrollUp, ScrollDown
    end MouseKind
end InputEvent

/** Pure parser: raw bytes → (events, unconsumed remainder). */
private[kyo] object TuiInput:

    import InputEvent.*
    import MouseKind.*

    /** Pre-computed single-char strings for ASCII range — avoids allocation per keystroke. */
    private val asciiStrings: Array[String] =
        val arr = new Array[String](128)
        @tailrec def init(i: Int): Array[String] =
            if i >= 128 then arr
            else
                arr(i) = i.toChar.toString
                init(i + 1)
        init(0)
    end asciiStrings

    def parse(bytes: Chunk[Byte]): (Chunk[InputEvent], Chunk[Byte]) =
        val events = ChunkBuilder.init[InputEvent]
        val len    = bytes.length

        @tailrec def loop(i: Int): (Chunk[InputEvent], Chunk[Byte]) =
            if i >= len then (events.result, Chunk.empty[Byte])
            else
                val b = bytes(i) & 0xff
                if b == 0x1b then
                    if i + 1 >= len then
                        (events.result, bytes.drop(i))
                    else
                        val next = bytes(i + 1) & 0xff
                        if next == '['.toInt then
                            val result = parseCsi(bytes, i + 2, len, events)
                            if result < 0 then (events.result, bytes.drop(i))
                            else loop(result)
                        else if next == 'O'.toInt then
                            if i + 2 >= len then (events.result, bytes.drop(i))
                            else
                                val ss3 = (bytes(i + 2) & 0xff).toChar
                                ss3 match
                                    case 'P' => events.addOne(Key("F1")); loop(i + 3)
                                    case 'Q' => events.addOne(Key("F2")); loop(i + 3)
                                    case 'R' => events.addOne(Key("F3")); loop(i + 3)
                                    case 'S' => events.addOne(Key("F4")); loop(i + 3)
                                    case 'A' => events.addOne(Key("ArrowUp")); loop(i + 3)
                                    case 'B' => events.addOne(Key("ArrowDown")); loop(i + 3)
                                    case 'C' => events.addOne(Key("ArrowRight")); loop(i + 3)
                                    case 'D' => events.addOne(Key("ArrowLeft")); loop(i + 3)
                                    case 'H' => events.addOne(Key("Home")); loop(i + 3)
                                    case 'F' => events.addOne(Key("End")); loop(i + 3)
                                    case _   => events.addOne(Key("Escape")); loop(i + 1)
                                end match
                        else if next == ']'.toInt then
                            events.addOne(Key("Escape"))
                            loop(i + 1)
                        else if next >= 0x20 && next < 0x7f then
                            events.addOne(Key(asciiStrings(next), alt = true))
                            loop(i + 2)
                        else
                            events.addOne(Key("Escape"))
                            loop(i + 1)
                        end if
                    end if
                else if b < 0x20 then
                    b match
                        case 0x0d => events.addOne(Key("Enter")); loop(i + 1)
                        case 0x09 => events.addOne(Key("Tab")); loop(i + 1)
                        case 0x08 => events.addOne(Key("Backspace")); loop(i + 1)
                        case 0x00 => events.addOne(Key(" ", ctrl = true)); loop(i + 1)
                        case _ =>
                            events.addOne(Key(asciiStrings(b + 'a' - 1), ctrl = true))
                            loop(i + 1)
                else if b == 0x7f then
                    events.addOne(Key("Backspace"))
                    loop(i + 1)
                else if b < 0x80 then
                    events.addOne(Key(asciiStrings(b)))
                    loop(i + 1)
                else
                    val result = parseUtf8(bytes, i, len, events)
                    if result < 0 then (events.result, bytes.drop(i))
                    else loop(result)
                end if
        end loop

        loop(0)
    end parse

    /** Parse a CSI sequence starting after ESC[. Returns new index or -1 if incomplete. */
    private def parseCsi(
        bytes: Chunk[Byte],
        start: Int,
        len: Int,
        events: ChunkBuilder[InputEvent]
    ): Int =
        // Check for SGR mouse: ESC[<
        if start < len && (bytes(start) & 0xff) == '<'.toInt then
            parseSgrMouse(bytes, start + 1, len, events, 0, 0, 0, 0)
        // Check for bracketed paste start: ESC[200~
        else if start + 3 < len && isBracketedPasteStart(bytes, start) then
            parseBracketedPaste(bytes, start + 4, start + 4, len, events)
        else
            // Collect numeric parameters separated by ';'
            collectParams(bytes, start, len, events, 0, 0, 1)

    private def isBracketedPasteStart(bytes: Chunk[Byte], start: Int): Boolean =
        val slice = new String(
            Array(
                (bytes(start) & 0xff).toByte,
                (bytes(start + 1) & 0xff).toByte,
                (bytes(start + 2) & 0xff).toByte,
                (bytes(start + 3) & 0xff).toByte
            ),
            "US-ASCII"
        )
        slice == "200~"
    end isBracketedPasteStart

    @tailrec private def collectParams(
        bytes: Chunk[Byte],
        i: Int,
        len: Int,
        events: ChunkBuilder[InputEvent],
        param1: Int,
        param2: Int,
        params: Int
    ): Int =
        if i >= len then -1
        else
            val c = (bytes(i) & 0xff).toChar
            if c >= '0' && c <= '9' then
                val digit = c - '0'
                if params == 1 then collectParams(bytes, i + 1, len, events, param1 * 10 + digit, param2, params)
                else collectParams(bytes, i + 1, len, events, param1, param2 * 10 + digit, params)
            else if c == ';' then
                collectParams(bytes, i + 1, len, events, param1, param2, params + 1)
            else
                val ni = i + 1
                c match
                    case 'A' => events.addOne(arrowKey("ArrowUp", param2)); ni
                    case 'B' => events.addOne(arrowKey("ArrowDown", param2)); ni
                    case 'C' => events.addOne(arrowKey("ArrowRight", param2)); ni
                    case 'D' => events.addOne(arrowKey("ArrowLeft", param2)); ni
                    case 'H' => events.addOne(arrowKey("Home", param2)); ni
                    case 'F' => events.addOne(arrowKey("End", param2)); ni
                    case 'Z' => events.addOne(Key("Tab", shift = true)); ni
                    case '~' =>
                        param1 match
                            case 1  => events.addOne(arrowKey("Home", param2)); ni
                            case 2  => events.addOne(arrowKey("Insert", param2)); ni
                            case 3  => events.addOne(arrowKey("Delete", param2)); ni
                            case 4  => events.addOne(arrowKey("End", param2)); ni
                            case 5  => events.addOne(arrowKey("PageUp", param2)); ni
                            case 6  => events.addOne(arrowKey("PageDown", param2)); ni
                            case 15 => events.addOne(arrowKey("F5", param2)); ni
                            case 17 => events.addOne(arrowKey("F6", param2)); ni
                            case 18 => events.addOne(arrowKey("F7", param2)); ni
                            case 19 => events.addOne(arrowKey("F8", param2)); ni
                            case 20 => events.addOne(arrowKey("F9", param2)); ni
                            case 21 => events.addOne(arrowKey("F10", param2)); ni
                            case 23 => events.addOne(arrowKey("F11", param2)); ni
                            case 24 => events.addOne(arrowKey("F12", param2)); ni
                            case _  => events.addOne(Key("Unknown")); ni
                    case _ => events.addOne(Key("Unknown")); ni
                end match
            end if

    /** Create a key event with modifiers from xterm modifier encoding (modifier = 1 + bitmask). */
    private def arrowKey(name: String, modifier: Int): Key =
        if modifier <= 1 then Key(name)
        else
            val mBits = modifier - 1
            Key(
                name,
                shift = (mBits & 1) != 0,
                alt = (mBits & 2) != 0,
                ctrl = (mBits & 4) != 0
            )

    /** Parse SGR mouse sequence: ESC[< button;col;row M/m */
    @tailrec
    private def parseSgrMouse(
        bytes: Chunk[Byte],
        i: Int,
        len: Int,
        events: ChunkBuilder[InputEvent],
        button: Int,
        col: Int,
        row: Int,
        field: Int
    ): Int =
        if i >= len then -1
        else
            val c = (bytes(i) & 0xff).toChar
            if c >= '0' && c <= '9' then
                val digit = c - '0'
                field match
                    case 0 => parseSgrMouse(bytes, i + 1, len, events, button * 10 + digit, col, row, field)
                    case 1 => parseSgrMouse(bytes, i + 1, len, events, button, col * 10 + digit, row, field)
                    case 2 => parseSgrMouse(bytes, i + 1, len, events, button, col, row * 10 + digit, field)
                    case _ => parseSgrMouse(bytes, i + 1, len, events, button, col, row, field)
                end match
            else if c == ';' then
                parseSgrMouse(bytes, i + 1, len, events, button, col, row, field + 1)
            else if c == 'M' || c == 'm' then
                val isPress = c == 'M'
                val x       = col - 1 // 1-based to 0-based
                val y       = row - 1
                val shift   = (button & 4) != 0
                val alt     = (button & 8) != 0
                val ctrl    = (button & 16) != 0
                val base    = button & 3
                val motion  = (button & 32) != 0
                val scroll  = (button & 64) != 0

                val kind =
                    if scroll then
                        if base == 0 then ScrollUp else ScrollDown
                    else if motion then
                        base match
                            case 0 => LeftDrag
                            case 1 => MiddleDrag
                            case 2 => RightDrag
                            case _ => Move
                    else if isPress then
                        base match
                            case 0 => LeftPress
                            case 1 => MiddlePress
                            case 2 => RightPress
                            case _ => Move
                    else
                        base match
                            case 0 => LeftRelease
                            case 1 => MiddleRelease
                            case 2 => RightRelease
                            case _ => Move

                events.addOne(Mouse(kind, x, y, shift = shift, alt = alt, ctrl = ctrl))
                i + 1
            else
                -1 // malformed
            end if
    end parseSgrMouse

    /** Parse bracketed paste content: ...ESC[201~ */
    @tailrec
    private def parseBracketedPaste(
        bytes: Chunk[Byte],
        start: Int,
        i: Int,
        len: Int,
        events: ChunkBuilder[InputEvent]
    ): Int =
        if i + 5 < len &&
            (bytes(i) & 0xff) == 0x1b &&
            (bytes(i + 1) & 0xff) == '['.toInt &&
            (bytes(i + 2) & 0xff) == '2'.toInt &&
            (bytes(i + 3) & 0xff) == '0'.toInt &&
            (bytes(i + 4) & 0xff) == '1'.toInt &&
            (bytes(i + 5) & 0xff) == '~'.toInt
        then
            val textBytes = new Array[Byte](i - start)
            copyBytes(bytes, start, textBytes, 0, textBytes.length)
            events.addOne(Paste(new String(textBytes, "UTF-8")))
            i + 6
        else if i + 5 < len then
            parseBracketedPaste(bytes, start, i + 1, len, events)
        else
            -1 // incomplete (terminator not found)
    end parseBracketedPaste

    /** Copy bytes from Chunk to Array. */
    @tailrec
    private def copyBytes(src: Chunk[Byte], srcOff: Int, dst: Array[Byte], dstOff: Int, remaining: Int): Unit =
        if remaining > 0 then
            dst(dstOff) = src(srcOff)
            copyBytes(src, srcOff + 1, dst, dstOff + 1, remaining - 1)

    /** Parse UTF-8 multi-byte sequence. Returns new index or -1 if incomplete. */
    private def parseUtf8(
        bytes: Chunk[Byte],
        start: Int,
        len: Int,
        events: ChunkBuilder[InputEvent]
    ): Int =
        val b0 = bytes(start) & 0xff
        val expected =
            if (b0 & 0xe0) == 0xc0 then 2
            else if (b0 & 0xf0) == 0xe0 then 3
            else if (b0 & 0xf8) == 0xf0 then 4
            else 1 // invalid, consume 1
        if start + expected > len then -1
        else
            val arr = new Array[Byte](expected)
            copyBytes(bytes, start, arr, 0, expected)
            events.addOne(Key(new String(arr, "UTF-8")))
            start + expected
        end if
    end parseUtf8

end TuiInput

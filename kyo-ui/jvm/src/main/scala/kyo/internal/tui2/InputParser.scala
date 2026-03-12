package kyo.internal.tui2

import kyo.Chunk
import kyo.ChunkBuilder
import scala.annotation.tailrec

/** ADT for terminal input events. */
sealed abstract class InputEvent derives CanEqual

object InputEvent:
    final case class Key(
        key: kyo.UI.Keyboard,
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

    final case class ClipboardItem(mimeType: String, data: Array[Byte]) derives CanEqual

    final case class ClipboardPaste(items: kyo.Chunk[ClipboardItem]) extends InputEvent derives CanEqual

    enum MouseKind derives CanEqual:
        case LeftPress, LeftRelease, MiddlePress, MiddleRelease,
            RightPress, RightRelease, LeftDrag, MiddleDrag, RightDrag,
            Move, ScrollUp, ScrollDown
    end MouseKind
end InputEvent

/** Pure parser: raw bytes -> (events, unconsumed remainder). CPS to avoid tuple allocation. */
private[kyo] object InputParser:

    import InputEvent.*
    import MouseKind.*
    import kyo.UI.Keyboard as K

    inline def parse[A](bytes: Chunk[Byte])(inline f: (Chunk[InputEvent], Chunk[Byte]) => A): A =
        val events = ChunkBuilder.init[InputEvent]
        val len    = bytes.length

        @tailrec def loop(i: Int): Int =
            if i >= len then -1
            else
                val b = bytes(i) & 0xff
                if b == 0x1b then
                    if i + 1 >= len then
                        i
                    else
                        val next = bytes(i + 1) & 0xff
                        if next == '['.toInt then
                            val result = parseCsi(bytes, i + 2, len, events)
                            if result < 0 then i
                            else loop(result)
                        else if next == 'O'.toInt then
                            if i + 2 >= len then i
                            else
                                val ss3 = (bytes(i + 2) & 0xff).toChar
                                ss3 match
                                    case 'P' => events.addOne(Key(K.F1)); loop(i + 3)
                                    case 'Q' => events.addOne(Key(K.F2)); loop(i + 3)
                                    case 'R' => events.addOne(Key(K.F3)); loop(i + 3)
                                    case 'S' => events.addOne(Key(K.F4)); loop(i + 3)
                                    case 'A' => events.addOne(Key(K.ArrowUp)); loop(i + 3)
                                    case 'B' => events.addOne(Key(K.ArrowDown)); loop(i + 3)
                                    case 'C' => events.addOne(Key(K.ArrowRight)); loop(i + 3)
                                    case 'D' => events.addOne(Key(K.ArrowLeft)); loop(i + 3)
                                    case 'H' => events.addOne(Key(K.Home)); loop(i + 3)
                                    case 'F' => events.addOne(Key(K.End)); loop(i + 3)
                                    case _   => events.addOne(Key(K.Escape)); loop(i + 1)
                                end match
                        else if next == ']'.toInt then
                            events.addOne(Key(K.Escape))
                            loop(i + 1)
                        else if next >= 0x20 && next < 0x7f then
                            events.addOne(Key(K.Char(next.toChar), alt = true))
                            loop(i + 2)
                        else
                            events.addOne(Key(K.Escape))
                            loop(i + 1)
                        end if
                    end if
                else if b < 0x20 then
                    b match
                        case 0x0d => events.addOne(Key(K.Enter)); loop(i + 1)
                        case 0x09 => events.addOne(Key(K.Tab)); loop(i + 1)
                        case 0x08 => events.addOne(Key(K.Backspace)); loop(i + 1)
                        case 0x00 => events.addOne(Key(K.Space, ctrl = true)); loop(i + 1)
                        case _ =>
                            events.addOne(Key(K.Char((b + 'a' - 1).toChar), ctrl = true))
                            loop(i + 1)
                else if b == 0x7f then
                    events.addOne(Key(K.Backspace))
                    loop(i + 1)
                else if b < 0x80 then
                    val k = if b == 0x20 then K.Space else K.Char(b.toChar)
                    events.addOne(Key(k))
                    loop(i + 1)
                else
                    val result = parseUtf8(bytes, i, len, events)
                    if result < 0 then i
                    else loop(result)
                end if
        end loop

        val remainderStart = loop(0)
        val leftover       = if remainderStart < 0 then Chunk.empty[Byte] else bytes.drop(remainderStart)
        f(events.result, leftover)
    end parse

    private def parseCsi(
        bytes: Chunk[Byte],
        start: Int,
        len: Int,
        events: ChunkBuilder[InputEvent]
    ): Int =
        if start < len && (bytes(start) & 0xff) == '<'.toInt then
            parseSgrMouse(bytes, start + 1, len, events, 0, 0, 0, 0)
        else if start + 3 < len && isBracketedPasteStart(bytes, start) then
            parseBracketedPaste(bytes, start + 4, start + 4, len, events)
        else
            collectParams(bytes, start, len, events, 0, 0, 1)

    private def isBracketedPasteStart(bytes: Chunk[Byte], start: Int): Boolean =
        (bytes(start) & 0xff) == '2'.toInt &&
            (bytes(start + 1) & 0xff) == '0'.toInt &&
            (bytes(start + 2) & 0xff) == '0'.toInt &&
            (bytes(start + 3) & 0xff) == '~'.toInt

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
                    case 'A' => events.addOne(modKey(K.ArrowUp, param2)); ni
                    case 'B' => events.addOne(modKey(K.ArrowDown, param2)); ni
                    case 'C' => events.addOne(modKey(K.ArrowRight, param2)); ni
                    case 'D' => events.addOne(modKey(K.ArrowLeft, param2)); ni
                    case 'H' => events.addOne(modKey(K.Home, param2)); ni
                    case 'F' => events.addOne(modKey(K.End, param2)); ni
                    case 'Z' => events.addOne(Key(K.Tab, shift = true)); ni
                    case '~' =>
                        param1 match
                            case 1  => events.addOne(modKey(K.Home, param2)); ni
                            case 2  => events.addOne(modKey(K.Insert, param2)); ni
                            case 3  => events.addOne(modKey(K.Delete, param2)); ni
                            case 4  => events.addOne(modKey(K.End, param2)); ni
                            case 5  => events.addOne(modKey(K.PageUp, param2)); ni
                            case 6  => events.addOne(modKey(K.PageDown, param2)); ni
                            case 15 => events.addOne(modKey(K.F5, param2)); ni
                            case 17 => events.addOne(modKey(K.F6, param2)); ni
                            case 18 => events.addOne(modKey(K.F7, param2)); ni
                            case 19 => events.addOne(modKey(K.F8, param2)); ni
                            case 20 => events.addOne(modKey(K.F9, param2)); ni
                            case 21 => events.addOne(modKey(K.F10, param2)); ni
                            case 23 => events.addOne(modKey(K.F11, param2)); ni
                            case 24 => events.addOne(modKey(K.F12, param2)); ni
                            case _  => events.addOne(Key(K.Unknown(""))); ni
                    case _ => events.addOne(Key(K.Unknown(""))); ni
                end match
            end if

    private def modKey(k: kyo.UI.Keyboard, modifier: Int): Key =
        if modifier <= 1 then Key(k)
        else
            val mBits = modifier - 1
            Key(
                k,
                shift = (mBits & 1) != 0,
                alt = (mBits & 2) != 0,
                ctrl = (mBits & 4) != 0
            )

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
                val x       = col - 1
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
                -1
            end if
    end parseSgrMouse

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
            -1
    end parseBracketedPaste

    @tailrec
    private def copyBytes(src: Chunk[Byte], srcOff: Int, dst: Array[Byte], dstOff: Int, remaining: Int): Unit =
        if remaining > 0 then
            dst(dstOff) = src(srcOff)
            copyBytes(src, srcOff + 1, dst, dstOff + 1, remaining - 1)

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
            else 1
        if start + expected > len then -1
        else
            val arr = new Array[Byte](expected)
            copyBytes(bytes, start, arr, 0, expected)
            val s = new String(arr, "UTF-8")
            if s.length == 1 then events.addOne(Key(K.Char(s.charAt(0))))
            else events.addOne(Key(K.Unknown(s)))
            start + expected
        end if
    end parseUtf8

end InputParser

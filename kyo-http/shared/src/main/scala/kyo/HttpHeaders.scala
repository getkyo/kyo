package kyo

import kyo.*
import scala.annotation.tailrec

/** Immutable HTTP header collection with zero-copy parsing and case-insensitive name lookups.
  *
  * Two internal representations share the same public API:
  *   - **Packed** (`Array[Byte]`): an offset index followed by raw header bytes, produced by the parser. Zero `String` allocations during
  *     parsing; header values are decoded lazily only when `get` or `getAll` is called.
  *   - **Built** (`Chunk[String]`): flat interleaved `[name, value, name, value, ...]`, constructed by application code via `add` / `set`.
  *
  * All name lookups are case-insensitive per RFC 9110. Header names preserve their original case for wire serialization. `add` appends
  * without deduplication (multi-value semantics, as required for `Set-Cookie`). `set` removes all existing headers with the same name
  * before appending the new value.
  *
  * Cookie methods vary by message direction: `cookie` and `cookies` parse the request `Cookie` header; `responseCookie` and `addCookie`
  * operate on `Set-Cookie` headers for responses. The `strict` parameter enables RFC 6265 validation of cookie names and values.
  *
  * Note: Any modification method (`add`, `set`, `remove`) converts a packed representation to a `Chunk[String]`. Avoid modifying headers
  * parsed from a request if you only need to read them — use the read-only lookup methods directly on the packed form.
  *
  * @see
  *   [[kyo.HttpRequest]] Carries request headers
  * @see
  *   [[kyo.HttpResponse]] Carries response headers
  * @see
  *   [[kyo.HttpCookie]] Typed cookie values with serialization attributes
  */
opaque type HttpHeaders = Chunk[String] | Array[Byte]

object HttpHeaders:

    given CanEqual[HttpHeaders, HttpHeaders] = CanEqual.derived

    private val ColonSpace = ": ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    private val CrLf       = "\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

    val empty: HttpHeaders = Chunk.empty[String]

    /** Constructs HttpHeaders from name-value tuple pairs. */
    def init(headers: Seq[(String, String)]): HttpHeaders =
        headers.foldLeft(empty)((h, kv) => h.add(kv._1, kv._2))

    /** Constructs HttpHeaders from a flat interleaved Chunk of [name, value, name, value, ...]. */
    private[kyo] def fromChunk(chunk: Chunk[String]): HttpHeaders = chunk

    /** Constructs HttpHeaders from a packed byte array (produced by parser). */
    private[kyo] def fromPacked(packed: Array[Byte]): HttpHeaders = packed

    // --- Dispatch helpers ---

    private inline def isPacked(h: HttpHeaders): Boolean = h.isInstanceOf[Array[?]]

    private def asPacked(h: HttpHeaders): Array[Byte] = (h: @unchecked) match
        case arr: Array[Byte] @unchecked => arr

    private def asChunk(h: HttpHeaders): Chunk[String] = (h: @unchecked) match
        case c: Chunk[String] @unchecked => c

    // --- Packed array helpers ---

    private def packedHeaderCount(packed: Array[Byte]): Int =
        ((packed(0) & 0xff) << 8) | (packed(1) & 0xff)

    private def packedRawOffset(packed: Array[Byte]): Int =
        2 + packedHeaderCount(packed) * 8

    private def packedReadShort(packed: Array[Byte], pos: Int): Int =
        ((packed(pos) & 0xff) << 8) | (packed(pos + 1) & 0xff)

    private def packedHeaderNameOff(packed: Array[Byte], i: Int): Int = packedReadShort(packed, 2 + i * 8)
    private def packedHeaderNameLen(packed: Array[Byte], i: Int): Int = packedReadShort(packed, 2 + i * 8 + 2)
    private def packedHeaderValOff(packed: Array[Byte], i: Int): Int  = packedReadShort(packed, 2 + i * 8 + 4)
    private def packedHeaderValLen(packed: Array[Byte], i: Int): Int  = packedReadShort(packed, 2 + i * 8 + 6)

    /** Case-insensitive byte comparison for header name lookup. */
    private def bytesEqualIgnoreCase(packed: Array[Byte], off: Int, len: Int, name: String): Boolean =
        if len != name.length then false
        else
            @tailrec def loop(i: Int): Boolean =
                if i >= len then true
                else
                    val a = (packed(off + i) & 0xff).toChar.toLower
                    val b = name.charAt(i).toLower
                    if a != b then false
                    else loop(i + 1)
            loop(0)

    /** Decodes a slice of the packed array to a String. */
    private def decodeString(packed: Array[Byte], off: Int, len: Int): String =
        new String(packed, off, len, java.nio.charset.StandardCharsets.UTF_8)

    /** Converts any HttpHeaders to Chunk[String] representation. */
    private def toChunk(h: HttpHeaders): Chunk[String] =
        if isPacked(h) then
            val packed  = asPacked(h)
            val count   = packedHeaderCount(packed)
            val rawOff  = packedRawOffset(packed)
            val builder = ChunkBuilder.init[String]
            @tailrec def loop(i: Int): Unit =
                if i < count then
                    val nameOff = packedHeaderNameOff(packed, i) + rawOff
                    val nameLen = packedHeaderNameLen(packed, i)
                    val valOff  = packedHeaderValOff(packed, i) + rawOff
                    val valLen  = packedHeaderValLen(packed, i)
                    discard(builder += decodeString(packed, nameOff, nameLen))
                    discard(builder += decodeString(packed, valOff, valLen))
                    loop(i + 1)
            loop(0)
            builder.result()
        else
            asChunk(h)

    extension (self: HttpHeaders)

        def size: Int =
            if isPacked(self) then packedHeaderCount(asPacked(self))
            else asChunk(self).length / 2

        def isEmpty: Boolean =
            if isPacked(self) then packedHeaderCount(asPacked(self)) == 0
            else asChunk(self).length == 0

        def nonEmpty: Boolean =
            if isPacked(self) then packedHeaderCount(asPacked(self)) != 0
            else asChunk(self).length != 0

        // --- Lookup ---

        /** Returns the value of the first header matching `name` (case-insensitive). */
        def get(name: String): Maybe[String] =
            if isPacked(self) then
                val packed = asPacked(self)
                val count  = packedHeaderCount(packed)
                val rawOff = packedRawOffset(packed)
                @tailrec def loop(i: Int): Maybe[String] =
                    if i >= count then Absent
                    else
                        val nameOff = packedHeaderNameOff(packed, i) + rawOff
                        val nameLen = packedHeaderNameLen(packed, i)
                        if bytesEqualIgnoreCase(packed, nameOff, nameLen, name) then
                            val valOff = packedHeaderValOff(packed, i) + rawOff
                            val valLen = packedHeaderValLen(packed, i)
                            Present(decodeString(packed, valOff, valLen))
                        else loop(i + 1)
                        end if
                loop(0)
            else
                val chunk = asChunk(self)
                @tailrec def loop(i: Int): Maybe[String] =
                    if i >= chunk.length then Absent
                    else if chunk(i).equalsIgnoreCase(name) then Present(chunk(i + 1))
                    else loop(i + 2)
                loop(0)
        end get

        /** Returns all values for headers matching `name` (case-insensitive). */
        def getAll(name: String): Seq[String] =
            if isPacked(self) then
                val packed  = asPacked(self)
                val count   = packedHeaderCount(packed)
                val rawOff  = packedRawOffset(packed)
                val builder = Chunk.newBuilder[String]
                @tailrec def loop(i: Int): Seq[String] =
                    if i >= count then builder.result()
                    else
                        val nameOff = packedHeaderNameOff(packed, i) + rawOff
                        val nameLen = packedHeaderNameLen(packed, i)
                        if bytesEqualIgnoreCase(packed, nameOff, nameLen, name) then
                            val valOff = packedHeaderValOff(packed, i) + rawOff
                            val valLen = packedHeaderValLen(packed, i)
                            builder += decodeString(packed, valOff, valLen)
                        end if
                        loop(i + 1)
                loop(0)
            else
                val chunk   = asChunk(self)
                val builder = Chunk.newBuilder[String]
                @tailrec def loop(i: Int): Seq[String] =
                    if i >= chunk.length then builder.result()
                    else
                        if chunk(i).equalsIgnoreCase(name) then
                            builder += chunk(i + 1)
                        loop(i + 2)
                loop(0)
        end getAll

        /** Whether a header with the given name exists (case-insensitive). */
        def contains(name: String): Boolean =
            if isPacked(self) then
                val packed = asPacked(self)
                val count  = packedHeaderCount(packed)
                val rawOff = packedRawOffset(packed)
                @tailrec def loop(i: Int): Boolean =
                    if i >= count then false
                    else
                        val nameOff = packedHeaderNameOff(packed, i) + rawOff
                        val nameLen = packedHeaderNameLen(packed, i)
                        if bytesEqualIgnoreCase(packed, nameOff, nameLen, name) then true
                        else loop(i + 1)
                loop(0)
            else
                val chunk = asChunk(self)
                @tailrec def loop(i: Int): Boolean =
                    if i >= chunk.length then false
                    else if chunk(i).equalsIgnoreCase(name) then true
                    else loop(i + 2)
                loop(0)
        end contains

        // --- Modification (always produces Chunk[String]) ---

        /** Appends a header without replacing existing ones (multi-value semantics). */
        def add(name: String, value: String): HttpHeaders =
            toChunk(self).append(name).append(value)

        /** Appends a header with an integer value, avoiding Int.toString allocation. */
        def add(name: String, value: Int): HttpHeaders =
            self.add(name, HttpHeaders.intToString(value))

        /** Replaces any existing header with the same name, then appends. */
        def set(name: String, value: String): HttpHeaders =
            val chunk   = toChunk(self)
            val builder = ChunkBuilder.init[String]
            @tailrec def loop(i: Int): Unit =
                if i < chunk.length then
                    if !chunk(i).equalsIgnoreCase(name) then
                        discard(builder += chunk(i))
                        discard(builder += chunk(i + 1))
                    loop(i + 2)
            loop(0)
            discard(builder += name)
            discard(builder += value)
            builder.result()
        end set

        /** Concatenates two header collections. */
        def concat(other: HttpHeaders): HttpHeaders =
            val selfChunk  = toChunk(self)
            val otherChunk = toChunk(other)
            if selfChunk.isEmpty then otherChunk
            else if otherChunk.isEmpty then selfChunk
            else selfChunk ++ otherChunk
        end concat

        /** Removes all headers with the given name (case-insensitive). */
        def remove(name: String): HttpHeaders =
            val chunk   = toChunk(self)
            val builder = ChunkBuilder.init[String]
            @tailrec def loop(i: Int): Unit =
                if i < chunk.length then
                    if !chunk(i).equalsIgnoreCase(name) then
                        discard(builder += chunk(i))
                        discard(builder += chunk(i + 1))
                    loop(i + 2)
            loop(0)
            builder.result()
        end remove

        /** Iterates over all headers as name-value pairs. */
        def foreach(f: (String, String) => Unit): Unit =
            if isPacked(self) then
                val packed = asPacked(self)
                val count  = packedHeaderCount(packed)
                val rawOff = packedRawOffset(packed)
                @tailrec def loop(i: Int): Unit =
                    if i < count then
                        val nameOff = packedHeaderNameOff(packed, i) + rawOff
                        val nameLen = packedHeaderNameLen(packed, i)
                        val valOff  = packedHeaderValOff(packed, i) + rawOff
                        val valLen  = packedHeaderValLen(packed, i)
                        f(decodeString(packed, nameOff, nameLen), decodeString(packed, valOff, valLen))
                        loop(i + 1)
                loop(0)
            else
                val chunk = asChunk(self)
                @tailrec def loop(i: Int): Unit =
                    if i < chunk.length then
                        f(chunk(i), chunk(i + 1))
                        loop(i + 2)
                loop(0)
        end foreach

        /** Writes all headers to a GrowableByteBuffer in HTTP/1.1 wire format (name: value\r\n per header). Zero allocation for packed
          * headers. For chunk-backed headers, uses writeAscii.
          */
        def writeToBuffer(buf: kyo.internal.util.GrowableByteBuffer): Unit =
            if isPacked(self) then
                val packed = asPacked(self)
                val count  = packedHeaderCount(packed)
                val rawOff = packedRawOffset(packed)
                @tailrec def loop(i: Int): Unit =
                    if i < count then
                        val nameOff = packedHeaderNameOff(packed, i) + rawOff
                        val nameLen = packedHeaderNameLen(packed, i)
                        val valOff  = packedHeaderValOff(packed, i) + rawOff
                        val valLen  = packedHeaderValLen(packed, i)
                        buf.writeBytes(packed, nameOff, nameLen)
                        buf.writeBytes(ColonSpace, 0, ColonSpace.length)
                        buf.writeBytes(packed, valOff, valLen)
                        buf.writeBytes(CrLf, 0, CrLf.length)
                        loop(i + 1)
                loop(0)
            else
                val chunk = asChunk(self)
                @tailrec def loop(i: Int): Unit =
                    if i < chunk.length then
                        buf.writeAscii(chunk(i))
                        buf.writeBytes(ColonSpace, 0, ColonSpace.length)
                        buf.writeAscii(chunk(i + 1))
                        buf.writeBytes(CrLf, 0, CrLf.length)
                        loop(i + 2)
                loop(0)
        end writeToBuffer

        /** Folds over all headers as name-value pairs. */
        def foldLeft[A](init: A)(f: (A, String, String) => A): A =
            if isPacked(self) then
                val packed = asPacked(self)
                val count  = packedHeaderCount(packed)
                val rawOff = packedRawOffset(packed)
                @tailrec def loop(i: Int, acc: A): A =
                    if i >= count then acc
                    else
                        val nameOff = packedHeaderNameOff(packed, i) + rawOff
                        val nameLen = packedHeaderNameLen(packed, i)
                        val valOff  = packedHeaderValOff(packed, i) + rawOff
                        val valLen  = packedHeaderValLen(packed, i)
                        loop(i + 1, f(acc, decodeString(packed, nameOff, nameLen), decodeString(packed, valOff, valLen)))
                loop(0, init)
            else
                val chunk = asChunk(self)
                @tailrec def loop(i: Int, acc: A): A =
                    if i >= chunk.length then acc
                    else loop(i + 2, f(acc, chunk(i), chunk(i + 1)))
                loop(0, init)
        end foldLeft

        // --- Cookies ---

        /** Returns the value of a request cookie by name, parsed from the Cookie header (lax mode). */
        def cookie(name: String): Maybe[String] =
            self.get("Cookie") match
                case Absent     => Absent
                case Present(v) => HttpHeaders.findCookieValue(v, name, strict = false)

        /** Returns the value of a request cookie by name, parsed from the Cookie header.
          * @param strict
          *   when true, validates cookie names/values against RFC 6265 and skips non-compliant cookies
          */
        def cookie(name: String, strict: Boolean): Maybe[String] =
            self.get("Cookie") match
                case Absent     => Absent
                case Present(v) => HttpHeaders.findCookieValue(v, name, strict)

        /** Returns all request cookies as name-value pairs, parsed from the Cookie header (lax mode). */
        def cookies: Seq[(String, String)] =
            self.get("Cookie") match
                case Absent     => Seq.empty
                case Present(v) => HttpHeaders.parseCookieHeader(v, strict = false)

        /** Returns all request cookies as name-value pairs, parsed from the Cookie header.
          * @param strict
          *   when true, validates cookie names/values against RFC 6265 and skips non-compliant cookies
          */
        def cookies(strict: Boolean): Seq[(String, String)] =
            self.get("Cookie") match
                case Absent     => Seq.empty
                case Present(v) => HttpHeaders.parseCookieHeader(v, strict)

        /** Returns the value of a response cookie by name, parsed from Set-Cookie headers. */
        def responseCookie(name: String): Maybe[String] =
            val setCookies = self.getAll("Set-Cookie")
            @tailrec def loop(i: Int): Maybe[String] =
                if i >= setCookies.size then Absent
                else
                    val header = setCookies(i)
                    val eqIdx  = header.indexOf('=')
                    if eqIdx > 0 then
                        val cookieName = header.substring(0, eqIdx).trim
                        if cookieName == name then
                            val semIdx = header.indexOf(';', eqIdx + 1)
                            val end    = if semIdx < 0 then header.length else semIdx
                            Present(header.substring(eqIdx + 1, end).trim)
                        else loop(i + 1)
                        end if
                    else loop(i + 1)
                    end if
            loop(0)
        end responseCookie

        /** Adds a Set-Cookie header for a response cookie. */
        def addCookie[A](name: String, cookie: HttpCookie[A]): HttpHeaders =
            self.add("Set-Cookie", HttpHeaders.serializeCookie(name, cookie))

        /** Adds a Set-Cookie header with a simple string value. */
        def addCookie(name: String, value: String)(using HttpCodec[String]): HttpHeaders =
            addCookie(name, HttpCookie(value))

    end extension

    // --- Cookie parsing ---

    private def findCookieValue(header: String, name: String, strict: Boolean): Maybe[String] =
        @tailrec def loop(pos: Int): Maybe[String] =
            if pos >= header.length then Absent
            else
                val start = skipWhitespace(header, pos)
                val eqIdx = header.indexOf('=', start)
                if eqIdx < 0 then Absent
                else
                    val key    = header.substring(start, eqIdx).trim
                    val semIdx = header.indexOf(';', eqIdx + 1)
                    val end    = if semIdx < 0 then header.length else semIdx
                    val value  = header.substring(eqIdx + 1, end).trim
                    if key == name then
                        if strict && !isValidCookieValue(value) then Absent
                        else Present(value)
                    else loop(if semIdx < 0 then header.length else semIdx + 1)
                    end if
                end if
        loop(0)
    end findCookieValue

    private def parseCookieHeader(header: String, strict: Boolean): Seq[(String, String)] =
        val builder = Seq.newBuilder[(String, String)]
        @tailrec def loop(pos: Int): Seq[(String, String)] =
            if pos >= header.length then builder.result()
            else
                val start = skipWhitespace(header, pos)
                val eqIdx = header.indexOf('=', start)
                if eqIdx < 0 then builder.result()
                else
                    val key    = header.substring(start, eqIdx).trim
                    val semIdx = header.indexOf(';', eqIdx + 1)
                    val end    = if semIdx < 0 then header.length else semIdx
                    val value  = header.substring(eqIdx + 1, end).trim
                    if !strict || (isValidCookieName(key) && isValidCookieValue(value)) then
                        builder += ((key, value))
                    loop(if semIdx < 0 then header.length else semIdx + 1)
                end if
        loop(0)
    end parseCookieHeader

    /** RFC 6265 cookie-name: token characters (RFC 2616 Section 2.2) */
    private def isValidCookieName(name: String): Boolean =
        @tailrec def loop(i: Int): Boolean =
            if i >= name.length then true
            else
                val c = name.charAt(i)
                if c <= 0x20 || c >= 0x7f || "\"(),/:;<=>?@[\\]{}".indexOf(c) >= 0 then false
                else loop(i + 1)
        name.nonEmpty && loop(0)
    end isValidCookieName

    /** RFC 6265 cookie-value: *cookie-octet / ( DQUOTE *cookie-octet DQUOTE ) cookie-octet = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
      */
    private def isValidCookieValue(value: String): Boolean =
        val len = value.length
        // Value may optionally be wrapped in DQUOTE
        val (start, end) =
            if len >= 2 && value.charAt(0) == '"' && value.charAt(len - 1) == '"' then (1, len - 1)
            else (0, len)
        @tailrec def loop(i: Int): Boolean =
            if i >= end then true
            else
                val c = value.charAt(i)
                if c < 0x21 || c > 0x7e || c == '"' || c == ',' || c == ';' || c == '\\' then false
                else loop(i + 1)
        loop(start)
    end isValidCookieValue

    private[kyo] def serializeCookie[A](name: String, cookie: HttpCookie[A]): String =
        val sb = new StringBuilder(name.size + 80)
        discard(sb.append(name).append('=').append(cookie.codec.encode(cookie.value)))
        cookie.maxAge match
            case Present(d) => discard(sb.append("; Max-Age=").append(d.toSeconds))
            case Absent     =>
        cookie.domain match
            case Present(d) => discard(sb.append("; Domain=").append(d))
            case Absent     =>
        cookie.path match
            case Present(p) => discard(sb.append("; Path=").append(p))
            case Absent     =>
        if cookie.secure then discard(sb.append("; Secure"))
        if cookie.httpOnly then discard(sb.append("; HttpOnly"))
        cookie.sameSite match
            case Present(s) => discard(sb.append("; SameSite=").append(s))
            case Absent     =>
        sb.toString
    end serializeCookie

    @tailrec private def skipWhitespace(s: String, pos: Int): Int =
        if pos < s.length && s.charAt(pos) == ' ' then skipWhitespace(s, pos + 1)
        else pos

    /** Converts a non-negative integer to a String using direct ASCII digit extraction, avoiding boxing that occurs with Int.toString.
      * Intended for small non-negative values such as Content-Length.
      */
    private[kyo] def intToString(value: Int): String =
        if value == 0 then "0"
        else
            // Determine the number of digits
            val digits = new Array[Byte](20) // max digits for Int is 10
            var n      = value
            var pos    = 19
            while n > 0 do
                digits(pos) = ('0' + (n % 10)).toByte
                n = n / 10
                pos -= 1
            end while
            new String(digits, pos + 1, 19 - pos, java.nio.charset.StandardCharsets.US_ASCII)
        end if
    end intToString

end HttpHeaders

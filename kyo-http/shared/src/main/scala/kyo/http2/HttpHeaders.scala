package kyo.http2

import kyo.Absent
import kyo.Chunk
import kyo.ChunkBuilder
import kyo.Maybe
import kyo.Present
import kyo.discard
import scala.annotation.tailrec

/** Immutable HTTP headers backed by a flat interleaved `Chunk[String]`.
  *
  * Headers are stored as `[name0, value0, name1, value1, ...]`. All name lookups are case-insensitive per HTTP semantics. Header names
  * preserve their original case for wire serialization.
  *
  * `add` appends without deduplication (multi-value semantics). `set` replaces existing headers with the same name.
  */
opaque type HttpHeaders = Chunk[String]

object HttpHeaders:

    given CanEqual[HttpHeaders, HttpHeaders] = CanEqual.derived

    val empty: HttpHeaders = Chunk.empty[String]

    /** Constructs HttpHeaders from a flat interleaved Chunk of [name, value, name, value, ...]. */
    private[http2] def fromChunk(chunk: Chunk[String]): HttpHeaders = chunk

    extension (self: HttpHeaders)

        def size: Int = self.length / 2

        def isEmpty: Boolean = self.length == 0

        def nonEmpty: Boolean = self.length != 0

        // --- Lookup ---

        /** Returns the value of the first header matching `name` (case-insensitive). */
        def get(name: String): Maybe[String] =
            @tailrec def loop(i: Int): Maybe[String] =
                if i >= self.length then Absent
                else if self(i).equalsIgnoreCase(name) then Present(self(i + 1))
                else loop(i + 2)
            loop(0)
        end get

        /** Returns all values for headers matching `name` (case-insensitive). */
        def getAll(name: String): Seq[String] =
            val builder = Seq.newBuilder[String]
            @tailrec def loop(i: Int): Seq[String] =
                if i >= self.length then builder.result()
                else
                    if self(i).equalsIgnoreCase(name) then
                        builder += self(i + 1)
                    loop(i + 2)
            loop(0)
        end getAll

        /** Whether a header with the given name exists (case-insensitive). */
        def contains(name: String): Boolean =
            @tailrec def loop(i: Int): Boolean =
                if i >= self.length then false
                else if self(i).equalsIgnoreCase(name) then true
                else loop(i + 2)
            loop(0)
        end contains

        // --- Modification ---

        /** Appends a header without replacing existing ones (multi-value semantics). */
        def add(name: String, value: String): HttpHeaders =
            self.append(name).append(value)

        /** Replaces any existing header with the same name, then appends. */
        def set(name: String, value: String): HttpHeaders =
            val builder = ChunkBuilder.init[String]
            @tailrec def loop(i: Int): Unit =
                if i < self.length then
                    if !self(i).equalsIgnoreCase(name) then
                        discard(builder += self(i))
                        discard(builder += self(i + 1))
                    loop(i + 2)
            loop(0)
            discard(builder += name)
            discard(builder += value)
            builder.result()
        end set

        /** Concatenates two header collections. */
        def concat(other: HttpHeaders): HttpHeaders =
            if self.isEmpty then other
            else if other.isEmpty then self
            else (self: Chunk[String]) ++ (other: Chunk[String])

        /** Removes all headers with the given name (case-insensitive). */
        def remove(name: String): HttpHeaders =
            val builder = ChunkBuilder.init[String]
            @tailrec def loop(i: Int): Unit =
                if i < self.length then
                    if !self(i).equalsIgnoreCase(name) then
                        discard(builder += self(i))
                        discard(builder += self(i + 1))
                    loop(i + 2)
            loop(0)
            builder.result()
        end remove

        /** Iterates over all headers as name-value pairs. */
        def foreach(f: (String, String) => Unit): Unit =
            @tailrec def loop(i: Int): Unit =
                if i < self.length then
                    f(self(i), self(i + 1))
                    loop(i + 2)
            loop(0)
        end foreach

        /** Folds over all headers as name-value pairs. */
        def foldLeft[A](init: A)(f: (A, String, String) => A): A =
            @tailrec def loop(i: Int, acc: A): A =
                if i >= self.length then acc
                else loop(i + 2, f(acc, self(i), self(i + 1)))
            loop(0, init)
        end foldLeft

        // --- Cookies ---

        /** Returns the value of a request cookie by name, parsed from the Cookie header. */
        def cookie(name: String): Maybe[String] =
            self.get("Cookie") match
                case Absent     => Absent
                case Present(v) => HttpHeaders.findCookieValue(v, name)

        /** Returns all request cookies as name-value pairs, parsed from the Cookie header. */
        def cookies: Seq[(String, String)] =
            self.get("Cookie") match
                case Absent     => Seq.empty
                case Present(v) => HttpHeaders.parseCookieHeader(v)

        /** Adds a Set-Cookie header for a response cookie. */
        def addCookie[A](name: String, cookie: HttpCookie[A]): HttpHeaders =
            self.add("Set-Cookie", HttpHeaders.serializeCookie(name, cookie))

        /** Adds a Set-Cookie header with a simple string value. */
        def addCookie(name: String, value: String)(using HttpCodec[String]): HttpHeaders =
            addCookie(name, HttpCookie(value))

    end extension

    // --- Cookie parsing ---

    private def findCookieValue(header: String, name: String): Maybe[String] =
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
                    if key == name then Present(header.substring(eqIdx + 1, end).trim)
                    else loop(if semIdx < 0 then header.length else semIdx + 1)
                end if
        loop(0)
    end findCookieValue

    private def parseCookieHeader(header: String): Seq[(String, String)] =
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
                    builder += ((key, value))
                    loop(if semIdx < 0 then header.length else semIdx + 1)
                end if
        loop(0)
    end parseCookieHeader

    private[http2] def serializeCookie[A](name: String, cookie: HttpCookie[A]): String =
        val sb = new StringBuilder
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

end HttpHeaders

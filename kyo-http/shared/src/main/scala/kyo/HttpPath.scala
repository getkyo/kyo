package kyo

import scala.NamedTuple
import scala.NamedTuple.AnyNamedTuple
import scala.annotation.tailrec
import scala.language.implicitConversions

/** Type-safe URL path pattern with compile-time capture tracking via named tuples.
  *
  * Paths are composed from four cases: `Literal` for fixed segments, `Capture` for typed path parameters, `Concat` for joining path
  * segments, and `Rest` for trailing wildcard capture. The type parameter `A` tracks captured values as a named tuple — composing paths
  * with `/` concatenates their capture types.
  *
  * Strings implicitly convert to `Literal` paths, enabling `"/users" / Capture[Int]("id")` syntax.
  *
  * @tparam A
  *   named tuple of captured path parameter types
  * @see
  *   [[kyo.HttpRoute]]
  * @see
  *   [[kyo.HttpHandler]]
  * @see
  *   [[kyo.HttpCodec]]
  */
enum HttpPath[+A <: AnyNamedTuple]:
    case Concat[A <: AnyNamedTuple, B <: AnyNamedTuple](left: HttpPath[A], right: HttpPath[B]) extends HttpPath[Row.Concat[A, B]]
    case Capture[N <: String, A](wireName: String, fieldName: N, codec: HttpCodec[A])          extends HttpPath[Row.Init[N, A]]
    case Literal[A <: AnyNamedTuple](value: String)                                            extends HttpPath[A]
    case Rest[N <: String](fieldName: N)                                                       extends HttpPath[Row.Init[N, String]]
end HttpPath

object HttpPath:

    implicit def stringToPath(s: String): HttpPath[Row.Empty] = Literal(s)

    object Capture:

        def apply[A](using codec: HttpCodec[A])[N <: String & Singleton](fieldName: N): HttpPath[Row.Init[N, A]] =
            HttpPath.Capture(fieldName, fieldName, codec)

        def apply[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](wireName: String, fieldName: N): HttpPath[Row.Init[N, A]] =
            HttpPath.Capture(wireName, fieldName, codec)

        def apply[N <: String & Singleton, A](
            fieldName: N,
            parse: String => A,
            serialize: A => String
        ): HttpPath[Row.Init[N, A]] =
            HttpPath.Capture(fieldName, fieldName, HttpCodec(parse, serialize))

        def apply[N <: String & Singleton, A](
            wireName: String,
            fieldName: N,
            parse: String => A,
            serialize: A => String
        ): HttpPath[Row.Init[N, A]] =
            HttpPath.Capture(wireName, fieldName, HttpCodec(parse, serialize))

        def rest: HttpPath[Row.Init["rest", String]] =
            rest("rest")

        def rest[N <: String & Singleton](fieldName: N): HttpPath[Row.Init[N, String]] =
            HttpPath.Rest(fieldName)

    end Capture

    extension [A <: AnyNamedTuple](self: HttpPath[A])
        def /[B <: AnyNamedTuple](next: HttpPath[B]): HttpPath[NamedTuple.Concat[A, B]] =
            Concat(self, next)

    extension (self: String)
        def /[B <: AnyNamedTuple](next: HttpPath[B]): HttpPath[NamedTuple.Concat[Row.Empty, B]] =
            Literal[Row.Empty](self) / next

    // --- Capture type-safe helpers (Scala 3 enum cases with type params can't have bodies) ---

    extension [N <: String, A](self: Capture[N, A])
        /** Server-side: parse a URL segment into the capture's typed value. */
        private[kyo] def parseSegment(segment: String): A =
            val decoded = java.net.URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
            self.codec.parse(decoded)

        /** Client-side: serialize a typed value into a URL-encoded segment. */
        private[kyo] def serializeValue(value: A): String =
            java.net.URLEncoder.encode(self.codec.serialize(value), "UTF-8")
    end extension

    // --- Path parsing utilities (used by HttpRouter and HttpHandler) ---

    /** Parse path into non-empty segments. "/api/users/123" -> List("api", "users", "123") */
    private[kyo] def parseSegments(path: String): List[String] =
        val len = path.length
        @tailrec def loop(start: Int, acc: List[String]): List[String] =
            val segStart = skipSlashes(path, start, len)
            if segStart >= len then acc.reverse
            else
                val segEnd = findSegmentEnd(path, segStart, len)
                if segEnd > segStart then
                    loop(segEnd, path.substring(segStart, segEnd) :: acc)
                else
                    loop(segEnd, acc)
                end if
            end if
        end loop
        loop(0, Nil)
    end parseSegments

    /** Count non-empty segments without allocating. */
    private[kyo] def countSegments(path: String): Int =
        val len = path.length
        @tailrec def loop(start: Int, count: Int): Int =
            val segStart = skipSlashes(path, start, len)
            if segStart >= len then count
            else
                val segEnd = findSegmentEnd(path, segStart, len)
                if segEnd > segStart then loop(segEnd, count + 1)
                else loop(segEnd, count)
            end if
        end loop
        loop(0, 0)
    end countSegments

    @tailrec private[kyo] def skipSlashes(path: String, pos: Int, len: Int): Int =
        if pos < len && path.charAt(pos) == '/' then skipSlashes(path, pos + 1, len)
        else pos

    @tailrec private[kyo] def findSegmentEnd(path: String, pos: Int, len: Int): Int =
        if pos < len && path.charAt(pos) != '/' then findSegmentEnd(path, pos + 1, len)
        else pos

end HttpPath

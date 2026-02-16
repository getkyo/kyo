package kyo

import kyo.internal.Inputs
import scala.annotation.tailrec
import scala.language.implicitConversions

/** Type-safe URL path pattern with compile-time tracked named captures.
  *
  * HttpPath defines URL patterns that can contain literal segments and typed captures. String literals like `"/api/users"` produce
  * `HttpPath[EmptyTuple]` (no captures). Captures like `HttpPath.int("id")` produce `HttpPath[(id: Int)]` — a named tuple type. Paths
  * compose with `/`, which uses the `Inputs` type class to concatenate named tuple types.
  *
  * For example, `"/api" / HttpPath.string("name") / HttpPath.int("id")` is `HttpPath[(name: String, id: Int)]`.
  *
  * Capture names must be string literals. Non-literal strings are rejected at compile time.
  *
  *   - String literal paths via implicit conversion from `String` to `HttpPath[EmptyTuple]`
  *   - Typed captures: `int`, `long`, `string`, `uuid`, `boolean` — all produce named tuple types
  *   - `/` composition with named tuple concatenation via `Inputs`
  *   - Zero-cost opaque type over `String | Segment[?]`
  *
  * @tparam A
  *   The type of values captured from the path. `EmptyTuple` for literal-only paths, a named tuple for captures.
  *
  * @see
  *   [[kyo.HttpRoute]]
  * @see
  *   [[kyo.HttpHandler]]
  */
opaque type HttpPath[+A] = String | HttpPath.Segment[?]

object HttpPath extends HttpPathFactory:

    def apply(s: String): HttpPath[EmptyTuple] = s

    implicit def stringToPath(s: String): HttpPath[EmptyTuple] = apply(s)

    extension [A](self: HttpPath[A])
        inline def /[B](next: HttpPath[B])(using c: Inputs[A, B]): HttpPath[c.Out] =
            Inputs.combine[A, B]
            Segment.Concat[c.Out](toSegment(self), toSegment(next))
    end extension

    // --- Private ---

    private[kyo] def mkCapture[A](name: String, parse: String => A): HttpPath[Any] =
        require(name.nonEmpty, "Capture name cannot be empty")
        Segment.Capture(name, parse)

    private[kyo] enum Segment[+A]:
        case Literal(value: String)                         extends Segment[EmptyTuple]
        case Capture[A](name: String, parse: String => A)   extends Segment[A]
        case Concat[C](left: Segment[?], right: Segment[?]) extends Segment[C]
    end Segment

    private def toSegment[A](path: HttpPath[A]): Segment[A] =
        path match
            case s: String       => Segment.Literal(s).asInstanceOf[Segment[A]]
            case seg: Segment[?] => seg.asInstanceOf[Segment[A]]

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

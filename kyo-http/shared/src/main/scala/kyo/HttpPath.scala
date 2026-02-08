package kyo

import java.util.UUID
import scala.annotation.tailrec
import scala.language.implicitConversions

/** Type-safe URL path pattern with compile-time tracked captures.
  *
  * HttpPath defines URL patterns that can contain literal segments and typed captures. String literals like `"/api/users"` produce
  * `HttpPath[Unit]` (no captures). Captures like `HttpPath.int("id")` produce `HttpPath[Int]`. Paths compose with `/`, which uses the
  * `Inputs` match type to flatten `Unit` away and build tuples for multiple captures — for example,
  * `"/api" / HttpPath.string("name") / HttpPath.int("id")` is `HttpPath[(String, Int)]`.
  *
  * The `Inputs[A, B]` match type is the general flattening mechanism used throughout kyo-http. Beyond path composition, it defines how
  * low-level handlers flatten path captures with the request into a single function argument, and how route-based handlers accumulate path,
  * query, header, and body parameters into the flat `In` type that the handler function receives.
  *
  *   - String literal paths via implicit conversion from `String` to `HttpPath[Unit]`
  *   - Typed captures: `int`, `long`, `string`, `uuid`, `boolean`
  *   - `/` composition with automatic tuple flattening via `Inputs`
  *   - Zero-cost opaque type over `String | Segment[?]`
  *
  * Note: A single capture produces a bare type (`HttpPath.int("id")` is `HttpPath[Int]`), not a 1-tuple. Multiple captures produce flat
  * tuples.
  *
  * @tparam A
  *   The type of values captured from the path. `Unit` for literal-only paths, a single type for one capture, a tuple for multiple
  *   captures.
  *
  * @see
  *   [[kyo.HttpRoute]]
  * @see
  *   [[kyo.HttpHandler]]
  */
opaque type HttpPath[+A] = String | HttpPath.Segment[?]

object HttpPath:

    def apply(s: String): HttpPath[Unit] = s

    implicit def stringToPath(s: String): HttpPath[Unit] = apply(s)

    def int(name: String): HttpPath[Int] =
        require(name.nonEmpty, "Capture name cannot be empty")
        Segment.Capture(name, _.toInt)

    def long(name: String): HttpPath[Long] =
        require(name.nonEmpty, "Capture name cannot be empty")
        Segment.Capture(name, _.toLong)

    def string(name: String): HttpPath[String] =
        require(name.nonEmpty, "Capture name cannot be empty")
        Segment.Capture(name, identity)

    def uuid(name: String): HttpPath[UUID] =
        require(name.nonEmpty, "Capture name cannot be empty")
        Segment.Capture(name, UUID.fromString)

    def boolean(name: String): HttpPath[Boolean] =
        require(name.nonEmpty, "Capture name cannot be empty")
        Segment.Capture(name, _.toBoolean)

    extension [A](self: HttpPath[A])
        def /[B](next: HttpPath[B]): HttpPath[Inputs[A, B]] =
            Segment.Concat[A, B](toSegment(self), toSegment(next))
    end extension

    // --- Type-level utilities ---

    /** Unit-aware tuple flattening. Eliminates Unit on either side, concatenates tuples, wraps bare types in 1-tuples. Used throughout
      * kyo-http: path `/` composition, handler input flattening, route `In` accumulation.
      */
    type Inputs[A, B] = A match
        case Unit => B
        case _ => B match
                case Unit => A
                case _    => Tuple.Concat[IntoTuple[A], IntoTuple[B]]

    type IntoTuple[A] = A match
        case Tuple => A
        case _     => A *: EmptyTuple

    // --- Private ---

    private[kyo] enum Segment[+A]:
        case Literal(value: String)                            extends Segment[Unit]
        case Capture[A](name: String, parse: String => A)      extends Segment[A]
        case Concat[A, B](left: Segment[?], right: Segment[?]) extends Segment[Inputs[A, B]]
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

    /** Skip consecutive '/' characters. */
    @tailrec private[kyo] def skipSlashes(path: String, pos: Int, len: Int): Int =
        if pos < len && path.charAt(pos) == '/' then skipSlashes(path, pos + 1, len)
        else pos

    /** Find end of current segment (next '/' or end of string). */
    @tailrec private[kyo] def findSegmentEnd(path: String, pos: Int, len: Int): Int =
        if pos < len && path.charAt(pos) != '/' then findSegmentEnd(path, pos + 1, len)
        else pos

end HttpPath

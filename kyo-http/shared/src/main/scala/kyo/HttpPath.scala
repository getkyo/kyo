package kyo

import kyo.Record.~
import scala.language.implicitConversions

/** Path pattern DSL for defining URL structures with literal segments and typed captures.
  *
  * Paths are composed with the `/` operator: `"users" / Capture[Int]("id") / "posts"` matches `/users/42/posts` and extracts `"id" ~ Int`
  * as a typed field. Each capture adds a field to the route's `In` type parameter via `&` intersection.
  *
  * String literals implicitly convert to `Literal` segments. `Capture[A]("name")` parses the URL segment using an `HttpCodec[A]`.
  * `Capture.Rest("name")` captures the entire remaining path as a single `String`.
  *
  * WARNING: `Rest` must be the last segment in a path. Placing it before other segments throws `IllegalArgumentException` at server
  * startup.
  *
  * @tparam A
  *   the intersection of field types contributed by captures in this path
  *
  * @see
  *   [[kyo.HttpCodec]] Handles string-to-value conversion for captures
  * @see
  *   [[kyo.HttpRoute]] Uses paths to define endpoint URL patterns
  */
enum HttpPath[+A] derives CanEqual:
    case Literal(value: String)                                                       extends HttpPath[Any]
    case Capture[N <: String, A](fieldName: N, wireName: String, codec: HttpCodec[A]) extends HttpPath[N ~ A]
    case Rest[N <: String](fieldName: N)                                              extends HttpPath[N ~ String]
    case Concat[A, B](left: HttpPath[A], right: HttpPath[B])                          extends HttpPath[A & B]

    def show: String =
        this match
            case Literal(value) =>
                if value.startsWith("/") then value else "/" + value
            case Capture(fieldName, wireName, _) =>
                val n = if wireName.nonEmpty then wireName else fieldName
                s"/:$n"
            case Rest(fieldName) =>
                s"/:$fieldName*"
            case Concat(left, right) =>
                left.show + right.show

    def /[B](next: HttpPath[B]): HttpPath[A & B] =
        Concat(this, next)

end HttpPath

export HttpPath./
export HttpPath.Capture

object HttpPath:
    val empty: HttpPath[Any] = Literal("")

    object Capture:
        def apply[A](using codec: HttpCodec[A])[N <: String & Singleton](fieldName: N): HttpPath[N ~ A] =
            new HttpPath.Capture(fieldName, "", codec)

        def apply[A](using codec: HttpCodec[A])[N <: String & Singleton](fieldName: N, wireName: String): HttpPath[N ~ A] =
            new HttpPath.Capture(fieldName, wireName, codec)

        object Rest:
            def apply[N <: String & Singleton](fieldName: N): HttpPath[N ~ String] =
                new HttpPath.Rest(fieldName)
        end Rest
    end Capture

    extension (self: String)
        def /[B](next: HttpPath[B]): HttpPath[Any & B] =
            Literal(self) / next

    implicit def stringToPath(s: String): HttpPath[Any] = Literal(s)
end HttpPath

package kyo.http2

import kyo.Record.~
import scala.language.implicitConversions

enum HttpPath[+A] derives CanEqual:
    case Literal(value: String)                                                                   extends HttpPath[Any]
    case Capture[N <: String & Singleton, A](fieldName: N, wireName: String, codec: HttpCodec[A]) extends HttpPath[N ~ A]
    case Rest[N <: String & Singleton](fieldName: N)                                              extends HttpPath[N ~ String]
    case Concat[A, B](left: HttpPath[A], right: HttpPath[B])                                      extends HttpPath[A & B]
end HttpPath

object HttpPath:
    val empty: HttpPath[Any] = Literal("")

    object Capture:
        def apply[A](using codec: HttpCodec[A])[N <: String & Singleton](fieldName: N): HttpPath[N ~ A] =
            HttpPath.Capture(fieldName, fieldName, codec)

        def apply[A](using codec: HttpCodec[A])[N <: String & Singleton](fieldName: N, wireName: String): HttpPath[N ~ A] =
            HttpPath.Capture(fieldName, wireName, codec)
    end Capture

    extension [A](self: HttpPath[A])
        def /[B](next: HttpPath[B]): HttpPath[A & B] =
            Concat(self, next)

    extension (self: String)
        def /[B](next: HttpPath[B]): HttpPath[Any & B] =
            Literal(self) / next

    implicit def stringToPath(s: String): HttpPath[Any] = Literal(s)
end HttpPath

package kyo

import kyo.*
import kyo.Record2.~
import scala.language.implicitConversions

enum HttpPath[+A] derives CanEqual:
    case Literal(value: String)                                                       extends HttpPath[Any]
    case Capture[N <: String, A](fieldName: N, wireName: String, codec: HttpCodec[A]) extends HttpPath[N ~ A]
    case Rest[N <: String](fieldName: N)                                              extends HttpPath[N ~ String]
    case Concat[A, B](left: HttpPath[A], right: HttpPath[B])                          extends HttpPath[A & B]
end HttpPath

export HttpPath.*

object HttpPath:
    val empty: HttpPath[Any] = Literal("")

    object Capture:
        def apply[A](using codec: HttpCodec[A])[N <: String & Singleton](fieldName: N): HttpPath[N ~ A] =
            new HttpPath.Capture(fieldName, "", codec)

        def apply[A](using codec: HttpCodec[A])[N <: String & Singleton](fieldName: N, wireName: String): HttpPath[N ~ A] =
            new HttpPath.Capture(fieldName, wireName, codec)
    end Capture

    // TODO let's move to the Capture companion, Rest directly in the kyo package doesn't seem great. Capture.Rest reads well as well
    object Rest:
        def apply[N <: String & Singleton](fieldName: N): HttpPath[N ~ String] =
            new HttpPath.Rest(fieldName)
    end Rest

    extension [A](self: HttpPath[A])
        def /[B](next: HttpPath[B]): HttpPath[A & B] =
            Concat(self, next)

    extension (self: String)
        def /[B](next: HttpPath[B]): HttpPath[Any & B] =
            Literal(self) / next

    implicit def stringToPath(s: String): HttpPath[Any] = Literal(s)
end HttpPath

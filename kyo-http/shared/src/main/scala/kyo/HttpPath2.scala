package kyo

import scala.NamedTuple
import scala.NamedTuple.AnyNamedTuple
import scala.language.implicitConversions

// TODO my goal with the extension on a type union was not needing this conversion
enum HttpPath2[+A <: AnyNamedTuple]:
    case Concat[A <: AnyNamedTuple, B <: AnyNamedTuple](left: HttpPath2[A], right: HttpPath2[B]) extends HttpPath2[Row.Concat[A, B]]
    case Capture[N <: String, A](wireName: String, fieldName: N, codec: HttpCodec[A])            extends HttpPath2[Row.Init[N, A]]
    case Literal[A <: AnyNamedTuple](value: String)                                              extends HttpPath2[A]
    case Rest[N <: String](fieldName: N)                                                         extends HttpPath2[Row.Init[N, String]]
end HttpPath2

object HttpPath2:

    implicit def stringToPath(s: String): HttpPath2[Row.Empty] = Literal(s)

    object Capture:

        def apply[A](using codec: HttpCodec[A])[N <: String & Singleton](fieldName: N): HttpPath2[Row.Init[N, A]] =
            HttpPath2.Capture(fieldName, fieldName, codec)

        def apply[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](wireName: String, fieldName: N): HttpPath2[Row.Init[N, A]] =
            HttpPath2.Capture(wireName, fieldName, codec)

        def apply[N <: String & Singleton, A](
            fieldName: N,
            parse: String => A,
            serialize: A => String
        ): HttpPath2[Row.Init[N, A]] =
            HttpPath2.Capture(fieldName, fieldName, HttpCodec(parse, serialize))

        def apply[N <: String & Singleton, A](
            wireName: String,
            fieldName: N,
            parse: String => A,
            serialize: A => String
        ): HttpPath2[Row.Init[N, A]] =
            HttpPath2.Capture(wireName, fieldName, HttpCodec(parse, serialize))

        def rest: HttpPath2[Row.Init["rest", String]] =
            rest("rest")

        def rest[N <: String & Singleton](fieldName: N): HttpPath2[Row.Init[N, String]] =
            HttpPath2.Rest(fieldName)

    end Capture

    extension [A <: AnyNamedTuple](self: String | HttpPath2[A])

        def /[B <: AnyNamedTuple](next: String | HttpPath2[B]): HttpPath2[NamedTuple.Concat[A, B]] =
            Concat(self.toPath, next.toPath)

        private def toPath: HttpPath2[A] =
            self match
                case self: String       => Literal(self)
                case self: HttpPath2[A] => self
    end extension
end HttpPath2

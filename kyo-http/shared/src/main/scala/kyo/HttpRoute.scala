package kyo

import HttpRequest.Method
import HttpRequest.Part
import HttpResponse.Status
import java.util.UUID
import scala.language.implicitConversions

case class HttpRoute[In, Out, Err](
    method: Method,
    path: HttpRoute.Path[Any],
    outputStatus: Status,
    streamInput: Boolean,
    streamOutput: Boolean,
    tag: Maybe[String],
    summary: Maybe[String]
)

object HttpRoute:

    type Inputs[A, B] = A match
        case Unit => B
        case _ => B match
                case Unit => A
                case _    => Tuple.Concat[IntoTuple[A], IntoTuple[B]]

    type IntoTuple[A] = A match
        case Tuple => A
        case _     => A *: EmptyTuple

    opaque type Path[+A] = String | Path.Segment[A]

    object Path:

        def apply(s: String): Path[Unit] = s

        implicit def stringToPath(s: String): Path[Unit] = apply(s)

        private[kyo] enum Segment[+A]:
            case Literal(value: String)                            extends Segment[Unit]
            case Capture[A](name: String, parse: String => A)      extends Segment[A]
            case Concat[A, B](left: Segment[A], right: Segment[B]) extends Segment[Inputs[A, B]]
        end Segment

        def int(name: String): Path[Int]         = ???
        def long(name: String): Path[Long]       = ???
        def string(name: String): Path[String]   = ???
        def uuid(name: String): Path[UUID]       = ???
        def boolean(name: String): Path[Boolean] = ???

        extension [A](self: Path[A])
            def /[B](next: Path[B]): Path[Inputs[A, B]] = ???
        end extension
    end Path

    def get[A](path: Path[A]): HttpRoute[A, Unit, Nothing]     = ???
    def post[A](path: Path[A]): HttpRoute[A, Unit, Nothing]    = ???
    def put[A](path: Path[A]): HttpRoute[A, Unit, Nothing]     = ???
    def patch[A](path: Path[A]): HttpRoute[A, Unit, Nothing]   = ???
    def delete[A](path: Path[A]): HttpRoute[A, Unit, Nothing]  = ???
    def head[A](path: Path[A]): HttpRoute[A, Unit, Nothing]    = ???
    def options[A](path: Path[A]): HttpRoute[A, Unit, Nothing] = ???

    extension [In, Out, Err](route: HttpRoute[In, Out, Err])

        // --- Client ---

        def call(in: In)(using Frame): Out < (Async & Abort[Err]) = ???

        // --- Query Parameters ---

        def query[A: Schema](name: String): HttpRoute[Inputs[In, A], Out, Err]             = ???
        def query[A: Schema](name: String, default: A): HttpRoute[Inputs[In, A], Out, Err] = ???

        // --- Headers ---

        def header(name: String): HttpRoute[Inputs[In, String], Out, Err]                  = ???
        def header(name: String, default: String): HttpRoute[Inputs[In, String], Out, Err] = ???

        // --- Cookies ---

        def cookie(name: String): HttpRoute[Inputs[In, String], Out, Err]                  = ???
        def cookie(name: String, default: String): HttpRoute[Inputs[In, String], Out, Err] = ???

        // --- Auth ---

        def authBearer: HttpRoute[Inputs[In, String], Out, Err]               = ???
        def authBasic: HttpRoute[Inputs[In, (String, String)], Out, Err]      = ???
        def authApiKey(name: String): HttpRoute[Inputs[In, String], Out, Err] = ???

        // --- Request Body ---

        def input[A: Schema]: HttpRoute[Inputs[In, A], Out, Err]                      = ???
        def inputText: HttpRoute[Inputs[In, String], Out, Err]                        = ???
        def inputForm[A: Schema]: HttpRoute[Inputs[In, A], Out, Err]                  = ???
        def inputMultipart: HttpRoute[Inputs[In, Seq[Part]], Out, Err]                = ???
        def inputBytes: HttpRoute[Inputs[In, Stream[Byte, Async]], Out, Err]          = ???
        def inputStream[A: Schema]: HttpRoute[Inputs[In, Stream[A, Async]], Out, Err] = ???

        // --- Response Body ---

        def output[O: Schema]: HttpRoute[In, O, Err]                      = ???
        def output[O: Schema](status: Status): HttpRoute[In, O, Err]      = ???
        def outputText: HttpRoute[In, String, Err]                        = ???
        def outputBytes: HttpRoute[In, Stream[Byte, Async], Err]          = ???
        def outputStream[O: Schema]: HttpRoute[In, Stream[O, Async], Err] = ???

        // --- Errors ---

        def error[E: Schema](status: Status): HttpRoute[In, Out, Err | E] = ???

        // --- Documentation ---

        def tag(t: String): HttpRoute[In, Out, Err]                          = ???
        def summary(s: String): HttpRoute[In, Out, Err]                      = ???
        def description(d: String): HttpRoute[In, Out, Err]                  = ???
        def operationId(id: String): HttpRoute[In, Out, Err]                 = ???
        def deprecated: HttpRoute[In, Out, Err]                              = ???
        def externalDocs(url: String): HttpRoute[In, Out, Err]               = ???
        def externalDocs(url: String, desc: String): HttpRoute[In, Out, Err] = ???
        def security(scheme: String): HttpRoute[In, Out, Err]                = ???

        // --- Server ---

        def handle[S](f: In => Out < (Abort[Err] & Async & S))(using Frame): HttpHandler[S] =
            HttpHandler.init(route)(f)

    end extension

end HttpRoute

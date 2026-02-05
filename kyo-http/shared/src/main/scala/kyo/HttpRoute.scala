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
    summary: Maybe[String],
    queryParams: Seq[HttpRoute.QueryParam[?]] = Seq.empty,
    headerParams: Seq[HttpRoute.HeaderParam] = Seq.empty,
    cookieParams: Seq[HttpRoute.CookieParam] = Seq.empty,
    inputSchema: Maybe[Schema[?]] = Absent,
    outputSchema: Maybe[Schema[?]] = Absent,
    errorSchemas: Seq[(Status, Schema[?])] = Seq.empty,
    description: Maybe[String] = Absent,
    operationId: Maybe[String] = Absent,
    isDeprecated: Boolean = false,
    externalDocsUrl: Maybe[String] = Absent,
    externalDocsDesc: Maybe[String] = Absent,
    securityScheme: Maybe[String] = Absent
):
    import HttpRoute.*

    // --- Query Parameters ---

    def query[A: Schema](name: String): HttpRoute[Inputs[In, A], Out, Err] =
        require(name.nonEmpty, "Query parameter name cannot be empty")
        copy(queryParams = queryParams :+ QueryParam(name, Schema[A], Absent))
            .asInstanceOf[HttpRoute[Inputs[In, A], Out, Err]]
    end query

    def query[A: Schema](name: String, default: A): HttpRoute[Inputs[In, A], Out, Err] =
        require(name.nonEmpty, "Query parameter name cannot be empty")
        copy(queryParams = queryParams :+ QueryParam(name, Schema[A], Present(default)))
            .asInstanceOf[HttpRoute[Inputs[In, A], Out, Err]]
    end query

    // --- Headers ---

    def header(name: String): HttpRoute[Inputs[In, String], Out, Err] =
        require(name.nonEmpty, "Header name cannot be empty")
        copy(headerParams = headerParams :+ HeaderParam(name, Absent))
            .asInstanceOf[HttpRoute[Inputs[In, String], Out, Err]]
    end header

    def header(name: String, default: String): HttpRoute[Inputs[In, String], Out, Err] =
        require(name.nonEmpty, "Header name cannot be empty")
        copy(headerParams = headerParams :+ HeaderParam(name, Present(default)))
            .asInstanceOf[HttpRoute[Inputs[In, String], Out, Err]]
    end header

    // --- Cookies ---

    def cookie(name: String): HttpRoute[Inputs[In, String], Out, Err] =
        require(name.nonEmpty, "Cookie name cannot be empty")
        copy(cookieParams = cookieParams :+ CookieParam(name, Absent))
            .asInstanceOf[HttpRoute[Inputs[In, String], Out, Err]]
    end cookie

    def cookie(name: String, default: String): HttpRoute[Inputs[In, String], Out, Err] =
        require(name.nonEmpty, "Cookie name cannot be empty")
        copy(cookieParams = cookieParams :+ CookieParam(name, Present(default)))
            .asInstanceOf[HttpRoute[Inputs[In, String], Out, Err]]
    end cookie

    // --- Auth ---

    def authBearer: HttpRoute[Inputs[In, String], Out, Err] =
        header("Authorization").asInstanceOf[HttpRoute[Inputs[In, String], Out, Err]]

    def authBasic: HttpRoute[Inputs[In, (String, String)], Out, Err] =
        header("Authorization").asInstanceOf[HttpRoute[Inputs[In, (String, String)], Out, Err]]

    def authApiKey(name: String): HttpRoute[Inputs[In, String], Out, Err] =
        require(name.nonEmpty, "API key header name cannot be empty")
        header(name).asInstanceOf[HttpRoute[Inputs[In, String], Out, Err]]

    // --- Request Body ---

    def input[A: Schema]: HttpRoute[Inputs[In, A], Out, Err] =
        copy(inputSchema = Present(Schema[A]))
            .asInstanceOf[HttpRoute[Inputs[In, A], Out, Err]]

    def inputText: HttpRoute[Inputs[In, String], Out, Err] =
        input[String]

    def inputForm[A: Schema]: HttpRoute[Inputs[In, A], Out, Err] =
        input[A]

    def inputMultipart: HttpRoute[Inputs[In, Seq[Part]], Out, Err] =
        this.asInstanceOf[HttpRoute[Inputs[In, Seq[Part]], Out, Err]]

    def inputBytes: HttpRoute[Inputs[In, Stream[Byte, Async]], Out, Err] =
        copy(streamInput = true).asInstanceOf[HttpRoute[Inputs[In, Stream[Byte, Async]], Out, Err]]

    def inputStream[A: Schema]: HttpRoute[Inputs[In, Stream[A, Async]], Out, Err] =
        copy(streamInput = true, inputSchema = Present(Schema[A]))
            .asInstanceOf[HttpRoute[Inputs[In, Stream[A, Async]], Out, Err]]

    // --- Response Body ---

    def output[O: Schema]: HttpRoute[In, O, Err] =
        copy(outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def output[O: Schema](status: Status): HttpRoute[In, O, Err] =
        copy(outputStatus = status, outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def outputText: HttpRoute[In, String, Err] =
        output[String]

    def outputBytes: HttpRoute[In, Stream[Byte, Async], Err] =
        copy(streamOutput = true).asInstanceOf[HttpRoute[In, Stream[Byte, Async], Err]]

    def outputStream[O: Schema]: HttpRoute[In, Stream[O, Async], Err] =
        copy(streamOutput = true, outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, Stream[O, Async], Err]]

    // --- Errors ---

    def error[E: Schema](status: Status): HttpRoute[In, Out, Err | E] =
        copy(errorSchemas = errorSchemas :+ (status -> Schema[E]))
            .asInstanceOf[HttpRoute[In, Out, Err | E]]

    // --- Documentation ---

    def tag(t: String): HttpRoute[In, Out, Err] =
        copy(tag = Present(t))

    def summary(s: String): HttpRoute[In, Out, Err] =
        copy(summary = Present(s))

    def description(d: String): HttpRoute[In, Out, Err] =
        copy(description = Present(d))

    def operationId(id: String): HttpRoute[In, Out, Err] =
        copy(operationId = Present(id))

    def deprecated: HttpRoute[In, Out, Err] =
        copy(isDeprecated = true)

    def externalDocs(url: String): HttpRoute[In, Out, Err] =
        copy(externalDocsUrl = Present(url))

    def externalDocs(url: String, desc: String): HttpRoute[In, Out, Err] =
        copy(externalDocsUrl = Present(url), externalDocsDesc = Present(desc))

    def security(scheme: String): HttpRoute[In, Out, Err] =
        copy(securityScheme = Present(scheme))

    // --- Server ---

    def handle[S](f: In => Out < (Abort[Err] & Async & S))(using Frame): HttpHandler[S] =
        HttpHandler.init(this)(f)

    // --- Client ---

    def call(in: In)(using Frame): Out < (Async & Abort[HttpError] & Abort[Err]) =
        val pathStr     = buildPath(path, in)
        val queryString = buildQueryString(queryParams, in)
        val fullPath    = if queryString.isEmpty then pathStr else s"$pathStr?$queryString"
        val headers     = buildHeaders(headerParams, cookieParams, in)

        val request = inputSchema match
            case Present(schema) =>
                val bodyValue = extractBody(in)
                val json      = schema.asInstanceOf[Schema[Any]].encode(bodyValue)
                HttpRequest.initBytes(method, fullPath, json.getBytes("UTF-8"), headers, "application/json")
            case Absent =>
                HttpRequest.initBytes(method, fullPath, Array.empty[Byte], headers, "")

        HttpClient.send(request).map { response =>
            if response.status.isError then
                response.bodyText.map { body =>
                    val errOpt = errorSchemas.collectFirst {
                        case (status, schema) if response.status == status =>
                            try Some(schema.asInstanceOf[Schema[Any]].decode(body))
                            catch case _: Exception => None
                    }.flatten
                    errOpt match
                        case Some(err) => Abort.fail(err.asInstanceOf[Err])
                        case None      => Abort.fail(HttpError.InvalidResponse(s"HTTP error: ${response.status}"))
                }
            else
                outputSchema match
                    case Present(schema) => response.bodyText.map(body => schema.asInstanceOf[Schema[Out]].decode(body))
                    case Absent          => ().asInstanceOf[Out]
        }
    end call

    // --- Private helpers ---

    private def buildPath(path: Path[Any], in: In): String =
        path match
            case s: String                => s
            case segment: Path.Segment[?] => buildUrlFromSegment(segment, in, 0)._1

    private def buildUrlFromSegment(segment: Path.Segment[?], in: Any, idx: Int): (String, Int) =
        segment match
            case Path.Segment.Literal(value) =>
                (value, idx)
            case Path.Segment.Capture(name, _) =>
                val value = extractInputAt(in, idx)
                (s"/$value", idx + 1)
            case Path.Segment.Concat(left, right) =>
                val (leftStr, nextIdx)  = buildUrlFromSegment(left.asInstanceOf[Path.Segment[?]], in, idx)
                val (rightStr, lastIdx) = buildUrlFromSegment(right.asInstanceOf[Path.Segment[?]], in, nextIdx)
                (leftStr + rightStr, lastIdx)

    private def extractInputAt(in: Any, idx: Int): Any =
        in match
            case tuple: Tuple => tuple.productElement(idx)
            case other => if idx == 0 then other else throw new IllegalStateException(s"Cannot extract input at index $idx from $other")

    private def buildHeaders(
        headerParams: Seq[HeaderParam],
        cookieParams: Seq[CookieParam],
        in: In
    ): Seq[(String, String)] =
        if headerParams.isEmpty then Seq.empty
        else
            val pathCaptureCount = countPathCaptures(path)
            val queryParamCount  = queryParams.size
            val offset           = pathCaptureCount + queryParamCount
            headerParams.zipWithIndex.map { case (param, i) =>
                val value = extractInputAt(in, offset + i)
                (param.name, value.toString)
            }

    private def buildQueryString(queryParams: Seq[QueryParam[?]], in: In): String =
        if queryParams.isEmpty then ""
        else
            val pathCaptureCount = countPathCaptures(path)
            val pairs = queryParams.zipWithIndex.map { case (param, i) =>
                val value = extractInputAt(in, pathCaptureCount + i)
                s"${param.name}=${java.net.URLEncoder.encode(value.toString, "UTF-8")}"
            }
            pairs.mkString("&")

    private def countPathCaptures(path: Path[Any]): Int =
        path match
            case _: String                => 0
            case segment: Path.Segment[?] => countSegmentCaptures(segment)

    private def countSegmentCaptures(segment: Path.Segment[?]): Int =
        segment match
            case Path.Segment.Literal(_)    => 0
            case Path.Segment.Capture(_, _) => 1
            case Path.Segment.Concat(left, right) =>
                countSegmentCaptures(left.asInstanceOf[Path.Segment[?]]) +
                    countSegmentCaptures(right.asInstanceOf[Path.Segment[?]])

    private def extractBody(in: In): Any = in

end HttpRoute

object HttpRoute:

    // --- Internal param types ---

    case class QueryParam[A](name: String, schema: Schema[A], default: Maybe[A])
    case class HeaderParam(name: String, default: Maybe[String])
    case class CookieParam(name: String, default: Maybe[String])

    // --- Path ---

    // TODO let's move this to kyo.HttpPath and incorporate the code that is in PathUtil in HttpPath
    opaque type Path[+A] = String | Path.Segment[?]

    object Path:

        def apply(s: String): Path[Unit] = s

        implicit def stringToPath(s: String): Path[Unit] = apply(s)

        private[kyo] enum Segment[+A]:
            case Literal(value: String)                            extends Segment[Unit]
            case Capture[A](name: String, parse: String => A)      extends Segment[A]
            case Concat[A, B](left: Segment[?], right: Segment[?]) extends Segment[Inputs[A, B]]
        end Segment

        def int(name: String): Path[Int] =
            require(name.nonEmpty, "Capture name cannot be empty")
            Segment.Capture(name, _.toInt)

        def long(name: String): Path[Long] =
            require(name.nonEmpty, "Capture name cannot be empty")
            Segment.Capture(name, _.toLong)

        def string(name: String): Path[String] =
            require(name.nonEmpty, "Capture name cannot be empty")
            Segment.Capture(name, identity)

        def uuid(name: String): Path[UUID] =
            require(name.nonEmpty, "Capture name cannot be empty")
            Segment.Capture(name, UUID.fromString)

        def boolean(name: String): Path[Boolean] =
            require(name.nonEmpty, "Capture name cannot be empty")
            Segment.Capture(name, _.toBoolean)

        private def toSegment[A](path: Path[A]): Segment[A] =
            path match
                case s: String       => Segment.Literal(s).asInstanceOf[Segment[A]]
                case seg: Segment[?] => seg.asInstanceOf[Segment[A]]

        extension [A](self: Path[A])
            def /[B](next: Path[B]): Path[Inputs[A, B]] =
                Segment.Concat[A, B](toSegment(self), toSegment(next))
        end extension

    end Path

    // --- Route factory methods ---

    private def init[A](method: Method, path: Path[A]): HttpRoute[A, Unit, Nothing] =
        HttpRoute(
            method = method,
            path = path.asInstanceOf[Path[Any]],
            outputStatus = Status.OK,
            streamInput = false,
            streamOutput = false,
            tag = Absent,
            summary = Absent
        )

    def get[A](path: Path[A]): HttpRoute[A, Unit, Nothing]     = init(Method.GET, path)
    def post[A](path: Path[A]): HttpRoute[A, Unit, Nothing]    = init(Method.POST, path)
    def put[A](path: Path[A]): HttpRoute[A, Unit, Nothing]     = init(Method.PUT, path)
    def patch[A](path: Path[A]): HttpRoute[A, Unit, Nothing]   = init(Method.PATCH, path)
    def delete[A](path: Path[A]): HttpRoute[A, Unit, Nothing]  = init(Method.DELETE, path)
    def head[A](path: Path[A]): HttpRoute[A, Unit, Nothing]    = init(Method.HEAD, path)
    def options[A](path: Path[A]): HttpRoute[A, Unit, Nothing] = init(Method.OPTIONS, path)

    // --- Type-level utilities (internal) ---

    type Inputs[A, B] = A match
        case Unit => B
        case _ => B match
                case Unit => A
                case _    => Tuple.Concat[IntoTuple[A], IntoTuple[B]]

    type IntoTuple[A] = A match
        case Tuple => A
        case _     => A *: EmptyTuple

end HttpRoute

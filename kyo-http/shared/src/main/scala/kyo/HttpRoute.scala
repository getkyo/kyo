package kyo

import HttpPath.Inputs
import HttpRequest.Method
import HttpRequest.Part
import HttpResponse.Status

case class HttpRoute[In, Out, Err](
    method: Method,
    path: HttpPath[Any],
    outputStatus: Status,
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

    // --- Response Body ---

    def output[O: Schema]: HttpRoute[In, O, Err] =
        copy(outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def output[O: Schema](status: Status): HttpRoute[In, O, Err] =
        copy(outputStatus = status, outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def outputText: HttpRoute[In, String, Err] =
        output[String]

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
                val body = response.bodyText
                val errOpt = errorSchemas.collectFirst {
                    case (status, schema) if response.status == status =>
                        try Some(schema.asInstanceOf[Schema[Any]].decode(body))
                        catch case _: Exception => None
                }.flatten
                errOpt match
                    case Some(err) => Abort.fail(err.asInstanceOf[Err])
                    case None      => Abort.fail(HttpError.InvalidResponse(s"HTTP error: ${response.status}"))
            else
                outputSchema match
                    case Present(schema) => schema.asInstanceOf[Schema[Out]].decode(response.bodyText)
                    case Absent          => ().asInstanceOf[Out]
        }
    end call

    // --- Private helpers ---

    private def buildPath(path: HttpPath[Any], in: In): String =
        path match
            case s: String                    => s
            case segment: HttpPath.Segment[?] => buildUrlFromSegment(segment, in, 0)._1

    private def buildUrlFromSegment(segment: HttpPath.Segment[?], in: Any, idx: Int): (String, Int) =
        segment match
            case HttpPath.Segment.Literal(value) =>
                (value, idx)
            case HttpPath.Segment.Capture(name, _) =>
                val value = extractInputAt(in, idx)
                (s"/$value", idx + 1)
            case HttpPath.Segment.Concat(left, right) =>
                val (leftStr, nextIdx)  = buildUrlFromSegment(left.asInstanceOf[HttpPath.Segment[?]], in, idx)
                val (rightStr, lastIdx) = buildUrlFromSegment(right.asInstanceOf[HttpPath.Segment[?]], in, nextIdx)
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

    private def countPathCaptures(path: HttpPath[Any]): Int =
        path match
            case _: String                    => 0
            case segment: HttpPath.Segment[?] => countSegmentCaptures(segment)

    private def countSegmentCaptures(segment: HttpPath.Segment[?]): Int =
        segment match
            case HttpPath.Segment.Literal(_)    => 0
            case HttpPath.Segment.Capture(_, _) => 1
            case HttpPath.Segment.Concat(left, right) =>
                countSegmentCaptures(left.asInstanceOf[HttpPath.Segment[?]]) +
                    countSegmentCaptures(right.asInstanceOf[HttpPath.Segment[?]])

    private def extractBody(in: In): Any = in

end HttpRoute

object HttpRoute:

    // --- Internal param types ---

    case class QueryParam[A](name: String, schema: Schema[A], default: Maybe[A])
    case class HeaderParam(name: String, default: Maybe[String])
    case class CookieParam(name: String, default: Maybe[String])

    // --- Route factory methods ---

    private def init[A](method: Method, path: HttpPath[A]): HttpRoute[A, Unit, Nothing] =
        HttpRoute(
            method = method,
            path = path.asInstanceOf[HttpPath[Any]],
            outputStatus = Status.OK,
            tag = Absent,
            summary = Absent
        )

    def get[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]     = init(Method.GET, path)
    def post[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]    = init(Method.POST, path)
    def put[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]     = init(Method.PUT, path)
    def patch[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]   = init(Method.PATCH, path)
    def delete[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]  = init(Method.DELETE, path)
    def head[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing]    = init(Method.HEAD, path)
    def options[A](path: HttpPath[A]): HttpRoute[A, Unit, Nothing] = init(Method.OPTIONS, path)

end HttpRoute

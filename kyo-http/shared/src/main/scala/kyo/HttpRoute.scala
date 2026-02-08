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
    errorSchemas: Seq[(Status, Schema[?], ConcreteTag[Any])] = Seq.empty,
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

    def error[E: Schema](status: Status)(using tag: ConcreteTag[E]): HttpRoute[In, Out, Err | E] =
        copy(errorSchemas = errorSchemas :+ (status, Schema[E], tag.asInstanceOf[ConcreteTag[Any]]))
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

end HttpRoute

object HttpRoute:

    // --- Internal param types ---

    case class QueryParam[A](name: String, schema: Schema[A], default: Maybe[A])
    case class HeaderParam(name: String, default: Maybe[String]) derives CanEqual
    case class CookieParam(name: String, default: Maybe[String]) derives CanEqual

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

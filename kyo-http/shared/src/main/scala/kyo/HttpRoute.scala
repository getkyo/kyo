package kyo

import HttpPath.Inputs
import HttpRequest.Method
import HttpRequest.Part
import HttpResponse.Status

/** Typed endpoint contract that serves as the single source of truth for both server-side handlers and client-side typed calls.
  *
  * Define a route once and use it on both sides: `.handle(f)` creates a server-side `HttpHandler`, and `HttpClient.call(route, in)` makes a
  * typed client call. The route captures the full contract — HTTP method, URL path with typed captures, query/header parameters, request
  * body schema, response body schema, and typed error responses with status codes — so server and client stay in sync by construction.
  *
  * The `In` type parameter accumulates as parameters are added — each `.query`, `.header`, and `.input` call appends to the input type via
  * the `Inputs` match type, which flattens `Unit` and concatenates tuples. A single parameter is a bare type; multiple parameters build a
  * flat tuple. The request body (via `.input[A]`) becomes a flat parameter alongside path/query/header params — not accessed through the
  * request object.
  *
  * Routes carry OpenAPI documentation metadata (tag, summary, description, operationId, deprecated, security) which feeds into automatic
  * spec generation via `HttpOpenApi`.
  *
  *   - Typed path captures via `HttpPath` (int, long, string, uuid, boolean)
  *   - Query parameters with optional defaults and `Schema`-based deserialization
  *   - Header parameter extraction
  *   - Request/response body schemas for automatic JSON serialization
  *   - Typed error responses mapped to HTTP status codes
  *   - OpenAPI metadata (tag, summary, description, operationId, deprecated, externalDocs, security)
  *   - `.handle(f)` to convert into an `HttpHandler`
  *   - Client-side typed invocation via `HttpClient.call`
  *
  * IMPORTANT: Parameter order in the `In` type follows declaration order: path captures, then `.query`, then `.header`, then `.input`. The
  * handler function must accept parameters in this order.
  *
  * IMPORTANT: Error types passed to `.error[E](status)` require `ConcreteTag[E]` — must be concrete types, not type parameters or unions.
  *
  * @tparam In
  *   Accumulated input type from path captures, query, header, and body parameters
  * @tparam Out
  *   Response body type
  * @tparam Err
  *   Union of possible typed error types
  *
  * @see
  *   [[kyo.HttpPath]]
  * @see
  *   [[kyo.HttpHandler]]
  * @see
  *   [[kyo.HttpClient.call]]
  * @see
  *   [[kyo.HttpOpenApi]]
  * @see
  *   [[kyo.Schema]]
  */
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

    /** Appends a required query parameter to `In` via `Inputs[In, A]`. Deserialized from the query string via `Schema[A]`. */
    def query[A: Schema](name: String): HttpRoute[Inputs[In, A], Out, Err] =
        require(name.nonEmpty, "Query parameter name cannot be empty")
        copy(queryParams = queryParams :+ QueryParam(name, Schema[A], Absent))
            .asInstanceOf[HttpRoute[Inputs[In, A], Out, Err]]
    end query

    /** Appends an optional query parameter with a default value. */
    def query[A: Schema](name: String, default: A): HttpRoute[Inputs[In, A], Out, Err] =
        require(name.nonEmpty, "Query parameter name cannot be empty")
        copy(queryParams = queryParams :+ QueryParam(name, Schema[A], Present(default)))
            .asInstanceOf[HttpRoute[Inputs[In, A], Out, Err]]
    end query

    // --- Headers ---

    /** Appends a required header parameter (always `String`) to `In` via `Inputs[In, String]`. */
    def header(name: String): HttpRoute[Inputs[In, String], Out, Err] =
        require(name.nonEmpty, "Header name cannot be empty")
        copy(headerParams = headerParams :+ HeaderParam(name, Absent))
            .asInstanceOf[HttpRoute[Inputs[In, String], Out, Err]]
    end header

    /** Appends an optional header parameter with a default value. */
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

    /** Sets the request body schema. The deserialized body becomes a flat parameter in the handler alongside path/query/header params. */
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

    /** Sets the response body schema for automatic JSON serialization. */
    def output[O: Schema]: HttpRoute[In, O, Err] =
        copy(outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def output[O: Schema](status: Status): HttpRoute[In, O, Err] =
        copy(outputStatus = status, outputSchema = Present(Schema[O]))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def outputText: HttpRoute[In, String, Err] =
        output[String]

    // --- Errors ---

    /** Registers a typed error response for the given status code. Requires `ConcreteTag[E]` — must be a concrete type. */
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

    /** Converts this route into an HttpHandler. `f` receives the accumulated flat `In` tuple — path captures, then query, then header, then
      * body params — and returns the output type. Errors matching route error schemas get the corresponding status code.
      */
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

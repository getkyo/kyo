package kyo

import HttpRequest.Method
import HttpRequest.Part
import HttpResponse.Status
import kyo.internal.Inputs

/** Typed endpoint contract that serves as the single source of truth for both server-side handlers and client-side typed calls.
  *
  * Define a route once and use it on both sides: `.handle(f)` creates a server-side `HttpHandler`, and `HttpClient.call(route, in)` makes a
  * typed client call. The route captures the full contract — HTTP method, URL path with typed captures, query/header parameters, request
  * body schema, response body schema, and typed error responses with status codes — so server and client stay in sync by construction.
  *
  * The `In` type parameter accumulates as parameters are added — each `.query`, `.header`, and `.requestBody` call appends to the input
  * type via the `Inputs` type class, which concatenates named tuple types. Named tuple fields are accessed by name (e.g., `in.id`,
  * `in.limit`, `in.body`). The field name matches the parameter name string literal.
  *
  * For example, `HttpRoute.get("users" / HttpPath.int("id")).query[Int]("limit")` produces
  * `HttpRoute[(id: Int, limit: Int), Unit, Nothing]`.
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
  * IMPORTANT: Parameter order in the `In` type follows declaration order. Path captures come first, then each `.query`, `.header`,
  * `.authBearer`/`.authBasic`/`.authApiKey`, `.cookie`, and `.requestBody`/`.requestBodyMultipart` call appends fields in the order they
  * are called. Both the handler and `HttpClient.call` respect this declaration order.
  *
  * IMPORTANT: Error types passed to `.error[E](status)` require `ConcreteTag[E]` — must be concrete types, not type parameters or unions.
  *
  * @tparam In
  *   Accumulated input type from path captures, query, header, and body parameters (named tuple or EmptyTuple)
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
    bodyEncoding: Maybe[HttpRoute.BodyEncoding] = Absent,
    responseEncoding: Maybe[HttpRoute.ResponseEncoding] = Absent,
    errorSchemas: Seq[(Status, Schema[?], ConcreteTag[Any])] = Seq.empty,
    description: Maybe[String] = Absent,
    operationId: Maybe[String] = Absent,
    isDeprecated: Boolean = false,
    externalDocsUrl: Maybe[String] = Absent,
    externalDocsDesc: Maybe[String] = Absent,
    securityScheme: Maybe[String] = Absent,
    private[kyo] paramCounter: Int = 0,
    private[kyo] bodyId: Int = -1
):
    import HttpRoute.*

    /** Private helper that centralizes validation + cast for all builder methods. */
    private inline def withField[N <: String & Singleton, A](using
        c: Inputs.Field[In, N, A]
    )(
        f: HttpRoute[In, Out, Err] => HttpRoute[In, Out, Err]
    ): HttpRoute[c.Out, Out, Err] =
        Inputs.addField[In, N, A]
        f(this).asInstanceOf[HttpRoute[c.Out, Out, Err]]
    end withField

    // --- Query Parameters ---

    /** Appends a required query parameter. The field name in the named tuple matches the parameter name. */
    inline def query[A: Schema](using
        DummyImplicit
    )[N <: String & Singleton](name: N)(using c: Inputs.Field[In, N, A]): HttpRoute[c.Out, Out, Err] =
        withField[N, A]: r =>
            r.copy(queryParams = r.queryParams :+ QueryParam(name, Schema[A], Absent, r.paramCounter), paramCounter = r.paramCounter + 1)

    /** Appends an optional query parameter with a default value. */
    inline def query[A: Schema](using
        DummyImplicit
    )[N <: String & Singleton](name: N, default: A)(using c: Inputs.Field[In, N, A]): HttpRoute[c.Out, Out, Err] =
        withField[N, A]: r =>
            r.copy(
                queryParams = r.queryParams :+ QueryParam(name, Schema[A], Present(default), r.paramCounter),
                paramCounter = r.paramCounter + 1
            )

    // --- Headers ---

    /** Appends a required header parameter (always `String`). */
    inline def header[N <: String & Singleton](name: N)(using c: Inputs.Field[In, N, String]): HttpRoute[c.Out, Out, Err] =
        withField[N, String]: r =>
            r.copy(headerParams = r.headerParams :+ HeaderParam(name, Absent, id = r.paramCounter), paramCounter = r.paramCounter + 1)

    /** Appends an optional header parameter with a default value. */
    inline def header[N <: String & Singleton](name: N, default: String)(using c: Inputs.Field[In, N, String]): HttpRoute[c.Out, Out, Err] =
        withField[N, String]: r =>
            r.copy(
                headerParams = r.headerParams :+ HeaderParam(name, Present(default), id = r.paramCounter),
                paramCounter = r.paramCounter + 1
            )

    // --- Cookies ---

    inline def cookie[N <: String & Singleton](name: N)(using c: Inputs.Field[In, N, String]): HttpRoute[c.Out, Out, Err] =
        withField[N, String]: r =>
            r.copy(cookieParams = r.cookieParams :+ CookieParam(name, Absent, r.paramCounter), paramCounter = r.paramCounter + 1)

    inline def cookie[N <: String & Singleton](name: N, default: String)(using c: Inputs.Field[In, N, String]): HttpRoute[c.Out, Out, Err] =
        withField[N, String]: r =>
            r.copy(cookieParams = r.cookieParams :+ CookieParam(name, Present(default), r.paramCounter), paramCounter = r.paramCounter + 1)

    // --- Auth ---

    inline def authBearer(using c: Inputs.Field[In, "bearer", String]): HttpRoute[c.Out, Out, Err] =
        withField["bearer", String]: r =>
            r.copy(
                headerParams = r.headerParams :+ HeaderParam("Authorization", Absent, Present(AuthScheme.Bearer), r.paramCounter),
                paramCounter = r.paramCounter + 1
            )

    inline def authBasic(using
        c1: Inputs.Field[In, "username", String],
        c2: Inputs.Field[c1.Out, "password", String]
    ): HttpRoute[c2.Out, Out, Err] =
        Inputs.addField[In, "username", String]
        Inputs.addField[c1.Out, "password", String]
        copy(
            headerParams = headerParams :+ HeaderParam("Authorization", Absent, Present(AuthScheme.Basic), paramCounter),
            paramCounter = paramCounter + 2
        ).asInstanceOf[HttpRoute[c2.Out, Out, Err]]
    end authBasic

    inline def authApiKey[N <: String & Singleton](name: N)(using c: Inputs.Field[In, N, String]): HttpRoute[c.Out, Out, Err] =
        withField[N, String]: r =>
            r.copy(
                headerParams = r.headerParams :+ HeaderParam(name, Absent, Present(AuthScheme.ApiKey), r.paramCounter),
                paramCounter = r.paramCounter + 1
            )

    // --- Request Body ---

    /** Sets the request body schema. The deserialized body becomes a named field `body` in the handler input. */
    inline def requestBody[A: Schema](using c: Inputs.Field[In, "body", A]): HttpRoute[c.Out, Out, Err] =
        withField["body", A]: r =>
            r.copy(
                bodyEncoding = Present(BodyEncoding.Json(Schema[A].asInstanceOf[Schema[Any]])),
                bodyId = r.paramCounter,
                paramCounter = r.paramCounter + 1
            )

    inline def requestBodyText(using c: Inputs.Field[In, "body", String]): HttpRoute[c.Out, Out, Err] =
        withField["body", String]: r =>
            r.copy(bodyEncoding = Present(BodyEncoding.Text), bodyId = r.paramCounter, paramCounter = r.paramCounter + 1)

    inline def requestBodyForm[A: Schema](using c: Inputs.Field[In, "body", A]): HttpRoute[c.Out, Out, Err] =
        withField["body", A]: r =>
            r.copy(
                bodyEncoding = Present(BodyEncoding.Form(Schema[A].asInstanceOf[Schema[Any]])),
                bodyId = r.paramCounter,
                paramCounter = r.paramCounter + 1
            )

    inline def requestBodyMultipart(using c: Inputs.Field[In, "parts", Seq[Part]]): HttpRoute[c.Out, Out, Err] =
        withField["parts", Seq[Part]]: r =>
            r.copy(bodyEncoding = Present(BodyEncoding.Multipart), bodyId = r.paramCounter, paramCounter = r.paramCounter + 1)

    inline def requestBodyStream(using c: Inputs.Field[In, "body", Stream[Span[Byte], Async]]): HttpRoute[c.Out, Out, Err] =
        withField["body", Stream[Span[Byte], Async]]: r =>
            r.copy(bodyEncoding = Present(BodyEncoding.Streaming), bodyId = r.paramCounter, paramCounter = r.paramCounter + 1)

    inline def requestBodyStreamMultipart(using c: Inputs.Field[In, "parts", Stream[Part, Async]]): HttpRoute[c.Out, Out, Err] =
        withField["parts", Stream[Part, Async]]: r =>
            r.copy(bodyEncoding = Present(BodyEncoding.StreamingMultipart), bodyId = r.paramCounter, paramCounter = r.paramCounter + 1)

    // --- Response Body ---

    /** Sets the response body schema for automatic JSON serialization. */
    def responseBody[O: Schema]: HttpRoute[In, O, Err] =
        copy(responseEncoding = Present(ResponseEncoding.Json(Schema[O].asInstanceOf[Schema[Any]])))
            .asInstanceOf[HttpRoute[In, O, Err]]

    def responseBodyText: HttpRoute[In, String, Err] =
        responseBody[String]

    /** Sets the response to SSE streaming. Handler returns `Stream[HttpEvent[V], Async]`. */
    def responseBodySse[V](using
        schema: Schema[V],
        emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]
    ): HttpRoute[In, Stream[HttpEvent[V], Async], Err] =
        copy(responseEncoding = Present(ResponseEncoding.Sse(schema.asInstanceOf[Schema[Any]], emitTag.erased)))
            .asInstanceOf[HttpRoute[In, Stream[HttpEvent[V], Async], Err]]

    /** Sets the response to NDJSON streaming. Handler returns `Stream[V, Async]`. */
    def responseBodyNdjson[V](using schema: Schema[V], emitTag: Tag[Emit[Chunk[V]]]): HttpRoute[In, Stream[V, Async], Err] =
        copy(responseEncoding = Present(ResponseEncoding.Ndjson(schema.asInstanceOf[Schema[Any]], emitTag.erased)))
            .asInstanceOf[HttpRoute[In, Stream[V, Async], Err]]

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

    /** Converts this route into an HttpHandler. `f` receives the accumulated `In` named tuple — path captures, then query, then header,
      * then body params — and returns the output type. Errors matching route error schemas get the corresponding status code.
      */
    def handle[S](using
        c: Inputs[In, (request: HttpRequest[?])],
        frame: Frame
    )(
        f: c.Out => Out < (Abort[Err] & Async & S)
    ): HttpHandler[S] =
        HttpHandler.init(this)(using c, frame)(f)

    /** No-input route handler — the function receives no parameters. Only available when `In = EmptyTuple`. */
    def handle[S](using
        ev: In =:= EmptyTuple,
        frame: Frame
    )(
        f: => Out < (Abort[Err] & Async & S)
    ): HttpHandler[S] =
        HttpHandler.init(this.asInstanceOf[HttpRoute[EmptyTuple, Out, Err]])((_: (request: HttpRequest[?])) => f)

end HttpRoute

object HttpRoute:

    // --- Internal param types ---

    private[kyo] case class QueryParam[A](name: String, schema: Schema[A], default: Maybe[A], id: Int = 0)

    private[kyo] enum BodyEncoding derives CanEqual:
        case Json(schema: Schema[Any])
        case Text
        case Form(schema: Schema[Any])
        case Multipart
        case Streaming
        case StreamingMultipart

        def contentType: Maybe[String] = this match
            case _: Json            => Present("application/json")
            case Text               => Present("text/plain")
            case _: Form            => Present("application/x-www-form-urlencoded")
            case Multipart          => Present("multipart/form-data")
            case Streaming          => Absent
            case StreamingMultipart => Present("multipart/form-data")

        def decode(text: String): Any = this match
            case Json(s) => s.decode(text)
            case Form(s) => s.decode(text)
            case _       => text

        def encode(value: Any): String = this match
            case Json(s) => s.encode(value)
            case Form(s) => s.encode(value)
            case _       => value.toString
    end BodyEncoding

    private[kyo] enum ResponseEncoding:
        case Json(schema: Schema[Any])
        case Sse(schema: Schema[Any], emitTag: Tag[Any])
        case Ndjson(schema: Schema[Any], emitTag: Tag[Any])

        def contentType: String = this match
            case _: Json   => "application/json"
            case _: Sse    => "text/event-stream"
            case _: Ndjson => "application/x-ndjson"
    end ResponseEncoding

    private[kyo] object ResponseEncoding:
        def extractSchema(enc: ResponseEncoding): Schema[Any] = enc match
            case ResponseEncoding.Json(s)      => s
            case ResponseEncoding.Sse(s, _)    => s
            case ResponseEncoding.Ndjson(s, _) => s
    end ResponseEncoding

    private[kyo] enum AuthScheme derives CanEqual:
        case Bearer
        case Basic
        case ApiKey
    end AuthScheme

    private[kyo] case class HeaderParam(name: String, default: Maybe[String], authScheme: Maybe[AuthScheme] = Absent, id: Int = 0)
        derives CanEqual
    private[kyo] case class CookieParam(name: String, default: Maybe[String], id: Int = 0) derives CanEqual

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

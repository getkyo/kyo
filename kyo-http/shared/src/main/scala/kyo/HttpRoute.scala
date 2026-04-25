package kyo

import kyo.*

/** A compile-time contract describing an HTTP endpoint's inputs, outputs, and error mappings.
  *
  * HttpRoute is the central abstraction in kyo-http. It defines *what* an endpoint looks like — the HTTP method, path pattern, which fields
  * to extract from requests, how to serialize responses, and which domain errors map to which status codes — without specifying *how* to
  * handle the request. This separation lets the same route definition drive both client and server: the client uses it to serialize
  * requests and deserialize responses, while the server uses it to parse incoming requests and build typed responses.
  *
  * The three type parameters track endpoint structure at compile time:
  *   - `In` accumulates request fields. Each call to `.request(_.query[Int]("page"))` refines `In` via `&` intersection, so
  *     `HttpRoute[Any, ...]` becomes `HttpRoute["page" ~ Int, ...]`. Path captures contribute fields too.
  *   - `Out` accumulates response fields the same way, typically including `"body" ~ A` for the response body.
  *   - `E` tracks error types registered with `.error[E](status)`, enabling `Abort.fail(e)` in handlers.
  *
  * Routes are built incrementally with a fluent API. Start with a factory like `HttpRoute.getRaw(path)`, then chain `.request(...)`,
  * `.response(...)`, `.filter(...)`, `.error[E](status)`, and `.metadata(...)`. Each step returns a new route with refined type parameters.
  * Convert to a handler with `.handler(req => ...)` for server use, or pass to `HttpClient.sendWith` for typed client calls.
  *
  * @tparam In
  *   The intersection of all request field types (path captures, query params, headers, cookies, body)
  * @tparam Out
  *   The intersection of all response field types (body, headers, cookies)
  * @tparam E
  *   The union of all error types that handlers can abort with
  *
  * @see
  *   [[kyo.HttpHandler]] Pairs a route with an implementation function
  * @see
  *   [[kyo.HttpClient]] Sends typed requests using route definitions
  * @see
  *   [[kyo.HttpFilter]] Composable middleware that participates in field tracking
  * @see
  *   [[kyo.HttpPath]] Path pattern DSL for literal segments and typed captures
  * @see
  *   [[kyo.Record]] The typed record that holds field values at runtime
  */
case class HttpRoute[In, Out, +E](
    method: HttpMethod,
    request: HttpRoute.RequestDef[In],
    response: HttpRoute.ResponseDef[Out] = HttpRoute.ResponseDef(),
    // Filter types are existential because the filter's In/Out don't match the route's In/Out
    // until composition time — the filter wraps the handler, not the route definition itself.
    filter: HttpFilter[?, ?, ?, ?, ? <: E] = HttpFilter.noop,
    metadata: HttpRoute.Metadata = HttpRoute.Metadata()
) derives CanEqual:
    import HttpRoute.*

    def pathAppend[In2](suffix: HttpPath[In2]): HttpRoute[In & In2, Out, E] =
        copy(request = request.pathAppend(suffix))

    def pathPrepend[In2](prefix: HttpPath[In2]): HttpRoute[In & In2, Out, E] =
        copy(request = request.pathPrepend(prefix))

    /** Adds fields to the request definition. The function receives the current RequestDef and should chain field declarations like
      * `.query[Int]("page")`, `.headerOpt[String]("auth")`, or `.bodyJson[A]`. Each declaration refines the `In` type parameter.
      */
    def request[R](f: RequestDef[In] => R)(using s: Fields.Exact[RequestDef, R])(using s.Out <:< In): HttpRoute[s.Out, Out, E] =
        HttpRoute(method, s(f(request)), response, filter, metadata)

    /** Adds fields to the response definition. The function receives the current ResponseDef and should chain field declarations like
      * `.bodyJson[A]`, `.header[String]("etag")`, or `.cookie[String]("session")`. Each declaration refines the `Out` type parameter.
      */
    def response[R](f: ResponseDef[Out] => R)(using s: Fields.Exact[ResponseDef, R])(using s.Out <:< Out): HttpRoute[In, s.Out, E] =
        HttpRoute(method, request, s(f(response)), filter, metadata)

    /** Applies a filter to this route. The filter's required fields (`ReqUse`) must be a subset of the route's current `In`, and any fields
      * the filter adds (`ReqAdd`) extend `In`. Filters compose via `andThen` — applying multiple filters accumulates their requirements and
      * additions.
      */
    def filter[ReqUse >: In, ReqAdd, ResUse >: Out, ResAdd, E2](
        f: HttpFilter[ReqUse, ReqAdd, ResUse, ResAdd, E2]
    ): HttpRoute[In & ReqAdd, Out & ResAdd, E | E2] =
        HttpRoute(method, request, response, this.filter.andThen(f), metadata)
    end filter

    /** Maps a domain error type to an HTTP status code. When a handler aborts with `Abort.fail(e: E2)`, the framework serializes the error
      * as JSON and responds with the declared status. Multiple error types can be registered by chaining `.error` calls.
      */
    inline def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(s: HttpStatus): HttpRoute[In, Out, E | E2] =
        copy(response = response.error[E2](s))

    def metadata(f: Metadata => Metadata): HttpRoute[In, Out, E] =
        copy(metadata = f(metadata))

    def metadata(meta: Metadata): HttpRoute[In, Out, E] =
        copy(metadata = meta)

    /** Creates an HttpHandler by pairing this route with an implementation function. The handler receives an `HttpRequest[In]` where all
      * declared fields are type-safely accessible via `req.fields`. Use `HttpResponse.halt(response)` to short-circuit, or `Abort.fail(e)`
      * for mapped domain errors.
      */
    def handler[E2 >: E](f: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]))(using
        Frame
    ): HttpHandler[In, Out, E2] =
        HttpHandler.init(this)(req => f(req))

end HttpRoute

object HttpRoute:

    private def make[A](method: HttpMethod, path: HttpPath[A]): HttpRoute[A, Any, Nothing] =
        HttpRoute[A, Any, Nothing](method, RequestDef[A](path), ResponseDef[Any](), HttpFilter.noop, Metadata())

    // ==================== Raw methods ====================

    def getRaw[A](path: HttpPath[A]): HttpRoute[A, Any, Nothing]     = make(HttpMethod.GET, path)
    def postRaw[A](path: HttpPath[A]): HttpRoute[A, Any, Nothing]    = make(HttpMethod.POST, path)
    def putRaw[A](path: HttpPath[A]): HttpRoute[A, Any, Nothing]     = make(HttpMethod.PUT, path)
    def patchRaw[A](path: HttpPath[A]): HttpRoute[A, Any, Nothing]   = make(HttpMethod.PATCH, path)
    def deleteRaw[A](path: HttpPath[A]): HttpRoute[A, Any, Nothing]  = make(HttpMethod.DELETE, path)
    def headRaw[A](path: HttpPath[A]): HttpRoute[A, Any, Nothing]    = make(HttpMethod.HEAD, path)
    def optionsRaw[A](path: HttpPath[A]): HttpRoute[A, Any, Nothing] = make(HttpMethod.OPTIONS, path)

    // ==================== JSON methods ====================

    def getJson[A: Schema](path: HttpPath[Any]): HttpRoute[Any, "body" ~ A, Nothing] =
        getRaw(path).response(_.bodyJson[A])

    def postJson[A: Schema, B: Schema](path: HttpPath[Any]): HttpRoute["body" ~ B, "body" ~ A, Nothing] =
        postRaw(path).request(_.bodyJson[B]).response(_.bodyJson[A])

    def putJson[A: Schema, B: Schema](path: HttpPath[Any]): HttpRoute["body" ~ B, "body" ~ A, Nothing] =
        putRaw(path).request(_.bodyJson[B]).response(_.bodyJson[A])

    def patchJson[A: Schema, B: Schema](path: HttpPath[Any]): HttpRoute["body" ~ B, "body" ~ A, Nothing] =
        patchRaw(path).request(_.bodyJson[B]).response(_.bodyJson[A])

    def deleteJson[A: Schema](path: HttpPath[Any]): HttpRoute[Any, "body" ~ A, Nothing] =
        deleteRaw(path).response(_.bodyJson[A])

    // ==================== Text methods ====================

    def getText(path: HttpPath[Any]): HttpRoute[Any, "body" ~ String, Nothing] =
        getRaw(path).response(_.bodyText)

    def postText(path: HttpPath[Any]): HttpRoute["body" ~ String, "body" ~ String, Nothing] =
        postRaw(path).request(_.bodyText).response(_.bodyText)

    def putText(path: HttpPath[Any]): HttpRoute["body" ~ String, "body" ~ String, Nothing] =
        putRaw(path).request(_.bodyText).response(_.bodyText)

    def patchText(path: HttpPath[Any]): HttpRoute["body" ~ String, "body" ~ String, Nothing] =
        patchRaw(path).request(_.bodyText).response(_.bodyText)

    def deleteText(path: HttpPath[Any]): HttpRoute[Any, "body" ~ String, Nothing] =
        deleteRaw(path).response(_.bodyText)

    // ==================== Binary methods ====================

    def getBinary(path: HttpPath[Any]): HttpRoute[Any, "body" ~ Span[Byte], Nothing] =
        getRaw(path).response(_.bodyBinary)

    def postBinary(path: HttpPath[Any]): HttpRoute["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        postRaw(path).request(_.bodyBinary).response(_.bodyBinary)

    def putBinary(path: HttpPath[Any]): HttpRoute["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        putRaw(path).request(_.bodyBinary).response(_.bodyBinary)

    def patchBinary(path: HttpPath[Any]): HttpRoute["body" ~ Span[Byte], "body" ~ Span[Byte], Nothing] =
        patchRaw(path).request(_.bodyBinary).response(_.bodyBinary)

    def deleteBinary(path: HttpPath[Any]): HttpRoute[Any, "body" ~ Span[Byte], Nothing] =
        deleteRaw(path).response(_.bodyBinary)

    // ==================== ContentType ====================

    /** The content type and serialization strategy for a route's body field.
      *
      * Determines how request/response bodies are read from and written to the wire. Non-streaming types (`Text`, `Binary`, `Json`, `Form`)
      * buffer the entire body. Streaming types (`ByteStream`, `Ndjson`, `Sse`, `SseText`, `MultipartStream`) produce or consume a `Stream`
      * that keeps the connection open until the stream completes.
      */
    enum ContentType[A]:
        case Text                                                            extends ContentType[String]
        case Binary                                                          extends ContentType[Span[Byte]]
        case ByteStream                                                      extends ContentType[Stream[Span[Byte], Async]]
        case Multipart                                                       extends ContentType[Seq[HttpRequest.Part]]
        case MultipartStream                                                 extends ContentType[Stream[HttpRequest.Part, Async]]
        case Json[A](schema: kyo.Schema[A], jsonSchema: kyo.Json.JsonSchema) extends ContentType[A]
        case Ndjson[V](schema: kyo.Schema[V], jsonSchema: kyo.Json.JsonSchema, emitTag: Tag[Emit[Chunk[V]]])
            extends ContentType[Stream[V, Async]]
        case Sse[V](schema: kyo.Schema[V], jsonSchema: kyo.Json.JsonSchema, emitTag: Tag[Emit[Chunk[HttpSseEvent[V]]]])
            extends ContentType[Stream[HttpSseEvent[V], Async]]
        case SseText(emitTag: Tag[Emit[Chunk[HttpSseEvent[String]]]]) extends ContentType[Stream[HttpSseEvent[String], Async]]
        case Form[A](codec: HttpFormCodec[A])                         extends ContentType[A]
    end ContentType

    object ContentType:
        given CanEqual[ContentType[?], ContentType[?]] = CanEqual.derived

    // ==================== Field ====================

    sealed abstract class Field[-A] derives CanEqual

    object Field:

        case class Param[N <: String, A, F](
            kind: Param.Location,
            fieldName: N,
            wireName: String,
            codec: HttpCodec[A],
            default: Maybe[A],
            optional: Boolean,
            description: String
        ) extends Field[N ~ F]

        object Param:
            enum Location derives CanEqual:
                case Query
                case Header
                case Cookie
            end Location
        end Param

        case class Body[N <: String, A](
            fieldName: N,
            contentType: ContentType[A],
            description: String
        ) extends Field[N ~ A]

    end Field

    // ==================== RequestDef ====================

    /** Fluent builder for request field declarations.
      *
      * Each method call (`.query`, `.header`, `.cookie`, `.bodyJson`, etc.) appends a field descriptor and refines the `In` type parameter
      * via `&` intersection. Required variants fail with `HttpMissingFieldException` when the value is absent. Optional variants
      * (`queryOpt`, `headerOpt`, `cookieOpt`) wrap the value in `Maybe[A]` instead.
      *
      * The `wireName` parameter allows the HTTP wire name to differ from the field name used in code. For example,
      * `.header[String]("auth", wireName = "Authorization")` extracts the `Authorization` header but exposes it as `req.fields.auth`.
      */
    case class RequestDef[-In](
        path: HttpPath[? >: In],
        fields: Chunk[Field[? >: In]] = Chunk.empty
    ) derives CanEqual:

        def pathAppend[In2](suffix: HttpPath[In2]): RequestDef[In & In2] =
            RequestDef(this.path / suffix, this.fields)

        def pathPrepend[In2](prefix: HttpPath[In2]): RequestDef[In & In2] =
            RequestDef(prefix / this.path, this.fields)

        def query[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Location.Query, fieldName, wireName, codec, default, false, description))

        def queryOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Location.Query, fieldName, wireName, codec, default, true, description))

        def header[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, default, false, description))

        def headerOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, default, true, description))

        def cookie[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, default, false, description))

        def cookieOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, default, true, description))

        inline def bodyJson[A](using schema: Schema[A]): RequestDef[In & "body" ~ A] =
            add(Field.Body("body", ContentType.Json(schema, kyo.Json.jsonSchema[A]), ""))

        inline def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using Fields.Pin[N]): RequestDef[In & N ~ A] =
            add(Field.Body(fieldName, ContentType.Json(schema, kyo.Json.jsonSchema[A]), description))

        def bodyText: RequestDef[In & "body" ~ String] =
            add(Field.Body("body", ContentType.Text, ""))

        def bodyText[N <: String & Singleton](fieldName: N, description: String = "")(using Fields.Pin[N]): RequestDef[In & N ~ String] =
            add(Field.Body(fieldName, ContentType.Text, description))

        def bodyBinary: RequestDef[In & "body" ~ Span[Byte]] =
            add(Field.Body("body", ContentType.Binary, ""))

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): RequestDef[In & N ~ Span[Byte]] =
            add(Field.Body(fieldName, ContentType.Binary, description))

        def bodyStream: RequestDef[In & "body" ~ Stream[Span[Byte], Async]] =
            add(Field.Body("body", ContentType.ByteStream, ""))

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ Stream[Span[Byte], Async]] =
            add(Field.Body(fieldName, ContentType.ByteStream, description))

        inline def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        ): RequestDef[In & "body" ~ Stream[V, Async]] =
            add(Field.Body("body", ContentType.Ndjson(schema, kyo.Json.jsonSchema[V], emitTag), ""))

        inline def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using Fields.Pin[N]): RequestDef[In & N ~ Stream[V, Async]] =
            add(Field.Body(fieldName, ContentType.Ndjson(schema, kyo.Json.jsonSchema[V], emitTag), description))

        def bodyForm[A](using codec: HttpFormCodec[A]): RequestDef[In & "body" ~ A] =
            add(Field.Body("body", ContentType.Form(codec), ""))

        def bodyForm[A](using
            codec: HttpFormCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ A] =
            add(Field.Body(fieldName, ContentType.Form(codec), description))

        def bodyMultipart: RequestDef[In & "body" ~ Seq[HttpRequest.Part]] =
            add(Field.Body("body", ContentType.Multipart, ""))

        def bodyMultipart[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): RequestDef[In & N ~ Seq[HttpRequest.Part]] =
            add(Field.Body(fieldName, ContentType.Multipart, description))

        def bodyMultipartStream: RequestDef[In & "body" ~ Stream[HttpRequest.Part, Async]] =
            add(Field.Body("body", ContentType.MultipartStream, ""))

        def bodyMultipartStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ Stream[HttpRequest.Part, Async]] =
            add(Field.Body(fieldName, ContentType.MultipartStream, description))

        private def add[F](field: Field[F]): RequestDef[In & F] =
            RequestDef(this.path, this.fields.append(field))

    end RequestDef

    // ==================== ResponseDef ====================

    /** Fluent builder for response field declarations.
      *
      * Mirrors `RequestDef` for the response side. Body declarations (`.bodyJson`, `.bodyText`, `.bodyBinary`, etc.) determine how the
      * response is serialized on the wire. Header and cookie declarations are extracted from or written to response headers. Use
      * `.status(HttpStatus.Created)` to override the response status.
      */
    case class ResponseDef[-Out](
        status: HttpStatus = HttpStatus.Success.OK,
        fields: Chunk[Field[? >: Out]] = Chunk.empty,
        errors: Chunk[ErrorMapping] = Chunk.empty
    ) derives CanEqual:

        def header[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ A] =
            addField(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, Absent, false, description))

        def headerOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ Maybe[A]] =
            addField(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, Absent, true, description))

        def cookie[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ HttpCookie[A]] =
            addField(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, Absent, false, description))

        def cookieOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ Maybe[HttpCookie[A]]] =
            addField(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, Absent, true, description))

        inline def bodyJson[A](using schema: Schema[A]): ResponseDef[Out & "body" ~ A] =
            addField(Field.Body("body", ContentType.Json(schema, kyo.Json.jsonSchema[A]), ""))

        inline def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using Fields.Pin[N]): ResponseDef[Out & N ~ A] =
            addField(Field.Body(fieldName, ContentType.Json(schema, kyo.Json.jsonSchema[A]), description))

        def bodyText: ResponseDef[Out & "body" ~ String] =
            addField(Field.Body("body", ContentType.Text, ""))

        def bodyText[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): ResponseDef[Out & N ~ String] =
            addField(Field.Body(fieldName, ContentType.Text, description))

        def bodyBinary: ResponseDef[Out & "body" ~ Span[Byte]] =
            addField(Field.Body("body", ContentType.Binary, ""))

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): ResponseDef[Out & N ~ Span[Byte]] =
            addField(Field.Body(fieldName, ContentType.Binary, description))

        def bodyStream: ResponseDef[Out & "body" ~ Stream[Span[Byte], Async]] =
            addField(Field.Body("body", ContentType.ByteStream, ""))

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ Stream[Span[Byte], Async]] =
            addField(Field.Body(fieldName, ContentType.ByteStream, description))

        inline def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        ): ResponseDef[Out & "body" ~ Stream[V, Async]] =
            addField(Field.Body("body", ContentType.Ndjson(schema, kyo.Json.jsonSchema[V], emitTag), ""))

        inline def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): ResponseDef[Out & N ~ Stream[V, Async]] =
            addField(Field.Body(fieldName, ContentType.Ndjson(schema, kyo.Json.jsonSchema[V], emitTag), description))

        inline def bodySseJson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpSseEvent[V]]]]
        ): ResponseDef[Out & "body" ~ Stream[HttpSseEvent[V], Async]] =
            addField(Field.Body("body", ContentType.Sse(schema, kyo.Json.jsonSchema[V], emitTag), ""))

        inline def bodySseJson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpSseEvent[V]]]]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ Stream[HttpSseEvent[V], Async]] =
            addField(Field.Body(fieldName, ContentType.Sse(schema, kyo.Json.jsonSchema[V], emitTag), description))

        def bodySseText(using
            emitTag: Tag[Emit[Chunk[HttpSseEvent[String]]]]
        ): ResponseDef[Out & "body" ~ Stream[HttpSseEvent[String], Async]] =
            addField(Field.Body("body", ContentType.SseText(emitTag), ""))

        def bodySseText(using
            emitTag: Tag[Emit[Chunk[HttpSseEvent[String]]]]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ Stream[HttpSseEvent[String], Async]] =
            addField(Field.Body(fieldName, ContentType.SseText(emitTag), description))

        inline def error[E](using schema: Schema[E], tag: ConcreteTag[E])(s: HttpStatus): ResponseDef[Out] =
            ResponseDef(
                this.status,
                this.fields,
                this.errors.append(ErrorMapping(s, schema, kyo.Json.jsonSchema[E], tag.asInstanceOf[ConcreteTag[Any]]))
            )

        def status(s: HttpStatus): ResponseDef[Out] =
            ResponseDef(s, this.fields, this.errors)

        private def addField[F](field: Field[F]): ResponseDef[Out & F] =
            ResponseDef(this.status, this.fields.append(field), this.errors)

    end ResponseDef

    // ==================== Error mappings ====================

    case class ErrorMapping(status: HttpStatus, schema: Schema[?], jsonSchema: kyo.Json.JsonSchema, tag: ConcreteTag[Any]) derives CanEqual

    // ==================== Metadata ====================

    /** OpenAPI metadata for a route, used when generating an OpenAPI spec from registered handlers. These values are purely informational —
      * they enrich the generated spec but are not enforced at runtime.
      */
    case class Metadata(
        tags: Seq[String] = Seq.empty,
        summary: Maybe[String] = Absent,
        description: Maybe[String] = Absent,
        operationId: Maybe[String] = Absent,
        deprecated: Boolean = false,
        externalDocsUrl: Maybe[String] = Absent,
        externalDocsDesc: Maybe[String] = Absent,
        security: Maybe[String] = Absent
    ) derives CanEqual:
        def tag(t: String): Metadata                          = copy(tags = tags :+ t)
        def tags(ts: String*): Metadata                       = copy(tags = tags ++ ts)
        def summary(s: String): Metadata                      = copy(summary = Present(s))
        def description(d: String): Metadata                  = copy(description = Present(d))
        def operationId(id: String): Metadata                 = copy(operationId = Present(id))
        def markDeprecated: Metadata                          = copy(deprecated = true)
        def externalDocs(url: String): Metadata               = copy(externalDocsUrl = Present(url))
        def externalDocs(url: String, desc: String): Metadata = copy(externalDocsUrl = Present(url), externalDocsDesc = Present(desc))
        def security(scheme: String): Metadata                = copy(security = Present(scheme))
    end Metadata

end HttpRoute

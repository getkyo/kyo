package kyo

import HttpRequest.Method
import HttpRequest.Part
import kyo.internal.Content
import kyo.internal.Optionality
import scala.NamedTuple.AnyNamedTuple
import scala.annotation.implicitNotFound
import scala.util.NotGiven

/** Type-safe HTTP route definition with compile-time tracking of path captures, request inputs, response outputs, and error types.
  *
  * Routes are built incrementally via a builder pattern: start with a factory method (`HttpRoute.get`, `HttpRoute.post`, etc.), then chain
  * `.request(_.query[Int]("limit"))`, `.response(_.bodyJson[User])`, `.metadata(_.tag("users"))`, and `.error[NotFound](404)` to define the
  * full contract. The type parameters evolve with each builder call, ensuring the handler function receives exactly the declared inputs and
  * must produce exactly the declared outputs.
  *
  * Create a handler from a route via `route.handle(f)`, which wires up automatic input extraction (path params, query params, headers,
  * cookies, auth, body) and output serialization (JSON, text, streaming, headers, cookies, error mappings).
  *
  * Routes also drive OpenAPI spec generation and typed client calls (`HttpClient.call`).
  *
  * @tparam PathIn
  *   named tuple of path capture types
  * @tparam In
  *   named tuple of request input types (query, header, cookie, body, auth)
  * @tparam Out
  *   named tuple of response output types (body, header, cookie)
  * @tparam Err
  *   union of error types mapped to HTTP status codes
  * @see
  *   [[kyo.HttpHandler]]
  * @see
  *   [[kyo.HttpPath]]
  * @see
  *   [[kyo.HttpClient.call]]
  * @see
  *   [[kyo.HttpOpenApi]]
  */
case class HttpRoute[PathIn <: AnyNamedTuple, In <: AnyNamedTuple, Out <: AnyNamedTuple, Err](
    method: Method,
    path: HttpPath[PathIn],
    request: HttpRoute.RequestDef[In] = HttpRoute.RequestDef,
    response: HttpRoute.ResponseDef[Out, Err] = HttpRoute.ResponseDef,
    metadata: HttpRoute.Metadata = HttpRoute.Metadata()
):
    import HttpRoute.*

    def request[In2 <: AnyNamedTuple](f: RequestDef[In] => RequestDef[In2]): HttpRoute[PathIn, In2, Out, Err] =
        copy(request = f(request))

    def request[In2 <: AnyNamedTuple](req: RequestDef[In2]): HttpRoute[PathIn, In2, Out, Err] =
        copy(request = req)

    def response[Out2 <: AnyNamedTuple, Err2](f: ResponseDef[Out, Err] => ResponseDef[Out2, Err2]): HttpRoute[PathIn, In, Out2, Err2] =
        copy(response = f(response))

    def response[Out2 <: AnyNamedTuple, Err2](res: ResponseDef[Out2, Err2]): HttpRoute[PathIn, In, Out2, Err2] =
        copy(response = res)

    def metadata(f: Metadata => Metadata): HttpRoute[PathIn, In, Out, Err] =
        copy(metadata = f(metadata))

    def metadata(meta: Metadata): HttpRoute[PathIn, In, Out, Err] =
        copy(metadata = meta)

    def path[PathIn2 <: AnyNamedTuple](f: HttpPath[PathIn] => HttpPath[PathIn2]): HttpRoute[PathIn2, In, Out, Err] =
        copy(path = f(path))

    def path[PathIn2 <: AnyNamedTuple](p: HttpPath[PathIn2]): HttpRoute[PathIn2, In, Out, Err] =
        copy(path = p)

    // --- Handler creation ---

    def handle[S](using
        Frame
    )(
        f: Row[FullInput[PathIn, In]] => OutputValue[Out] < (Abort[Err] & Async & S)
    ): HttpHandler[S] =
        HttpHandler.fromRoute(this)(f)

end HttpRoute

object HttpRoute:

    // ==================== Type aliases ====================

    type FullInput[PathIn <: AnyNamedTuple, In <: AnyNamedTuple] =
        Row.Append[Row.Concat[PathIn, In], "request", HttpRequest[?]]

    type InputValue[PathIn <: AnyNamedTuple, In <: AnyNamedTuple] = Row.Values[Row.Concat[PathIn, In]] match
        case v *: EmptyTuple => v
        case _               => Row.Values[Row.Concat[PathIn, In]]

    type OutputValue[Out <: AnyNamedTuple] = Row.Values[Out] match
        case EmptyTuple      => Unit
        case v *: EmptyTuple => v
        case _               => Row[Out]

    // ==================== Codec (alias) ====================

    type Codec[A] = HttpCodec[A]
    val Codec = HttpCodec

    // ==================== Factory methods ====================

    def get[A <: AnyNamedTuple](path: HttpPath[A]): HttpRoute[A, Row.Empty, Row.Empty, Nothing]     = HttpRoute(Method.GET, path)
    def post[A <: AnyNamedTuple](path: HttpPath[A]): HttpRoute[A, Row.Empty, Row.Empty, Nothing]    = HttpRoute(Method.POST, path)
    def put[A <: AnyNamedTuple](path: HttpPath[A]): HttpRoute[A, Row.Empty, Row.Empty, Nothing]     = HttpRoute(Method.PUT, path)
    def patch[A <: AnyNamedTuple](path: HttpPath[A]): HttpRoute[A, Row.Empty, Row.Empty, Nothing]   = HttpRoute(Method.PATCH, path)
    def delete[A <: AnyNamedTuple](path: HttpPath[A]): HttpRoute[A, Row.Empty, Row.Empty, Nothing]  = HttpRoute(Method.DELETE, path)
    def head[A <: AnyNamedTuple](path: HttpPath[A]): HttpRoute[A, Row.Empty, Row.Empty, Nothing]    = HttpRoute(Method.HEAD, path)
    def options[A <: AnyNamedTuple](path: HttpPath[A]): HttpRoute[A, Row.Empty, Row.Empty, Nothing] = HttpRoute(Method.OPTIONS, path)

    // ==================== Metadata ====================

    case class Metadata(
        tags: Seq[String] = Seq.empty,
        summary: Maybe[String] = Absent,
        description: Maybe[String] = Absent,
        operationId: Maybe[String] = Absent,
        deprecated: Boolean = false,
        externalDocsUrl: Maybe[String] = Absent,
        externalDocsDesc: Maybe[String] = Absent,
        security: Maybe[String] = Absent
    ):
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

    // ==================== RequestDef ====================

    case class RequestDef[In <: AnyNamedTuple](inputFields: Seq[InputField]):

        def query[A](
            using
            opt: Optionality[A],
            codec: Codec[opt.Value]
        )[N <: String & Singleton](
            name: N,
            default: Option[opt.Value] = None,
            wireName: String = "",
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[Row.Append[In, N, A]] =
            val wn = if wireName.isEmpty then name else wireName
            addIn[N, A](InputField.Query(wn, codec.asInstanceOf[Codec[Any]], default.fold(Absent)(Present(_)), opt.isOptional, description))
        end query

        def header[A](
            using
            opt: Optionality[A],
            codec: Codec[opt.Value]
        )[N <: String & Singleton](
            name: N,
            default: Option[opt.Value] = None,
            wireName: String = "",
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[Row.Append[In, N, A]] =
            val wn = if wireName.isEmpty then name else wireName
            addIn[N, A](InputField.Header(
                wn,
                codec.asInstanceOf[Codec[Any]],
                default.fold(Absent)(Present(_)),
                opt.isOptional,
                description
            ))
        end header

        def cookie[A](
            using
            opt: Optionality[A],
            codec: Codec[opt.Value]
        )[N <: String & Singleton](
            name: N,
            default: Option[opt.Value] = None,
            wireName: String = "",
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[Row.Append[In, N, A]] =
            val wn = if wireName.isEmpty then name else wireName
            addIn[N, A](InputField.Cookie(
                wn,
                codec.asInstanceOf[Codec[Any]],
                default.fold(Absent)(Present(_)),
                opt.isOptional,
                description
            ))
        end cookie

        // --- Auth ---

        def authBearer(using UniqueRequestField[In, "bearer"]): RequestDef[Row.Append[In, "bearer", String]] =
            addIn["bearer", String](InputField.Auth(AuthScheme.Bearer))

        def authBasic: RequestDef[Row.Append[Row.Append[In, "username", String], "password", String]] =
            RequestDef(inputFields :+ InputField.Auth(AuthScheme.BasicUsername) :+ InputField.Auth(AuthScheme.BasicPassword))

        def authApiKey[N <: String & Singleton](name: N)(using UniqueRequestField[In, N]): RequestDef[Row.Append[In, N, String]] =
            addIn[N, String](InputField.Auth(AuthScheme.ApiKey(name, AuthLocation.Header)))

        def authApiKey[N <: String & Singleton](name: N, location: AuthLocation)(using
            UniqueRequestField[In, N]
        ): RequestDef[Row.Append[In, N, String]] =
            addIn[N, String](InputField.Auth(AuthScheme.ApiKey(name, location)))

        // --- Request Body ---

        def bodyJson[A: Schema](using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", A]] =
            bodyJson[A]("")

        def bodyJson[A: Schema](description: String)(using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", A]] =
            addIn["body", A](InputField.Body(Content.Json(Schema[A].asInstanceOf[Schema[Any]]), description))

        def bodyText(using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", String]] =
            bodyText("")

        def bodyText(description: String)(using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", String]] =
            addIn["body", String](InputField.Body(Content.Text, description))

        def bodyBinary(using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", Span[Byte]]] =
            bodyBinary("")

        def bodyBinary(description: String)(using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", Span[Byte]]] =
            addIn["body", Span[Byte]](InputField.Body(Content.Binary, description))

        def bodyStream(using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", Stream[Span[Byte], Async]]] =
            bodyStream("")

        def bodyStream(description: String)(using
            UniqueRequestField[In, "body"]
        ): RequestDef[Row.Append[In, "body", Stream[Span[Byte], Async]]] =
            addIn["body", Stream[Span[Byte], Async]](InputField.Body(Content.ByteStream, description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]],
            uf: UniqueRequestField[In, "body"]
        ): RequestDef[Row.Append[In, "body", Stream[V, Async]]] =
            bodyNdjson[V]("")

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]],
            uf: UniqueRequestField[In, "body"]
        )(description: String): RequestDef[Row.Append[In, "body", Stream[V, Async]]] =
            addIn["body", Stream[V, Async]](InputField.Body(
                Content.Ndjson(schema.asInstanceOf[Schema[Any]], emitTag.erased.asInstanceOf[Tag[Emit[Chunk[Any]]]]),
                description
            ))

        def bodyForm[A: Schema](using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", A]] =
            bodyForm[A]("")

        def bodyForm[A: Schema](description: String)(using UniqueRequestField[In, "body"]): RequestDef[Row.Append[In, "body", A]] =
            addIn["body", A](InputField.Body(Content.Form(Schema[A].asInstanceOf[Schema[Any]]), description))

        def bodyMultipart(using UniqueRequestField[In, "parts"]): RequestDef[Row.Append[In, "parts", Seq[Part]]] =
            bodyMultipart("")

        def bodyMultipart(description: String)(using UniqueRequestField[In, "parts"]): RequestDef[Row.Append[In, "parts", Seq[Part]]] =
            addIn["parts", Seq[Part]](InputField.Body(Content.Multipart, description))

        def bodyMultipartStream(using UniqueRequestField[In, "parts"]): RequestDef[Row.Append[In, "parts", Stream[Part, Async]]] =
            bodyMultipartStream("")

        def bodyMultipartStream(description: String)(using
            UniqueRequestField[In, "parts"]
        ): RequestDef[Row.Append[In, "parts", Stream[Part, Async]]] =
            addIn["parts", Stream[Part, Async]](InputField.Body(Content.MultipartStream, description))

        private def addIn[N <: String & Singleton, A](
            field: InputField
        )(using UniqueRequestField[In, N]): RequestDef[Row.Append[In, N, A]] =
            copy(inputFields = inputFields :+ field)

    end RequestDef

    object RequestDef extends RequestDef[Row.Empty](Seq.empty)

    // ==================== ResponseDef ====================

    case class ResponseDef[Out <: AnyNamedTuple, Err](
        status: HttpStatus,
        outputFields: Seq[OutputField],
        errorMappings: Seq[ErrorMapping]
    ):
        // --- Response Body ---

        def bodyJson[O: Schema](using UniqueResponseField[Out, "body"]): ResponseDef[Row.Append[Out, "body", O], Err] =
            bodyJson[O]("")

        def bodyJson[O: Schema](description: String)(using UniqueResponseField[Out, "body"]): ResponseDef[Row.Append[Out, "body", O], Err] =
            addOut["body", O](OutputField.Body(Content.Json(Schema[O].asInstanceOf[Schema[Any]]), description))

        def bodyText(using UniqueResponseField[Out, "body"]): ResponseDef[Row.Append[Out, "body", String], Err] =
            bodyText("")

        def bodyText(description: String)(using UniqueResponseField[Out, "body"]): ResponseDef[Row.Append[Out, "body", String], Err] =
            addOut["body", String](OutputField.Body(Content.Text, description))

        def bodyBinary(using UniqueResponseField[Out, "body"]): ResponseDef[Row.Append[Out, "body", Span[Byte]], Err] =
            bodyBinary("")

        def bodyBinary(description: String)(using UniqueResponseField[Out, "body"]): ResponseDef[Row.Append[Out, "body", Span[Byte]], Err] =
            addOut["body", Span[Byte]](OutputField.Body(Content.Binary, description))

        def bodyStream(using
            UniqueResponseField[Out, "body"]
        ): ResponseDef[Row.Append[Out, "body", Stream[Span[Byte], Async & Scope]], Err] =
            bodyStream("")

        def bodyStream(description: String)(using
            UniqueResponseField[Out, "body"]
        ): ResponseDef[Row.Append[Out, "body", Stream[Span[Byte], Async & Scope]], Err] =
            addOut["body", Stream[Span[Byte], Async & Scope]](OutputField.Body(Content.ByteStream, description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]],
            uf: UniqueResponseField[Out, "body"]
        ): ResponseDef[Row.Append[Out, "body", Stream[V, Async & Scope]], Err] =
            bodyNdjson[V]("")

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]],
            uf: UniqueResponseField[Out, "body"]
        )(description: String): ResponseDef[Row.Append[Out, "body", Stream[V, Async & Scope]], Err] =
            addOut["body", Stream[V, Async & Scope]](OutputField.Body(
                Content.Ndjson(schema.asInstanceOf[Schema[Any]], emitTag.erased.asInstanceOf[Tag[Emit[Chunk[Any]]]]),
                description
            ))

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]],
            uf: UniqueResponseField[Out, "body"]
        ): ResponseDef[Row.Append[Out, "body", Stream[HttpEvent[V], Async & Scope]], Err] =
            bodySse[V]("")

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]],
            uf: UniqueResponseField[Out, "body"]
        )(description: String): ResponseDef[Row.Append[Out, "body", Stream[HttpEvent[V], Async & Scope]], Err] =
            addOut["body", Stream[HttpEvent[V], Async & Scope]](OutputField.Body(
                Content.Sse(schema.asInstanceOf[Schema[Any]], emitTag.erased.asInstanceOf[Tag[Emit[Chunk[HttpEvent[Any]]]]]),
                description
            ))

        // --- Response Headers ---

        def header[A](
            using
            opt: Optionality[A],
            codec: Codec[opt.Value]
        )[N <: String & Singleton](
            name: N,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Row.Append[Out, N, A], Err] =
            val wn = if wireName.isEmpty then name else wireName
            addOut[N, A](OutputField.Header(wn, codec.asInstanceOf[Codec[Any]], opt.isOptional, description))
        end header

        // --- Response Cookies ---

        def cookie[A](
            using
            opt: Optionality[A],
            codec: Codec[opt.Value]
        )[N <: String & Singleton](
            name: N,
            attributes: CookieAttributes = CookieAttributes.default,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Row.Append[Out, N, A], Err] =
            val wn = if wireName.isEmpty then name else wireName
            addOut[N, A](OutputField.Cookie(wn, codec.asInstanceOf[Codec[Any]], opt.isOptional, attributes, description))
        end cookie

        // --- Errors ---

        def error[E: Schema](status: HttpStatus)(using tag: ConcreteTag[E]): ResponseDef[Out, Err | E] =
            copy(errorMappings =
                errorMappings :+ ErrorMapping(status, Schema[E].asInstanceOf[Schema[Any]], tag.asInstanceOf[ConcreteTag[Any]])
            )

        // --- Status ---

        def status(s: HttpStatus): ResponseDef[Out, Err] = copy(status = s)

        private def addOut[N <: String & Singleton, A](
            field: OutputField
        )(using UniqueResponseField[Out, N]): ResponseDef[Row.Append[Out, N, A], Err] =
            copy(outputFields = outputFields :+ field)
    end ResponseDef

    object ResponseDef extends ResponseDef[Row.Empty, Nothing](HttpStatus.Success.OK, Seq.empty, Seq.empty)

    // ==================== Auth ====================

    enum AuthScheme derives CanEqual:
        case Bearer
        case BasicUsername
        case BasicPassword
        case ApiKey(name: String, location: AuthLocation)
    end AuthScheme

    enum AuthLocation derives CanEqual:
        case Header, Query, Cookie

    // ==================== Cookie ====================

    case class CookieAttributes(
        httpOnly: Boolean = false,
        secure: Boolean = false,
        sameSite: Maybe[HttpResponse.Cookie.SameSite] = Absent,
        maxAge: Maybe[Int] = Absent,
        domain: Maybe[String] = Absent,
        path: Maybe[String] = Absent
    )

    object CookieAttributes:
        val default: CookieAttributes = CookieAttributes()

    // ==================== Duplicate field detection ====================

    @implicitNotFound("Duplicate request field '${N}' — this field was already added to the request definition")
    opaque type UniqueRequestField[A <: AnyNamedTuple, N <: String] = Unit
    given [A <: AnyNamedTuple, N <: String](using NotGiven[Row.HasName[Row.Names[A], N]]): UniqueRequestField[A, N] = ()

    @implicitNotFound("Duplicate response field '${N}' — this field was already added to the response definition")
    opaque type UniqueResponseField[A <: AnyNamedTuple, N <: String] = Unit
    given [A <: AnyNamedTuple, N <: String](using NotGiven[Row.HasName[Row.Names[A], N]]): UniqueResponseField[A, N] = ()

    // ==================== Field descriptors (private[kyo]) ====================

    private[kyo] enum InputField:
        case Query(name: String, codec: Codec[Any], default: Maybe[Any], optional: Boolean, description: String)
        case Header(name: String, codec: Codec[Any], default: Maybe[Any], optional: Boolean, description: String)
        case Cookie(name: String, codec: Codec[Any], default: Maybe[Any], optional: Boolean, description: String)
        case Body(content: Content, description: String)
        case Auth(scheme: AuthScheme)

        /** Server-side: extract this field's value from the request. */
        private[kyo] def extract(request: HttpRequest[?])(using Frame): Any < (Sync & Abort[HttpError]) = this match
            case Query(name, codec, default, optional, _) =>
                Abort.get(extractParam(request.query(name), codec, default, optional, "query", name))
            case Header(name, codec, default, optional, _) =>
                Abort.get(extractParam(request.header(name), codec, default, optional, "header", name))
            case Cookie(name, codec, default, optional, _) =>
                Abort.get(extractParam(request.cookie(name).map(_.value), codec, default, optional, "cookie", name))
            case Body(content: Content.Input, _) =>
                Abort.get(content.decodeFrom(request.asInstanceOf[HttpRequest[HttpBody.Bytes]]))
            case Body(content: Content.StreamInput, _) =>
                content.decodeFrom(request.asInstanceOf[HttpRequest[HttpBody.Streamed]])
            case Auth(scheme) => scheme match
                    case AuthScheme.Bearer =>
                        Abort.get(extractBearer(request))
                    case AuthScheme.BasicUsername =>
                        Abort.get(extractBasicAuth(request).map(_(0)))
                    case AuthScheme.BasicPassword =>
                        Abort.get(extractBasicAuth(request).map(_(1)))
                    case AuthScheme.ApiKey(name, location) =>
                        Abort.get(extractApiKey(request, name, location))

        /** Client-side: serialize this field's value for a request. */
        private[kyo] def serialize(value: Any): Maybe[String] = this match
            case Query(name, codec, _, optional, _) =>
                if optional then
                    value.asInstanceOf[Maybe[Any]] match
                        case Present(v) =>
                            Present(s"$name=${java.net.URLEncoder.encode(codec.serialize(v), "UTF-8")}")
                        case Absent => Absent
                else
                    Present(s"$name=${java.net.URLEncoder.encode(codec.serialize(value), "UTF-8")}")
            case Cookie(name, codec, _, optional, _) =>
                if optional then
                    value.asInstanceOf[Maybe[Any]] match
                        case Present(v) => Present(s"$name=${codec.serialize(v)}")
                        case Absent     => Absent
                else
                    Present(s"$name=${codec.serialize(value)}")
            case _ => Absent

        /** Client-side: serialize this field's value as a header. */
        private[kyo] def serializeHeader(value: Any): Maybe[(String, String)] = this match
            case Header(name, codec, _, optional, _) =>
                if optional then
                    value.asInstanceOf[Maybe[Any]] match
                        case Present(v) => Present((name, codec.serialize(v)))
                        case Absent     => Absent
                else
                    Present((name, codec.serialize(value)))
            case _ => Absent

        private[kyo] def isStreaming: Boolean = this match
            case Body(content, _) => content.isStreaming
            case _                => false

        private def extractParam(
            raw: Maybe[String],
            codec: HttpCodec[Any],
            default: Maybe[Any],
            optional: Boolean,
            kind: String,
            name: String
        )(using Frame): Result[HttpError.MissingParam, Any] =
            raw match
                case Present(v) =>
                    val parsed = codec.parse(v)
                    Result.succeed(if optional then Present(parsed) else parsed)
                case Absent =>
                    default match
                        case Present(d) => Result.succeed(if optional then Present(d) else d)
                        case Absent =>
                            if optional then Result.succeed(Absent)
                            else Result.fail(HttpError.MissingParam(s"Missing required $kind: $name"))

        private def extractBearer(request: HttpRequest[?])(using Frame): Result[HttpError, String] =
            request.header("Authorization") match
                case Absent => Result.fail(HttpError.MissingAuth("Authorization"))
                case Present(raw) =>
                    if raw.startsWith("Bearer ") then Result.succeed(raw.substring(7))
                    else Result.fail(HttpError.InvalidAuth("Expected Bearer token"))

        private def extractBasicAuth(request: HttpRequest[?])(using Frame): Result[HttpError, (String, String)] =
            request.header("Authorization") match
                case Absent => Result.fail(HttpError.MissingAuth("Authorization"))
                case Present(raw) =>
                    if !raw.startsWith("Basic ") then Result.fail(HttpError.InvalidAuth("Expected Basic auth"))
                    else
                        val decoded  = new String(java.util.Base64.getDecoder.decode(raw.substring(6)), "UTF-8")
                        val colonIdx = decoded.indexOf(':')
                        if colonIdx < 0 then Result.fail(HttpError.InvalidAuth("Invalid Basic auth format"))
                        else Result.succeed((decoded.substring(0, colonIdx), decoded.substring(colonIdx + 1)))

        private def extractApiKey(request: HttpRequest[?], name: String, location: AuthLocation)(using
            Frame
        ): Result[HttpError.MissingAuth, String] =
            val value = location match
                case AuthLocation.Header => request.header(name)
                case AuthLocation.Query  => request.query(name)
                case AuthLocation.Cookie => request.cookie(name).map(_.value)
            value match
                case Present(v) => Result.succeed(v)
                case Absent     => Result.fail(HttpError.MissingAuth(name))
        end extractApiKey
    end InputField

    private[kyo] enum OutputField:
        case Header(name: String, codec: Codec[Any], optional: Boolean, description: String)
        case Cookie(name: String, codec: Codec[Any], optional: Boolean, attributes: CookieAttributes, description: String)
        case Body(content: Content.Output, description: String)

        /** Server-side: serialize this field's value onto a response. */
        private[kyo] def serialize(value: Any, status: HttpStatus, base: Maybe[HttpResponse[?]])(using Frame): HttpResponse[?] =
            this match
                case Body(content, _) =>
                    content.encodeToResponse(value, status)
                case Header(name, codec, optional, _) =>
                    val resp = base.getOrElse(HttpResponse(status))
                    if !optional then
                        resp.setHeader(name, codec.serialize(value))
                    else
                        value.asInstanceOf[Maybe[Any]] match
                            case Present(v) => resp.setHeader(name, codec.serialize(v))
                            case Absent     => resp
                    end if
                case Cookie(name, codec, optional, attrs, _) =>
                    val resp = base.getOrElse(HttpResponse(status))
                    if !optional then
                        resp.addCookie(OutputField.buildCookie(name, codec.serialize(value), attrs))
                    else
                        value.asInstanceOf[Maybe[Any]] match
                            case Present(v) =>
                                resp.addCookie(OutputField.buildCookie(name, codec.serialize(v), attrs))
                            case Absent => resp
                    end if

        /** Client-side: decode buffered response body. Only meaningful for Body fields. */
        private[kyo] def extract(response: HttpResponse[HttpBody.Bytes])(using Frame): Any < Abort[HttpError] = this match
            case Body(content, _) => Abort.get(content.decodeFrom(response))
            case _                => ()

        /** Client-side: decode streaming response body. Only meaningful for Body fields with StreamOutput. */
        private[kyo] def extractStream(response: HttpResponse[HttpBody.Streamed])(using Frame): Any < (Async & Sync & Abort[HttpError]) =
            this match
                case Body(content: Content.StreamOutput, _) => content.decodeStreamFrom(response)
                case Body(content: Content.Output, _)       => response.ensureBytes.map(r => Abort.get(content.decodeFrom(r)))
                case _                                      => ()

        private[kyo] def isStreaming: Boolean = this match
            case Body(content, _) => content.isStreaming
            case _                => false
    end OutputField

    private[kyo] object OutputField:
        private[kyo] def buildCookie(name: String, value: String, attrs: CookieAttributes): HttpResponse.Cookie =
            val c0 = HttpResponse.Cookie(name, value)
            val c1 = if attrs.httpOnly then c0.httpOnly(true) else c0
            val c2 = if attrs.secure then c1.secure(true) else c1
            val c3 = attrs.sameSite match
                case Present(ss) => c2.sameSite(ss)
                case Absent      => c2
            val c4 = attrs.maxAge match
                case Present(s) => c3.maxAge(Duration.fromUnits(s.toLong, Duration.Units.Seconds))
                case Absent     => c3
            val c5 = attrs.domain match
                case Present(d) => c4.domain(d)
                case Absent     => c4
            val c6 = attrs.path match
                case Present(p) => c5.path(p)
                case Absent     => c5
            c6
        end buildCookie
    end OutputField

    // ==================== Error mappings (private[kyo]) ====================

    private[kyo] case class ErrorMapping(status: HttpStatus, schema: Schema[Any], tag: ConcreteTag[Any]):
        /** Server-side: try to encode an error value to an HTTP response. */
        private[kyo] def encode(err: Any): Maybe[HttpResponse[HttpBody.Bytes]] =
            if tag.accepts(err) then
                try
                    val json = schema.encode(err)
                    Present(
                        HttpResponse(status, json)
                            .setHeader("Content-Type", "application/json")
                    )
                catch case _: Throwable => Absent
            else Absent

        /** Client-side: try to decode an error from a response status + body. */
        private[kyo] def decode(responseStatus: HttpStatus, body: String): Maybe[Any] =
            if status.code == responseStatus.code then
                schema.decode(body).toMaybe
            else Absent
    end ErrorMapping

end HttpRoute

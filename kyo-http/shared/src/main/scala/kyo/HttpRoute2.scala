package kyo

import HttpRequest.Method
import HttpRequest.Part
import kyo.internal.Content
import kyo.internal.Optionality
import scala.NamedTuple.AnyNamedTuple
import scala.annotation.implicitNotFound
import scala.util.NotGiven

case class HttpRoute2[PathIn <: AnyNamedTuple, In <: AnyNamedTuple, Out <: AnyNamedTuple, Err](
    method: Method,
    path: HttpPath2[PathIn],
    request: HttpRoute2.RequestDef[In] = RequestDef,
    response: HttpRoute2.ResponseDef[Out, Err] = ResponseDef,
    metadata: HttpRoute2.Metadata = HttpRoute2.Metadata()
):
    import HttpRoute2.*

    def request[In2 <: AnyNamedTuple](f: RequestDef[In] => RequestDef[In2]): HttpRoute2[PathIn, In2, Out, Err] =
        copy(request = f(request))

    def request[In2 <: AnyNamedTuple](req: RequestDef[In2]): HttpRoute2[PathIn, In2, Out, Err] =
        copy(request = req)

    def response[Out2 <: AnyNamedTuple, Err2](f: ResponseDef[Out, Err] => ResponseDef[Out2, Err2]): HttpRoute2[PathIn, In, Out2, Err2] =
        copy(response = f(response))

    def response[Out2 <: AnyNamedTuple, Err2](res: ResponseDef[Out2, Err2]): HttpRoute2[PathIn, In, Out2, Err2] =
        copy(response = res)

    def metadata(f: Metadata => Metadata): HttpRoute2[PathIn, In, Out, Err] =
        copy(metadata = f(metadata))

    def metadata(meta: Metadata): HttpRoute2[PathIn, In, Out, Err] =
        copy(metadata = meta)

    def path[PathIn2 <: AnyNamedTuple](f: HttpPath2[PathIn] => HttpPath2[PathIn2]): HttpRoute2[PathIn2, In, Out, Err] =
        copy(path = f(path))

    def path[PathIn2 <: AnyNamedTuple](p: HttpPath2[PathIn2]): HttpRoute2[PathIn2, In, Out, Err] =
        copy(path = p)

end HttpRoute2

object HttpRoute2:

    // ==================== Codec (alias) ====================

    type Codec[A] = HttpCodec[A]
    val Codec = HttpCodec

    // ==================== Factory methods ====================

    def get[A <: AnyNamedTuple](path: HttpPath2[A]): HttpRoute2[A, Row.Empty, Row.Empty, Nothing]     = HttpRoute2(Method.GET, path)
    def post[A <: AnyNamedTuple](path: HttpPath2[A]): HttpRoute2[A, Row.Empty, Row.Empty, Nothing]    = HttpRoute2(Method.POST, path)
    def put[A <: AnyNamedTuple](path: HttpPath2[A]): HttpRoute2[A, Row.Empty, Row.Empty, Nothing]     = HttpRoute2(Method.PUT, path)
    def patch[A <: AnyNamedTuple](path: HttpPath2[A]): HttpRoute2[A, Row.Empty, Row.Empty, Nothing]   = HttpRoute2(Method.PATCH, path)
    def delete[A <: AnyNamedTuple](path: HttpPath2[A]): HttpRoute2[A, Row.Empty, Row.Empty, Nothing]  = HttpRoute2(Method.DELETE, path)
    def head[A <: AnyNamedTuple](path: HttpPath2[A]): HttpRoute2[A, Row.Empty, Row.Empty, Nothing]    = HttpRoute2(Method.HEAD, path)
    def options[A <: AnyNamedTuple](path: HttpPath2[A]): HttpRoute2[A, Row.Empty, Row.Empty, Nothing] = HttpRoute2(Method.OPTIONS, path)

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
            addIn[N, A](InputField.Query(wn, codec, default.fold(Absent)(Present(_)), opt.isOptional, description))
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
            addIn[N, A](InputField.Header(wn, codec, default.fold(Absent)(Present(_)), opt.isOptional, description))
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
            addIn[N, A](InputField.Cookie(wn, codec, default.fold(Absent)(Present(_)), opt.isOptional, description))
        end cookie

        // --- Auth ---

        def authBearer(using UniqueRequestField[In, "bearer"]): RequestDef[Row.Append[In, "bearer", String]] =
            addIn["bearer", String](InputField.Auth(AuthScheme.Bearer))

        def authBasic: RequestDef[Row.Append[Row.Append[In, "username", String], "password", String]] =
            RequestDef(inputFields :+ InputField.Auth(AuthScheme.Basic))
                .asInstanceOf[RequestDef[Row.Append[Row.Append[In, "username", String], "password", String]]]

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
            addIn["body", Stream[V, Async]](InputField.Body(Content.Ndjson(schema.asInstanceOf[Schema[Any]], emitTag.erased), description))

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
                .asInstanceOf[RequestDef[Row.Append[In, N, A]]]
        end addIn

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

        def bodyStream(using UniqueResponseField[Out, "body"]): ResponseDef[Row.Append[Out, "body", Stream[Span[Byte], Async]], Err] =
            bodyStream("")

        def bodyStream(description: String)(using
            UniqueResponseField[Out, "body"]
        ): ResponseDef[Row.Append[Out, "body", Stream[Span[Byte], Async]], Err] =
            addOut["body", Stream[Span[Byte], Async]](OutputField.Body(Content.ByteStream, description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]],
            uf: UniqueResponseField[Out, "body"]
        ): ResponseDef[Row.Append[Out, "body", Stream[V, Async]], Err] =
            bodyNdjson[V]("")

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]],
            uf: UniqueResponseField[Out, "body"]
        )(description: String): ResponseDef[Row.Append[Out, "body", Stream[V, Async]], Err] =
            addOut["body", Stream[V, Async]](OutputField.Body(
                Content.Ndjson(schema.asInstanceOf[Schema[Any]], emitTag.erased),
                description
            ))

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]],
            uf: UniqueResponseField[Out, "body"]
        ): ResponseDef[Row.Append[Out, "body", Stream[HttpEvent[V], Async]], Err] =
            bodySse[V]("")

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]],
            uf: UniqueResponseField[Out, "body"]
        )(description: String): ResponseDef[Row.Append[Out, "body", Stream[HttpEvent[V], Async]], Err] =
            addOut["body", Stream[HttpEvent[V], Async]](OutputField.Body(
                Content.Sse(schema.asInstanceOf[Schema[Any]], emitTag.erased),
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
            addOut[N, A](OutputField.Header(wn, codec, opt.isOptional, description))
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
            addOut[N, A](OutputField.Cookie(wn, codec, opt.isOptional, attributes, description))
        end cookie

        // --- Errors ---

        def error[E: Schema](status: HttpStatus)(using tag: ConcreteTag[E]): ResponseDef[Out, Err | E] =
            copy(errorMappings = errorMappings :+ ErrorMapping(status, Schema[E], tag.asInstanceOf[ConcreteTag[Any]]))
                .asInstanceOf[ResponseDef[Out, Err | E]]

        // --- Status ---

        def status(s: HttpStatus): ResponseDef[Out, Err] = copy(status = s)

        private def addOut[N <: String & Singleton, A](
            field: OutputField
        )(using UniqueResponseField[Out, N]): ResponseDef[Row.Append[Out, N, A], Err] =
            copy(outputFields = outputFields :+ field)
                .asInstanceOf[ResponseDef[Row.Append[Out, N, A], Err]]
        end addOut
    end ResponseDef

    object ResponseDef extends ResponseDef[Row.Empty, Nothing](HttpStatus.Success.OK, Seq.empty, Seq.empty)

    // ==================== Auth ====================

    enum AuthScheme:
        case Bearer
        case Basic
        case ApiKey(name: String, location: AuthLocation)
    end AuthScheme

    enum AuthLocation:
        case Header, Query, Cookie

    // ==================== Cookie ====================

    enum SameSite:
        case Strict, Lax, None
    end SameSite

    case class CookieAttributes(
        httpOnly: Boolean = false,
        secure: Boolean = false,
        sameSite: Maybe[SameSite] = Absent,
        maxAge: Maybe[Int] = Absent,
        domain: Maybe[String] = Absent,
        path: Maybe[String] = Absent
    )

    object CookieAttributes:
        val default: CookieAttributes = CookieAttributes()

    // ==================== Duplicate field detection ====================
    //
    // Compile-time guard: a given instance is only available when the field name N
    // does NOT already appear in the named tuple A. If it does, NotGiven fails and
    // the @implicitNotFound message is shown.

    @implicitNotFound("Duplicate request field '${N}' — this field was already added to the request definition")
    opaque type UniqueRequestField[A <: AnyNamedTuple, N <: String] = Unit
    given [A <: AnyNamedTuple, N <: String](using NotGiven[Row.HasName[Row.Names[A], N]]): UniqueRequestField[A, N] = ()

    @implicitNotFound("Duplicate response field '${N}' — this field was already added to the response definition")
    opaque type UniqueResponseField[A <: AnyNamedTuple, N <: String] = Unit
    given [A <: AnyNamedTuple, N <: String](using NotGiven[Row.HasName[Row.Names[A], N]]): UniqueResponseField[A, N] = ()

    // ==================== Field descriptors (private[kyo]) ====================

    private[kyo] enum InputField:
        case Query(name: String, codec: Codec[?], default: Maybe[Any], optional: Boolean, description: String)
        case Header(name: String, codec: Codec[?], default: Maybe[Any], optional: Boolean, description: String)
        case Cookie(name: String, codec: Codec[?], default: Maybe[Any], optional: Boolean, description: String)
        case Body(content: Content.Input, description: String)
        case Auth(scheme: AuthScheme)
    end InputField

    private[kyo] enum OutputField:
        case Header(name: String, codec: Codec[?], optional: Boolean, description: String)
        case Cookie(name: String, codec: Codec[?], optional: Boolean, attributes: CookieAttributes, description: String)
        case Body(content: Content.Output, description: String)
    end OutputField

    // ==================== Error mappings (private[kyo]) ====================

    private[kyo] case class ErrorMapping(status: HttpStatus, schema: Schema[?], tag: ConcreteTag[Any])

end HttpRoute2

package kyo.http2

import kyo.<
import kyo.Absent
import kyo.Async
import kyo.Chunk
import kyo.ConcreteTag
import kyo.Emit
import kyo.Frame
import kyo.Maybe
import kyo.Present
import kyo.Record.~
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Tag
import scala.annotation.implicitNotFound
import scala.annotation.targetName
import scala.compiletime

case class HttpRoute[In, Out, -S](
    method: HttpMethod,
    request: HttpRoute.RequestDef[In],
    response: HttpRoute.ResponseDef[Out] = HttpRoute.ResponseDef(),
    filter: HttpFilter[?, ?, ?, ?, S] = HttpFilter.noop,
    metadata: HttpRoute.Metadata = HttpRoute.Metadata()
) derives CanEqual:
    import HttpRoute.*

    def pathAppend[In2](suffix: HttpPath[In2]): HttpRoute[In & In2, Out, S] =
        copy(request = request.pathAppend(suffix))

    def pathPrepend[In2](prefix: HttpPath[In2]): HttpRoute[In & In2, Out, S] =
        copy(request = request.pathPrepend(prefix))

    def request[R](f: RequestDef[In] => R)(using s: Strict[RequestDef, R])(using s.Out <:< In): HttpRoute[s.Out, Out, S] =
        HttpRoute(method, s(f(request)), response, filter, metadata)

    def response[R](f: ResponseDef[Out] => R)(using s: Strict[ResponseDef, R])(using s.Out <:< Out): HttpRoute[In, s.Out, S] =
        HttpRoute(method, request, s(f(response)), filter, metadata)

    def filter[ReqIn >: In, ReqOut, ResIn >: Out, ResOut, S2](
        f: HttpFilter[ReqIn, ReqOut, ResIn, ResOut, S2]
    ): HttpRoute[In & ReqOut, Out & ResOut, S & S2] =
        HttpRoute(method, request, response, this.filter.andThen(f), metadata)
    end filter

    def metadata(f: Metadata => Metadata): HttpRoute[In, Out, S] =
        copy(metadata = f(metadata))

    def metadata(meta: Metadata): HttpRoute[In, Out, S] =
        copy(metadata = meta)

    def handle[S2](f: HttpRequest[In] => HttpResponse[Out] < S2)(using Frame): HttpHandler[In, Out, S & S2] =
        HttpHandler(this, req => f(req))

end HttpRoute

object HttpRoute:

    private def make[A](method: HttpMethod, path: HttpPath[A]): HttpRoute[A, Any, Any] =
        HttpRoute[A, Any, Any](method, RequestDef[A](path), ResponseDef[Any](), HttpFilter.noop, Metadata())

    def get[A](path: HttpPath[A]): HttpRoute[A, Any, Any]     = make(HttpMethod.GET, path)
    def post[A](path: HttpPath[A]): HttpRoute[A, Any, Any]    = make(HttpMethod.POST, path)
    def put[A](path: HttpPath[A]): HttpRoute[A, Any, Any]     = make(HttpMethod.PUT, path)
    def patch[A](path: HttpPath[A]): HttpRoute[A, Any, Any]   = make(HttpMethod.PATCH, path)
    def delete[A](path: HttpPath[A]): HttpRoute[A, Any, Any]  = make(HttpMethod.DELETE, path)
    def head[A](path: HttpPath[A]): HttpRoute[A, Any, Any]    = make(HttpMethod.HEAD, path)
    def options[A](path: HttpPath[A]): HttpRoute[A, Any, Any] = make(HttpMethod.OPTIONS, path)

    // ==================== ContentType ====================

    enum ContentType[A] derives CanEqual:
        case Text()                                                             extends ContentType[String]
        case Binary()                                                           extends ContentType[Span[Byte]]
        case ByteStream()                                                       extends ContentType[Stream[Span[Byte], Async & Scope]]
        case Multipart()                                                        extends ContentType[Seq[HttpPart]]
        case MultipartStream()                                                  extends ContentType[Stream[HttpPart, Async]]
        case Json[A](schema: Schema[A])                                         extends ContentType[A]
        case Ndjson[V](schema: Schema[V], emitTag: Tag[Emit[Chunk[V]]])         extends ContentType[Stream[V, Async]]
        case Sse[V](schema: Schema[V], emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]) extends ContentType[Stream[HttpEvent[V], Async & Scope]]
        case Form[A](codec: HttpFormCodec[A])                                   extends ContentType[A]
    end ContentType

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
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Location.Query, fieldName, wireName, codec, default, false, description))

        def queryOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Location.Query, fieldName, wireName, codec, default, true, description))

        def header[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, default, false, description))

        def headerOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, default, true, description))

        def cookie[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, default, false, description))

        def cookieOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, default, true, description))

        def bodyJson[A](using schema: Schema[A])(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ A] =
            add(Field.Body("body", ContentType.Json(schema), ""))

        def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using UniqueRequestField[In, N]): RequestDef[In & N ~ A] =
            add(Field.Body(fieldName, ContentType.Json(schema), description))

        def bodyText(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ String] =
            add(Field.Body("body", ContentType.Text(), ""))

        def bodyText[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueRequestField[In, N]
        ): RequestDef[In & N ~ String] =
            add(Field.Body(fieldName, ContentType.Text(), description))

        def bodyBinary(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ Span[Byte]] =
            add(Field.Body("body", ContentType.Binary(), ""))

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueRequestField[In, N]
        ): RequestDef[In & N ~ Span[Byte]] =
            add(Field.Body(fieldName, ContentType.Binary(), description))

        def bodyStream(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ Stream[Span[Byte], Async & Scope]] =
            add(Field.Body("body", ContentType.ByteStream(), ""))

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ Stream[Span[Byte], Async & Scope]] =
            add(Field.Body(fieldName, ContentType.ByteStream(), description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ Stream[V, Async]] =
            add(Field.Body("body", ContentType.Ndjson(schema, emitTag), ""))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueRequestField[In, N]
        ): RequestDef[In & N ~ Stream[V, Async]] =
            add(Field.Body(fieldName, ContentType.Ndjson(schema, emitTag), description))

        def bodyForm[A](using codec: HttpFormCodec[A])(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ A] =
            add(Field.Body("body", ContentType.Form(codec), ""))

        def bodyForm[A](using
            codec: HttpFormCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ A] =
            add(Field.Body(fieldName, ContentType.Form(codec), description))

        def bodyMultipart(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ Seq[HttpPart]] =
            add(Field.Body("body", ContentType.Multipart(), ""))

        def bodyMultipart[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueRequestField[In, N]
        ): RequestDef[In & N ~ Seq[HttpPart]] =
            add(Field.Body(fieldName, ContentType.Multipart(), description))

        def bodyMultipartStream(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ Stream[HttpPart, Async]] =
            add(Field.Body("body", ContentType.MultipartStream(), ""))

        def bodyMultipartStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ Stream[HttpPart, Async]] =
            add(Field.Body(fieldName, ContentType.MultipartStream(), description))

        private def add[F](field: Field[F]): RequestDef[In & F] =
            RequestDef(this.path, this.fields.append(field))

    end RequestDef

    // ==================== ResponseDef ====================

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
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ A] =
            addField(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, Absent, false, description))

        def headerOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ Maybe[A]] =
            addField(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, Absent, true, description))

        def cookie[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ HttpCookie[A]] =
            addField(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, Absent, false, description))

        def cookieOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ Maybe[HttpCookie[A]]] =
            addField(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, Absent, true, description))

        def bodyJson[A](using schema: Schema[A])(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ A] =
            addField(Field.Body("body", ContentType.Json(schema), ""))

        def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ A] =
            addField(Field.Body(fieldName, ContentType.Json(schema), description))

        def bodyText(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ String] =
            addField(Field.Body("body", ContentType.Text(), ""))

        def bodyText[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueResponseField[Out, N]
        ): ResponseDef[Out & N ~ String] =
            addField(Field.Body(fieldName, ContentType.Text(), description))

        def bodyBinary(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ Span[Byte]] =
            addField(Field.Body("body", ContentType.Binary(), ""))

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueResponseField[Out, N]
        ): ResponseDef[Out & N ~ Span[Byte]] =
            addField(Field.Body(fieldName, ContentType.Binary(), description))

        def bodyStream(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ Stream[Span[Byte], Async & Scope]] =
            addField(Field.Body("body", ContentType.ByteStream(), ""))

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ Stream[Span[Byte], Async & Scope]] =
            addField(Field.Body(fieldName, ContentType.ByteStream(), description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ Stream[V, Async]] =
            addField(Field.Body("body", ContentType.Ndjson(schema, emitTag), ""))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueResponseField[Out, N]
        ): ResponseDef[Out & N ~ Stream[V, Async]] =
            addField(Field.Body(fieldName, ContentType.Ndjson(schema, emitTag), description))

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]
        )(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ Stream[HttpEvent[V], Async & Scope]] =
            addField(Field.Body("body", ContentType.Sse(schema, emitTag), ""))

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ Stream[HttpEvent[V], Async & Scope]] =
            addField(Field.Body(fieldName, ContentType.Sse(schema, emitTag), description))

        def error[E](using schema: Schema[E], tag: ConcreteTag[E])(s: HttpStatus): ResponseDef[Out] =
            ResponseDef(this.status, this.fields, this.errors.append(ErrorMapping(s, schema, tag.asInstanceOf[ConcreteTag[Any]])))

        def status(s: HttpStatus): ResponseDef[Out] =
            ResponseDef(s, this.fields, this.errors)

        private def addField[F](field: Field[F]): ResponseDef[Out & F] =
            ResponseDef(this.status, this.fields.append(field), this.errors)

    end ResponseDef

    // ==================== Error mappings ====================

    case class ErrorMapping(status: HttpStatus, schema: Schema[?], tag: ConcreteTag[Any]) derives CanEqual

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

    // ==================== Type-level utilities ====================

    /** Prevents Scala 3 from widening ("softening") intersection types inferred for record fields.
      *
      * When a builder lambda like `_.query[Int]("page").bodyJson[User]` returns `RequestDef[A]`, the compiler may normalize the
      * intersection components of `A`, collapsing distinct record fields. This is analogous to the problem solved by the experimental
      * `Precise` typeclass (SIP-64), which tells the compiler not to widen a type variable. `Strict` achieves the same effect without
      * experimental features: the given instance matches `F[A]` exactly, extracting `A` as a dependent type and providing a safe identity
      * conversion, forcing the compiler to preserve the precise intersection type.
      */
    sealed trait Strict[F[_], R]:
        type Out
        def apply(r: R): F[Out]

    object Strict:
        given [F[_], A]: Strict[F, F[A]] with
            type Out = A
            def apply(r: F[A]): F[A] = r
    end Strict

    sealed trait HasFieldName[Fields, N <: String]
    object HasFieldName:
        given direct[N <: String, V]: HasFieldName[N ~ V, N]                               = null
        given inLeft[A, B, N <: String](using HasFieldName[A, N]): HasFieldName[A & B, N]  = null
        given inRight[A, B, N <: String](using HasFieldName[B, N]): HasFieldName[A & B, N] = null
    end HasFieldName

    @implicitNotFound("Duplicate request field '${N}' — this field was already added to the request definition")
    opaque type UniqueRequestField[+A, N <: String] = Unit
    inline given [A, N <: String]: UniqueRequestField[A, N] =
        val _ = scala.compiletime.summonInline[scala.util.NotGiven[HasFieldName[A, N]]]
        ()

    @implicitNotFound("Duplicate response field '${N}' — this field was already added to the response definition")
    opaque type UniqueResponseField[+A, N <: String] = Unit
    inline given [A, N <: String]: UniqueResponseField[A, N] =
        val _ = scala.compiletime.summonInline[scala.util.NotGiven[HasFieldName[A, N]]]
        ()

end HttpRoute

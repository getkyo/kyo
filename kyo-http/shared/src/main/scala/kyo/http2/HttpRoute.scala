package kyo.http2

import kyo.Absent
import kyo.Async
import kyo.Chunk
import kyo.ConcreteTag
import kyo.Emit
import kyo.Maybe
import kyo.Present
import kyo.Record.~
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Tag
import scala.annotation.implicitNotFound
import scala.annotation.targetName
import scala.util.NotGiven

case class HttpRoute[In, Out, -S](
    method: HttpMethod,
    request: HttpRoute.RequestDef[In],
    response: HttpRoute.ResponseDef[Out] = HttpRoute.ResponseDef(),
    metadata: HttpRoute.Metadata = HttpRoute.Metadata()
):
    import HttpRoute.*

    def pathAppend[In2](suffix: HttpPath[In2]): HttpRoute[In & In2, Out, S] =
        copy(request = request.pathAppend(suffix))

    def pathPrepend[In2](prefix: HttpPath[In2]): HttpRoute[In & In2, Out, S] =
        copy(request = request.pathPrepend(prefix))

    def request[R](f: RequestDef[In] => R): HttpRoute[Strict[RequestDef, R], Out, S] =
        HttpRoute(method, f(request).asInstanceOf[RequestDef[Strict[RequestDef, R]]], response, metadata)

    def request[In2](req: RequestDef[In2]): HttpRoute[In2, Out, S] =
        HttpRoute(method, req, response, metadata)

    def response[R](f: ResponseDef[Out] => R): HttpRoute[In, Out & Strict[ResponseDef, R], S] =
        HttpRoute(method, request, f(response).asInstanceOf[ResponseDef[Strict[ResponseDef, R]]], metadata)

    def response[Out2](res: ResponseDef[Out2]): HttpRoute[In, Out2, S] =
        HttpRoute(method, request, res, metadata)

    // def filter[ReqOut, ResOut, S2](
    //     f: HttpFilter[PathIn & In, ReqOut, Out, ResOut, S2]
    // ): HttpRoute[PathIn, In & ReqOut, Out & ResOut, S & S2] =
    //     val composed = this.filter.andThen(f)
    //     HttpRoute(method, path, request, response, composed, metadata)
    // end filter

    def metadata(f: Metadata => Metadata): HttpRoute[In, Out, S] =
        HttpRoute(method, request, response, f(metadata))

    def metadata(meta: Metadata): HttpRoute[In, Out, S] =
        HttpRoute(method, request, response, meta)

end HttpRoute

object HttpRoute:

    private def make[A](method: HttpMethod, path: HttpPath[A]): HttpRoute[A, Any, Any] =
        HttpRoute[A, Any, Any](method, RequestDef[A](path), ResponseDef[Any](), Metadata())

    def get[A](path: HttpPath[A]): HttpRoute[A, Any, Any]     = make(HttpMethod.GET, path)
    def post[A](path: HttpPath[A]): HttpRoute[A, Any, Any]    = make(HttpMethod.POST, path)
    def put[A](path: HttpPath[A]): HttpRoute[A, Any, Any]     = make(HttpMethod.PUT, path)
    def patch[A](path: HttpPath[A]): HttpRoute[A, Any, Any]   = make(HttpMethod.PATCH, path)
    def delete[A](path: HttpPath[A]): HttpRoute[A, Any, Any]  = make(HttpMethod.DELETE, path)
    def head[A](path: HttpPath[A]): HttpRoute[A, Any, Any]    = make(HttpMethod.HEAD, path)
    def options[A](path: HttpPath[A]): HttpRoute[A, Any, Any] = make(HttpMethod.OPTIONS, path)

    // ==================== ContentType ====================

    enum ContentType[A]:
        case Text()                                                             extends ContentType[String]
        case Binary()                                                           extends ContentType[Span[Byte]]
        case ByteStream()                                                       extends ContentType[Stream[Span[Byte], Async & Scope]]
        case Multipart()                                                        extends ContentType[Seq[HttpPart]]
        case MultipartStream()                                                  extends ContentType[Stream[HttpPart, Async]]
        case Json[A](schema: Schema[A])                                         extends ContentType[A]
        case Ndjson[V](schema: Schema[V], emitTag: Tag[Emit[Chunk[V]]])         extends ContentType[Stream[V, Async]]
        case Sse[V](schema: Schema[V], emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]) extends ContentType[Stream[HttpEvent[V], Async & Scope]]
    end ContentType

    // ==================== Field ====================

    sealed abstract class Field[-A]

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
    ):

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
        ): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Location.Query, fieldName, wireName, codec, default, false, description))

        def queryOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        ): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Location.Query, fieldName, wireName, codec, default, true, description))

        def header[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        ): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, default, false, description))

        def headerOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        ): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, default, true, description))

        def cookie[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        ): RequestDef[In & N ~ HttpCookie.Request[A]] =
            add(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, default, false, description))

        def cookieOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        ): RequestDef[In & N ~ Maybe[HttpCookie.Request[A]]] =
            add(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, default, true, description))

        def bodyJson[A](using schema: Schema[A]): RequestDef[In & "body" ~ A] =
            add(Field.Body("body", ContentType.Json(schema), ""))

        def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String): RequestDef[In & N ~ A] =
            add(Field.Body(fieldName, ContentType.Json(schema), description))

        def bodyText: RequestDef[In & "body" ~ String] =
            add(Field.Body("body", ContentType.Text(), ""))

        def bodyText[N <: String & Singleton](fieldName: N, description: String): RequestDef[In & N ~ String] =
            add(Field.Body(fieldName, ContentType.Text(), description))

        def bodyBinary: RequestDef[In & "body" ~ Span[Byte]] =
            add(Field.Body("body", ContentType.Binary(), ""))

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String): RequestDef[In & N ~ Span[Byte]] =
            add(Field.Body(fieldName, ContentType.Binary(), description))

        def bodyStream: RequestDef[In & "body" ~ Stream[Span[Byte], Async & Scope]] =
            add(Field.Body("body", ContentType.ByteStream(), ""))

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String
        ): RequestDef[In & N ~ Stream[Span[Byte], Async & Scope]] =
            add(Field.Body(fieldName, ContentType.ByteStream(), description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        ): RequestDef[In & "body" ~ Stream[V, Async]] =
            add(Field.Body("body", ContentType.Ndjson(schema, emitTag), ""))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String): RequestDef[In & N ~ Stream[V, Async]] =
            add(Field.Body(fieldName, ContentType.Ndjson(schema, emitTag), description))

        def bodyMultipart: RequestDef[In & "body" ~ Seq[HttpPart]] =
            add(Field.Body("body", ContentType.Multipart(), ""))

        def bodyMultipart[N <: String & Singleton](fieldName: N, description: String): RequestDef[In & N ~ Seq[HttpPart]] =
            add(Field.Body(fieldName, ContentType.Multipart(), description))

        def bodyMultipartStream: RequestDef[In & "body" ~ Stream[HttpPart, Async]] =
            add(Field.Body("body", ContentType.MultipartStream(), ""))

        def bodyMultipartStream[N <: String & Singleton](fieldName: N, description: String): RequestDef[In & N ~ Stream[HttpPart, Async]] =
            add(Field.Body(fieldName, ContentType.MultipartStream(), description))

        private def add[F](field: Field[F]): RequestDef[In & F] =
            RequestDef(this.path, this.fields.append(field))

    end RequestDef

    // ==================== ResponseDef ====================

    case class ResponseDef[-Out](
        status: HttpStatus = HttpStatus.Success.OK,
        fields: Chunk[Field[? >: Out]] = Chunk.empty,
        errors: Chunk[ErrorMapping] = Chunk.empty
    ):

        def header[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        ): ResponseDef[Out & N ~ A] =
            addField(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, Absent, false, description))

        def headerOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        ): ResponseDef[Out & N ~ Maybe[A]] =
            addField(Field.Param(Field.Param.Location.Header, fieldName, wireName, codec, Absent, true, description))

        def cookie[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        ): ResponseDef[Out & N ~ HttpCookie.Response[A]] =
            addField(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, Absent, false, description))

        def cookieOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        ): ResponseDef[Out & N ~ Maybe[HttpCookie.Response[A]]] =
            addField(Field.Param(Field.Param.Location.Cookie, fieldName, wireName, codec, Absent, true, description))

        def bodyJson[A](using schema: Schema[A]): ResponseDef[Out & "body" ~ A] =
            addField(Field.Body("body", ContentType.Json(schema), ""))

        def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String): ResponseDef[Out & N ~ A] =
            addField(Field.Body(fieldName, ContentType.Json(schema), description))

        def bodyText: ResponseDef[Out & "body" ~ String] =
            addField(Field.Body("body", ContentType.Text(), ""))

        def bodyText[N <: String & Singleton](fieldName: N, description: String): ResponseDef[Out & N ~ String] =
            addField(Field.Body(fieldName, ContentType.Text(), description))

        def bodyBinary: ResponseDef[Out & "body" ~ Span[Byte]] =
            addField(Field.Body("body", ContentType.Binary(), ""))

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String): ResponseDef[Out & N ~ Span[Byte]] =
            addField(Field.Body(fieldName, ContentType.Binary(), description))

        def bodyStream: ResponseDef[Out & "body" ~ Stream[Span[Byte], Async & Scope]] =
            addField(Field.Body("body", ContentType.ByteStream(), ""))

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String
        ): ResponseDef[Out & N ~ Stream[Span[Byte], Async & Scope]] =
            addField(Field.Body(fieldName, ContentType.ByteStream(), description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        ): ResponseDef[Out & "body" ~ Stream[V, Async]] =
            addField(Field.Body("body", ContentType.Ndjson(schema, emitTag), ""))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String): ResponseDef[Out & N ~ Stream[V, Async]] =
            addField(Field.Body(fieldName, ContentType.Ndjson(schema, emitTag), description))

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]
        ): ResponseDef[Out & "body" ~ Stream[HttpEvent[V], Async & Scope]] =
            addField(Field.Body("body", ContentType.Sse(schema, emitTag), ""))

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]
        )[N <: String & Singleton](
            fieldName: N,
            description: String
        ): ResponseDef[Out & N ~ Stream[HttpEvent[V], Async & Scope]] =
            addField(Field.Body(fieldName, ContentType.Sse(schema, emitTag), description))

        def error[E](using schema: Schema[E], tag: ConcreteTag[E])(s: HttpStatus): ResponseDef[Out] =
            ResponseDef(this.status, this.fields, this.errors.append(ErrorMapping(s, schema, tag.asInstanceOf[ConcreteTag[Any]])))

        def status(s: HttpStatus): ResponseDef[Out] =
            ResponseDef(s, this.fields, this.errors)

        private def addField[F](field: Field[F]): ResponseDef[Out & F] =
            ResponseDef(this.status, this.fields.append(field), this.errors)

    end ResponseDef

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

    // ==================== Strict type extraction (workaround for Scala 3 intersection normalization) ====================

    type Strict[F[_], R] = R match
        case F[a] => a

    // ==================== Duplicate field detection ====================

    sealed trait HasFieldName[Fields, N <: String]
    object HasFieldName:
        given direct[N <: String, V]: HasFieldName[N ~ V, N]                               = null
        given inLeft[A, B, N <: String](using HasFieldName[A, N]): HasFieldName[A & B, N]  = null
        given inRight[A, B, N <: String](using HasFieldName[B, N]): HasFieldName[A & B, N] = null
    end HasFieldName

    @implicitNotFound("Duplicate request field '${N}' — this field was already added to the request definition")
    opaque type UniqueRequestField[-A, N <: String] = Unit
    given [A, N <: String](using NotGiven[HasFieldName[A, N]]): UniqueRequestField[A, N] = ()

    @implicitNotFound("Duplicate response field '${N}' — this field was already added to the response definition")
    opaque type UniqueResponseField[-A, N <: String] = Unit
    given [A, N <: String](using NotGiven[HasFieldName[A, N]]): UniqueResponseField[A, N] = ()

    // ==================== Error mappings ====================

    case class ErrorMapping(status: HttpStatus, schema: Schema[?], tag: ConcreteTag[Any])

end HttpRoute

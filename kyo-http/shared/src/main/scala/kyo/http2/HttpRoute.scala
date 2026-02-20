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
import scala.util.NotGiven

case class HttpRoute[PathIn, In, Out, Err](
    method: HttpMethod,
    path: HttpPath[PathIn],
    request: HttpRoute.RequestDef[In] = HttpRoute.RequestDef(),
    response: HttpRoute.ResponseDef[Out, Err] = HttpRoute.ResponseDef(),
    metadata: HttpRoute.Metadata = HttpRoute.Metadata()
):
    import HttpRoute.*

    def path[PathIn2](p: HttpPath[PathIn2]): HttpRoute[PathIn2, In, Out, Err] =
        HttpRoute[PathIn2, In, Out, Err](method, p, request, response, metadata)

    def path[PathIn2](f: HttpPath[PathIn] => HttpPath[PathIn2]): HttpRoute[PathIn2, In, Out, Err] =
        HttpRoute[PathIn2, In, Out, Err](method, f(path), request, response, metadata)

    def request[In2](f: RequestDef[In] => RequestDef[In2]): HttpRoute[PathIn, In2, Out, Err] =
        HttpRoute[PathIn, In2, Out, Err](method, path, f(request), response, metadata)

    def request[In2](req: RequestDef[In2]): HttpRoute[PathIn, In2, Out, Err] =
        HttpRoute[PathIn, In2, Out, Err](method, path, req, response, metadata)

    def response[Out2, Err2](f: ResponseDef[Out, Err] => ResponseDef[Out2, Err2]): HttpRoute[PathIn, In, Out2, Err2] =
        HttpRoute[PathIn, In, Out2, Err2](method, path, request, f(response), metadata)

    def response[Out2, Err2](res: ResponseDef[Out2, Err2]): HttpRoute[PathIn, In, Out2, Err2] =
        HttpRoute[PathIn, In, Out2, Err2](method, path, request, res, metadata)

    def metadata(f: Metadata => Metadata): HttpRoute[PathIn, In, Out, Err] =
        HttpRoute[PathIn, In, Out, Err](method, path, request, response, f(metadata))

    def metadata(meta: Metadata): HttpRoute[PathIn, In, Out, Err] =
        HttpRoute[PathIn, In, Out, Err](method, path, request, response, meta)

end HttpRoute

object HttpRoute:

    private def make[A](method: HttpMethod, path: HttpPath[A]): HttpRoute[A, Any, Any, Nothing] =
        HttpRoute[A, Any, Any, Nothing](method, path, RequestDef[Any](), ResponseDef[Any, Nothing](), Metadata())

    def get[A](path: HttpPath[A]): HttpRoute[A, Any, Any, Nothing]     = make(HttpMethod.GET, path)
    def post[A](path: HttpPath[A]): HttpRoute[A, Any, Any, Nothing]    = make(HttpMethod.POST, path)
    def put[A](path: HttpPath[A]): HttpRoute[A, Any, Any, Nothing]     = make(HttpMethod.PUT, path)
    def patch[A](path: HttpPath[A]): HttpRoute[A, Any, Any, Nothing]   = make(HttpMethod.PATCH, path)
    def delete[A](path: HttpPath[A]): HttpRoute[A, Any, Any, Nothing]  = make(HttpMethod.DELETE, path)
    def head[A](path: HttpPath[A]): HttpRoute[A, Any, Any, Nothing]    = make(HttpMethod.HEAD, path)
    def options[A](path: HttpPath[A]): HttpRoute[A, Any, Any, Nothing] = make(HttpMethod.OPTIONS, path)

    // ==================== ContentType ====================

    enum ContentType[A]:
        case Json[A](schema: Schema[A])                                         extends ContentType[A]
        case Text()                                                             extends ContentType[String]
        case Binary()                                                           extends ContentType[Span[Byte]]
        case ByteStream()                                                       extends ContentType[Stream[Span[Byte], Async & Scope]]
        case Ndjson[V](schema: Schema[V], emitTag: Tag[Emit[Chunk[V]]])         extends ContentType[Stream[V, Async]]
        case Sse[V](schema: Schema[V], emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]) extends ContentType[Stream[HttpEvent[V], Async & Scope]]
        case Multipart()                                                        extends ContentType[Seq[HttpPart]]
        case MultipartStream()                                                  extends ContentType[Stream[HttpPart, Async]]
    end ContentType

    // ==================== Field ====================

    sealed abstract class Field[-A]

    object Field:

        case class Param[N <: String, A, F](
            kind: Param.Kind,
            fieldName: N,
            wireName: String,
            codec: HttpCodec[A],
            default: Maybe[A],
            optional: Boolean,
            description: String
        ) extends Field[N ~ F]

        object Param:
            enum Kind derives CanEqual:
                case Query
                case Header
                case Cookie
            end Kind
        end Param

        case class Body[N <: String, A](
            fieldName: N,
            contentType: ContentType[A],
            description: String
        ) extends Field[N ~ A]

    end Field

    // ==================== RequestDef ====================

    case class RequestDef[+In](
        fields: Chunk[Field[? <: In]] = Chunk.empty
    ):

        def query[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Kind.Query, fieldName, wireName, codec, default, false, description))

        def queryOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Kind.Query, fieldName, wireName, codec, default, true, description))

        def header[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ A] =
            add(Field.Param(Field.Param.Kind.Header, fieldName, wireName, codec, default, false, description))

        def headerOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ Maybe[A]] =
            add(Field.Param(Field.Param.Kind.Header, fieldName, wireName, codec, default, true, description))

        def cookie[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ HttpCookie.Request[A]] =
            add(Field.Param(Field.Param.Kind.Cookie, fieldName, wireName, codec, default, false, description))

        def cookieOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            default: Maybe[A] = Absent,
            description: String = ""
        )(using UniqueRequestField[In, N]): RequestDef[In & N ~ Maybe[HttpCookie.Request[A]]] =
            add(Field.Param(Field.Param.Kind.Cookie, fieldName, wireName, codec, default, true, description))

        def bodyJson[A](using schema: Schema[A], u: UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ A] =
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
            emitTag: Tag[Emit[Chunk[V]]],
            u: UniqueRequestField[In, "body"]
        ): RequestDef[In & "body" ~ Stream[V, Async]] =
            add(Field.Body("body", ContentType.Ndjson(schema, emitTag), ""))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueRequestField[In, N]
        ): RequestDef[In & N ~ Stream[V, Async]] =
            add(Field.Body(fieldName, ContentType.Ndjson(schema, emitTag), description))

        def bodyMultipart(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ Seq[HttpPart]] =
            add(Field.Body("body", ContentType.Multipart(), ""))

        def bodyMultipart[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueRequestField[In, N]
        ): RequestDef[In & N ~ Seq[HttpPart]] =
            add(Field.Body(fieldName, ContentType.Multipart(), description))

        def bodyMultipartStream(using UniqueRequestField[In, "body"]): RequestDef[In & "body" ~ Stream[HttpPart, Async]] =
            add(Field.Body("body", ContentType.MultipartStream(), ""))

        def bodyMultipartStream[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueRequestField[In, N]
        ): RequestDef[In & N ~ Stream[HttpPart, Async]] =
            add(Field.Body(fieldName, ContentType.MultipartStream(), description))

        private def add[F](field: Field[F]): RequestDef[In & F] =
            RequestDef(fields.append(field))

    end RequestDef

    // ==================== ResponseDef ====================

    case class ResponseDef[+Out, +Err](
        status: HttpStatus = HttpStatus.Success.OK,
        fields: Chunk[Field[? <: Out]] = Chunk.empty,
        errors: Chunk[ErrorMapping[Err]] = Chunk.empty
    ):

        def header[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ A, Err] =
            ResponseDef(
                status,
                fields.append(Field.Param(Field.Param.Kind.Header, fieldName, wireName, codec, Absent, false, description)),
                errors
            )

        def headerOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ Maybe[A], Err] =
            ResponseDef(
                status,
                fields.append(Field.Param(Field.Param.Kind.Header, fieldName, wireName, codec, Absent, true, description)),
                errors
            )

        def cookie[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ HttpCookie.Response[A], Err] =
            ResponseDef(
                status,
                fields.append(Field.Param(Field.Param.Kind.Cookie, fieldName, wireName, codec, Absent, false, description)),
                errors
            )

        def cookieOpt[A](using
            codec: HttpCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            wireName: String = "",
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ Maybe[HttpCookie.Response[A]], Err] =
            ResponseDef(
                status,
                fields.append(Field.Param(Field.Param.Kind.Cookie, fieldName, wireName, codec, Absent, true, description)),
                errors
            )

        def bodyJson[A](using schema: Schema[A], u: UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ A, Err] =
            ResponseDef(status, fields.append(Field.Body("body", ContentType.Json(schema), "")), errors)

        def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueResponseField[Out, N]
        ): ResponseDef[Out & N ~ A, Err] =
            ResponseDef(status, fields.append(Field.Body(fieldName, ContentType.Json(schema), description)), errors)

        def bodyText(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ String, Err] =
            ResponseDef(status, fields.append(Field.Body("body", ContentType.Text(), "")), errors)

        def bodyText[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueResponseField[Out, N]
        ): ResponseDef[Out & N ~ String, Err] =
            ResponseDef(status, fields.append(Field.Body(fieldName, ContentType.Text(), description)), errors)

        def bodyBinary(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ Span[Byte], Err] =
            ResponseDef(status, fields.append(Field.Body("body", ContentType.Binary(), "")), errors)

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueResponseField[Out, N]
        ): ResponseDef[Out & N ~ Span[Byte], Err] =
            ResponseDef(status, fields.append(Field.Body(fieldName, ContentType.Binary(), description)), errors)

        def bodyStream(using UniqueResponseField[Out, "body"]): ResponseDef[Out & "body" ~ Stream[Span[Byte], Async & Scope], Err] =
            ResponseDef(status, fields.append(Field.Body("body", ContentType.ByteStream(), "")), errors)

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ Stream[Span[Byte], Async & Scope], Err] =
            ResponseDef(status, fields.append(Field.Body(fieldName, ContentType.ByteStream(), description)), errors)

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]],
            u: UniqueResponseField[Out, "body"]
        ): ResponseDef[Out & "body" ~ Stream[V, Async], Err] =
            ResponseDef(status, fields.append(Field.Body("body", ContentType.Ndjson(schema, emitTag), "")), errors)

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using
            UniqueResponseField[Out, N]
        ): ResponseDef[Out & N ~ Stream[V, Async], Err] =
            ResponseDef(status, fields.append(Field.Body(fieldName, ContentType.Ndjson(schema, emitTag), description)), errors)

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]],
            u: UniqueResponseField[Out, "body"]
        ): ResponseDef[Out & "body" ~ Stream[HttpEvent[V], Async & Scope], Err] =
            ResponseDef(status, fields.append(Field.Body("body", ContentType.Sse(schema, emitTag), "")), errors)

        def bodySse[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using UniqueResponseField[Out, N]): ResponseDef[Out & N ~ Stream[HttpEvent[V], Async & Scope], Err] =
            ResponseDef(status, fields.append(Field.Body(fieldName, ContentType.Sse(schema, emitTag), description)), errors)

        def error[E](using schema: Schema[E], tag: ConcreteTag[E])(s: HttpStatus): ResponseDef[Out, Err | E] =
            ResponseDef(status, fields, errors.append(ErrorMapping(s, schema, tag.asInstanceOf[ConcreteTag[Any]])))

        def status(s: HttpStatus): ResponseDef[Out, Err] =
            copy(status = s)

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

    case class ErrorMapping[+E](status: HttpStatus, schema: Schema[?], tag: ConcreteTag[Any])

end HttpRoute

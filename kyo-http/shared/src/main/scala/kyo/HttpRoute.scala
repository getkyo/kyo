package kyo

import kyo.*
import kyo.Record.~

case class HttpRoute[In, Out, +E](
    method: HttpMethod,
    request: HttpRoute.RequestDef[In],
    response: HttpRoute.ResponseDef[Out] = HttpRoute.ResponseDef(),
    filter: HttpFilter[?, ?, ?, ?, ? <: E] = HttpFilter.noop,
    metadata: HttpRoute.Metadata = HttpRoute.Metadata()
) derives CanEqual:
    import HttpRoute.*

    def pathAppend[In2](suffix: HttpPath[In2]): HttpRoute[In & In2, Out, E] =
        copy(request = request.pathAppend(suffix))

    def pathPrepend[In2](prefix: HttpPath[In2]): HttpRoute[In & In2, Out, E] =
        copy(request = request.pathPrepend(prefix))

    def request[R](f: RequestDef[In] => R)(using s: Fields.Exact[RequestDef, R])(using s.Out <:< In): HttpRoute[s.Out, Out, E] =
        HttpRoute(method, s(f(request)), response, filter, metadata)

    def response[R](f: ResponseDef[Out] => R)(using s: Fields.Exact[ResponseDef, R])(using s.Out <:< Out): HttpRoute[In, s.Out, E] =
        HttpRoute(method, request, s(f(response)), filter, metadata)

    def filter[ReqIn >: In, ReqOut, ResIn >: Out, ResOut, E2](
        f: HttpFilter[ReqIn, ReqOut, ResIn, ResOut, E2]
    ): HttpRoute[In & ReqOut, Out & ResOut, E | E2] =
        HttpRoute(method, request, response, this.filter.andThen(f), metadata)
    end filter

    def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(s: HttpStatus): HttpRoute[In, Out, E | E2] =
        copy(response = response.error[E2](s))

    def metadata(f: Metadata => Metadata): HttpRoute[In, Out, E] =
        copy(metadata = f(metadata))

    def metadata(meta: Metadata): HttpRoute[In, Out, E] =
        copy(metadata = meta)

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

    enum ContentType[A] derives CanEqual:
        case Text()                                                             extends ContentType[String]
        case Binary()                                                           extends ContentType[Span[Byte]]
        case ByteStream()                                                       extends ContentType[Stream[Span[Byte], Async]]
        case Multipart()                                                        extends ContentType[Seq[HttpPart]]
        case MultipartStream()                                                  extends ContentType[Stream[HttpPart, Async]]
        case Json[A](schema: Schema[A])                                         extends ContentType[A]
        case Ndjson[V](schema: Schema[V], emitTag: Tag[Emit[Chunk[V]]])         extends ContentType[Stream[V, Async]]
        case Sse[V](schema: Schema[V], emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]) extends ContentType[Stream[HttpEvent[V], Async]]
        case SseText(emitTag: Tag[Emit[Chunk[HttpEvent[String]]]])              extends ContentType[Stream[HttpEvent[String], Async]]
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

        def bodyJson[A](using schema: Schema[A]): RequestDef[In & "body" ~ A] =
            add(Field.Body("body", ContentType.Json(schema), ""))

        def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using Fields.Pin[N]): RequestDef[In & N ~ A] =
            add(Field.Body(fieldName, ContentType.Json(schema), description))

        def bodyText: RequestDef[In & "body" ~ String] =
            add(Field.Body("body", ContentType.Text(), ""))

        def bodyText[N <: String & Singleton](fieldName: N, description: String = "")(using Fields.Pin[N]): RequestDef[In & N ~ String] =
            add(Field.Body(fieldName, ContentType.Text(), description))

        def bodyBinary: RequestDef[In & "body" ~ Span[Byte]] =
            add(Field.Body("body", ContentType.Binary(), ""))

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): RequestDef[In & N ~ Span[Byte]] =
            add(Field.Body(fieldName, ContentType.Binary(), description))

        def bodyStream: RequestDef[In & "body" ~ Stream[Span[Byte], Async]] =
            add(Field.Body("body", ContentType.ByteStream(), ""))

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ Stream[Span[Byte], Async]] =
            add(Field.Body(fieldName, ContentType.ByteStream(), description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        ): RequestDef[In & "body" ~ Stream[V, Async]] =
            add(Field.Body("body", ContentType.Ndjson(schema, emitTag), ""))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using Fields.Pin[N]): RequestDef[In & N ~ Stream[V, Async]] =
            add(Field.Body(fieldName, ContentType.Ndjson(schema, emitTag), description))

        def bodyForm[A](using codec: HttpFormCodec[A]): RequestDef[In & "body" ~ A] =
            add(Field.Body("body", ContentType.Form(codec), ""))

        def bodyForm[A](using
            codec: HttpFormCodec[A]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ A] =
            add(Field.Body(fieldName, ContentType.Form(codec), description))

        def bodyMultipart: RequestDef[In & "body" ~ Seq[HttpPart]] =
            add(Field.Body("body", ContentType.Multipart(), ""))

        def bodyMultipart[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): RequestDef[In & N ~ Seq[HttpPart]] =
            add(Field.Body(fieldName, ContentType.Multipart(), description))

        def bodyMultipartStream: RequestDef[In & "body" ~ Stream[HttpPart, Async]] =
            add(Field.Body("body", ContentType.MultipartStream(), ""))

        def bodyMultipartStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): RequestDef[In & N ~ Stream[HttpPart, Async]] =
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

        def bodyJson[A](using schema: Schema[A]): ResponseDef[Out & "body" ~ A] =
            addField(Field.Body("body", ContentType.Json(schema), ""))

        def bodyJson[A](using
            schema: Schema[A]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using Fields.Pin[N]): ResponseDef[Out & N ~ A] =
            addField(Field.Body(fieldName, ContentType.Json(schema), description))

        def bodyText: ResponseDef[Out & "body" ~ String] =
            addField(Field.Body("body", ContentType.Text(), ""))

        def bodyText[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): ResponseDef[Out & N ~ String] =
            addField(Field.Body(fieldName, ContentType.Text(), description))

        def bodyBinary: ResponseDef[Out & "body" ~ Span[Byte]] =
            addField(Field.Body("body", ContentType.Binary(), ""))

        def bodyBinary[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): ResponseDef[Out & N ~ Span[Byte]] =
            addField(Field.Body(fieldName, ContentType.Binary(), description))

        def bodyStream: ResponseDef[Out & "body" ~ Stream[Span[Byte], Async]] =
            addField(Field.Body("body", ContentType.ByteStream(), ""))

        def bodyStream[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ Stream[Span[Byte], Async]] =
            addField(Field.Body(fieldName, ContentType.ByteStream(), description))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        ): ResponseDef[Out & "body" ~ Stream[V, Async]] =
            addField(Field.Body("body", ContentType.Ndjson(schema, emitTag), ""))

        def bodyNdjson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[V]]]
        )[N <: String & Singleton](fieldName: N, description: String = "")(using
            Fields.Pin[N]
        ): ResponseDef[Out & N ~ Stream[V, Async]] =
            addField(Field.Body(fieldName, ContentType.Ndjson(schema, emitTag), description))

        def bodySseJson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]
        ): ResponseDef[Out & "body" ~ Stream[HttpEvent[V], Async]] =
            addField(Field.Body("body", ContentType.Sse(schema, emitTag), ""))

        def bodySseJson[V](using
            schema: Schema[V],
            emitTag: Tag[Emit[Chunk[HttpEvent[V]]]]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ Stream[HttpEvent[V], Async]] =
            addField(Field.Body(fieldName, ContentType.Sse(schema, emitTag), description))

        def bodySseText(using
            emitTag: Tag[Emit[Chunk[HttpEvent[String]]]]
        ): ResponseDef[Out & "body" ~ Stream[HttpEvent[String], Async]] =
            addField(Field.Body("body", ContentType.SseText(emitTag), ""))

        def bodySseText(using
            emitTag: Tag[Emit[Chunk[HttpEvent[String]]]]
        )[N <: String & Singleton](
            fieldName: N,
            description: String = ""
        )(using Fields.Pin[N]): ResponseDef[Out & N ~ Stream[HttpEvent[String], Async]] =
            addField(Field.Body(fieldName, ContentType.SseText(emitTag), description))

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

end HttpRoute

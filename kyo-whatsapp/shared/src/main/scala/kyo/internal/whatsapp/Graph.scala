package kyo.internal.whatsapp

import kyo.*
import scala.reflect.TypeTest

/** The one request path of the Cloud API, on the config and HTTP client of a `WhatsApp`.
  *
  * Every call reads its answer with `failOnError = false`, so the module maps status and body itself and no kyo-http status failure is
  * raised. Every request runs on the client's own `HttpClient` under the complete `HttpClientConfig` of `WhatsAppConfig.httpConfig`, which
  * replaces the caller's: a caller's filter would see the token, a caller's base url would resolve a relative url, and a caller's TLS
  * setting would govern the token's connection.
  *
  * No kyo-http failure is kept, since each names the request's url: it is described by a [[kyo.WhatsAppTransportException]]'s fields and
  * dropped. Which leaf a Graph error becomes on which operation is written once, as the traits the leaves mix in: `within` keeps the leaf a
  * code names when it carries the operation's trait `F`, and otherwise answers [[kyo.WhatsAppOtherApiException]].
  */
private[kyo] object Graph:

    /** The leaves every HTTP operation can fail with, whatever it calls. */
    type Common = WhatsAppTransportException | WhatsAppUnexpectedStatusException | WhatsAppOtherApiException

    /** Runs `request` to `target` on the client and answers the body of a 2xx; a non-2xx is the Graph error's leaf within `F`, or the
      * status leaf when the body is not a Graph error.
      */
    def call[F >: Common](client: WhatsApp, method: String, target: HttpUrl)(
        request: HttpClient => HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException])
    )(using Frame, TypeTest[WhatsAppException, F]): Span[Byte] < (Async & Abort[F]) =
        transport(client, method, target)(request).map { response =>
            if response.status.isSuccess then response.fields.body
            else Abort.get(statusFailure[F](client.config.token, method, response.status, response.fields.body)).map(Abort.fail(_))
        }

    /** Runs a kyo-http request on the client under the module's own config, and describes its failure without keeping it. */
    def transport[A](client: WhatsApp, method: String, target: HttpUrl)(
        request: HttpClient => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[WhatsAppTransportException]) =
        Abort.runWith[HttpException](
            HttpClient.let(client.http)(HttpClient.withConfig(WhatsAppConfig.httpConfig(client.config))(request(client.http)))
        ) {
            case Result.Success(a) => a
            case Result.Failure(e) =>
                val (kind, timeout, cause) = describe(e)
                Abort.fail(WhatsAppTransportException(method, kind, target.host, target.port, timeout)(cause))
            // kyo-http's HTTP/1 client completes a response it never received with a panic of an anonymous
            // `IOException("connection closed") with NoStackTrace`: for a close before the head, an oversized head, a status outside 100
            // to 599, and a failure of the connection's channel alike. kyo-http exports no type for it, so it is matched by its shape and
            // message, and typed as `NoResponseHead`, which claims none of the four.
            case Result.Panic(e: (java.io.IOException & scala.util.control.NoStackTrace))
                if (e.getClass.getSuperclass eq classOf[java.io.IOException]) && e.getMessage == "connection closed" =>
                Abort.fail(WhatsAppTransportException(
                    method,
                    WhatsAppTransportException.Kind.NoResponseHead,
                    target.host,
                    target.port,
                    Absent
                )())
            case Result.Panic(e) => Abort.panic(e)
        }

    /** The kind, the timeout that ran out, and kyo-net's cause, of every kyo-http leaf a call of this module can meet. The cause is kept
      * only for a connection that could not be made, whose kyo-http leaf holds a host and a port and no url.
      *
      * The match is exhaustive with no wildcard, so a kyo-http leaf added upstream fails to compile here. A leaf no call can produce is a
      * module defect and panics by its name only: a status leaf (every call reads with `failOnError = false`), a redirect leaf (redirects
      * are off), a typed-body decode or server leaf (the calls read raw bytes and serve nothing), a unix-socket leaf (every url is refused
      * before one is sent to), and a url or header leaf (every url and header the module sends is built from validated parts, and a media
      * url is refused unless it is printable ASCII on a host).
      */
    private[kyo] def describe(e: HttpException): (WhatsAppTransportException.Kind, Maybe[Duration], Maybe[kyo.net.NetException]) =
        import WhatsAppTransportException.Kind
        def unreachable(leaf: String): Nothing = bug(s"$leaf reached a kyo-whatsapp call")
        e match
            case e: HttpConnectException =>
                e.cause match
                    case tls: kyo.net.NetTlsException => (Kind.Tls, Absent, Present(tls))
                    case net: kyo.net.NetException    => (Kind.Connect, Absent, Present(net))
                    case _                            => (Kind.Connect, Absent, Absent)
            case e: HttpDnsResolutionException =>
                e.cause match
                    case net: kyo.net.NetException => (Kind.Dns, Absent, Present(net))
                    case _                         => (Kind.Dns, Absent, Absent)
            case e: HttpConnectTimeoutException       => (Kind.ConnectTimeout, Present(e.timeout), Absent)
            case e: HttpPoolExhaustedException        => (Kind.PoolExhausted(e.maxConnections), Absent, Absent)
            case e: HttpTimeoutException              => (Kind.Timeout, Present(e.duration), Absent)
            case _: HttpConnectionClosedException     => (Kind.ConnectionClosed, Absent, Absent)
            case _: HttpProtocolException             => (Kind.Protocol, Absent, Absent)
            case _: HttpMalformedBodyException        => (Kind.Protocol, Absent, Absent)
            case e: HttpPayloadTooLargeException      => (Kind.PayloadTooLarge(e.bodySize.bytes, e.maxSize.bytes), Absent, Absent)
            case _: HttpUrlParseException             => unreachable("HttpUrlParseException")
            case _: HttpNonAsciiException             => unreachable("HttpNonAsciiException")
            case _: HttpInvalidFieldException         => unreachable("HttpInvalidFieldException")
            case _: HttpWebSocketHandshakeException   => unreachable("HttpWebSocketHandshakeException")
            case _: HttpUnixConnectException          => unreachable("HttpUnixConnectException")
            case _: HttpRedirectLoopException         => unreachable("HttpRedirectLoopException")
            case _: HttpStatusException               => unreachable("HttpStatusException")
            case _: HttpBindException                 => unreachable("HttpBindException")
            case _: HttpHandlerException              => unreachable("HttpHandlerException")
            case _: HttpFieldDecodeException          => unreachable("HttpFieldDecodeException")
            case _: HttpPathDecodeException           => unreachable("HttpPathDecodeException")
            case _: HttpMissingFieldException         => unreachable("HttpMissingFieldException")
            case _: HttpJsonDecodeException           => unreachable("HttpJsonDecodeException")
            case _: HttpFormDecodeException           => unreachable("HttpFormDecodeException")
            case _: HttpUnsupportedMediaTypeException => unreachable("HttpUnsupportedMediaTypeException")
            case _: HttpStreamingDecodeException      => unreachable("HttpStreamingDecodeException")
            case _: HttpMissingBoundaryException      => unreachable("HttpMissingBoundaryException")
        end match
    end describe

    /** A non-2xx answer as `F`: its Graph error's leaf, or the status leaf when the body is not a Graph error. A panic while decoding the
      * body is a defect and stays a panic.
      */
    private[kyo] def statusFailure[F >: Common](token: WhatsAppToken, method: String, status: HttpStatus, body: Span[Byte])(using
        Frame,
        TypeTest[WhatsAppException, F]
    ): Result[Nothing, F] =
        Json.decodeBytes[Wire.ErrorEnvelope](body) match
            case Result.Success(env) => Result.succeed(within[F](method, leafFor(token, method, env.error)))
            case Result.Failure(_)   => Result.succeed(WhatsAppUnexpectedStatusException(method, status))
            case Result.Panic(t)     => Result.panic(t)

    /** The leaf a Graph error names, on whichever operation received it, with the token redacted from Meta's text. */
    private[kyo] def leafFor(token: WhatsAppToken, method: String, err: Wire.ErrorDto)(using Frame): WhatsAppApiException =
        val description = redact(token, err.message)
        val details     = err.error_data.flatMap(_.details).map(redact(token, _))
        val subcode     = err.error_subcode
        val traceId     = err.fbtrace_id
        err.code match
            case 190                                => WhatsAppTokenExpiredException(method, subcode, description, details, traceId)
            case code @ (0 | 3 | 10 | 131005)       => WhatsAppAccessDeniedException(method, code, subcode, description, details, traceId)
            case code if code >= 200 && code <= 299 => WhatsAppAccessDeniedException(method, code, subcode, description, details, traceId)
            case 4                                  => WhatsAppAppRateLimitException(method, subcode, description, details, traceId)
            case 80007  => WhatsAppBusinessAccountRateLimitException(method, subcode, description, details, traceId)
            case 130429 => WhatsAppThroughputRateLimitException(method, subcode, description, details, traceId)
            case 131056 => WhatsAppRecipientPairRateLimitException(method, subcode, description, details, traceId)
            case 131026 => WhatsAppUndeliverableException(method, subcode, description, details, traceId)
            case 131021 => WhatsAppSenderIsRecipientException(method, subcode, description, details, traceId)
            case 131047 => WhatsAppWindowClosedException(method, subcode, description, details, traceId)
            case 132000 => WhatsAppTemplateParameterCountException(method, subcode, description, details, traceId)
            case 132001 => WhatsAppTemplateNotFoundException(method, subcode, description, details, traceId)
            case 132005 => WhatsAppTemplateTextTooLongException(method, subcode, description, details, traceId)
            case 132007 => WhatsAppTemplateContentPolicyException(method, subcode, description, details, traceId)
            case 132012 => WhatsAppTemplateParameterFormatException(method, subcode, description, details, traceId)
            case 132015 => WhatsAppTemplatePausedException(method, subcode, description, details, traceId)
            case 131053 => WhatsAppMediaUploadException(method, subcode, description, details, traceId)
            case code @ (100 | 131008 | 131009 | 135000) =>
                WhatsAppInvalidParameterException(method, code, subcode, description, details, traceId)
            case code @ (131000 | 131016) => WhatsAppServiceUnavailableException(method, code, subcode, description, details, traceId)
            case code                     => WhatsAppOtherApiException(method, code, subcode, description, details, traceId)
        end match
    end leafFor

    /** Meta's text with every occurrence of the token's value replaced, for an answer that echoes the request. */
    private[kyo] def redact(token: WhatsAppToken, text: String): String =
        text.replace(token.value, "<redacted>")

    private def within[F >: WhatsAppOtherApiException](method: String, leaf: WhatsAppApiException)(using
        Frame,
        TypeTest[WhatsAppException, F]
    ): F =
        leaf match
            case named: F => named
            case _        =>
                WhatsAppOtherApiException(method, leaf.code, leaf.subcode, leaf.description, leaf.details, leaf.traceId)

end Graph

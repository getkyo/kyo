package kyo

import WhatsAppExceptionMembershipTest.*

/** The failure-trait table as data, every pair of it produced through the real client and no pair beyond it. */
class WhatsAppExceptionMembershipTest extends BaseWhatsAppTest:

    // Distinctive secrets: every failure the run produces is checked not to render either of them.
    val token           = "SECRET-TOKEN-4f9c2b"
    val queryCredential = "SECRET-QUERY-8d2e71"
    val credentialQuery = s"?hash=$queryCredential&ext=1"
    val phoneId         = WhatsAppId.PhoneNumberId("106540352242922")
    val mediaId         = WhatsAppId.MediaId("M1")
    val to              = WhatsAppId.WaId("16505551234")

    import Leaf.*
    import Op.*

    val http    = Set(S, T, R, C, U, V, D, X)
    val generic = Set(S, T, R, C, U, V, D, X)

    val table: Map[Leaf, Set[Op]] = Map(
        OtherApi                 -> (http + F),
        TokenExpired             -> generic,
        AccessDenied             -> generic,
        AppRateLimit             -> generic,
        BusinessAccountRateLimit -> generic,
        ThroughputRateLimit      -> Set(S, T),
        RecipientPairRateLimit   -> Set(S, T),
        Undeliverable            -> Set(S, T),
        SenderIsRecipient        -> Set(S, T),
        WindowClosed             -> Set(S),
        TemplateNotFound         -> Set(T),
        TemplateParameterCount   -> Set(T),
        TemplateParameterFormat  -> Set(T),
        TemplateContentPolicy    -> Set(T),
        TemplatePaused           -> Set(T),
        TemplateTextTooLong      -> Set(T),
        MediaUpload              -> Set(S, T, U),
        InvalidParameter         -> generic,
        ServiceUnavailable       -> generic,
        UnexpectedStatus         -> (http + F),
        RefusedUrl               -> Set(V, D, F, X),
        Transport                -> (http + F),
        Decode                   -> (http + N),
        SignatureMissing         -> Set(G),
        SignatureMalformed       -> Set(G),
        SignatureMismatch        -> Set(G)
    )

    val tablePairs: Set[(Leaf, Op)] = table.toSet.flatMap((leaf, ops) => ops.map(op => (leaf, op)))

    def fullErrorBody(code: Int): String =
        s"""{"error":{"message":"(#$code) msg","type":"OAuthException","code":$code,"error_subcode":2494055,"error_data":{"messaging_product":"whatsapp","details":"details $code"},"fbtrace_id":"trace$code","error_user_title":"title","error_user_msg":"user msg"}}"""

    /** The fields every Graph leaf copies from `fullErrorBody(code)`. */
    def described(code: Int) = (Present(2494055), s"(#$code) msg", Present(s"details $code"), Present(s"trace$code"))

    /** One code per Graph leaf, and the leaf that code builds on an operation, as `(method, code) => leaf`. */
    val graphLeaves: Chunk[(Leaf, Int, (String, Int) => WhatsAppException)] =
        def one(build: (String, Maybe[Int], String, Maybe[String], Maybe[String]) => WhatsAppException)
            : (String, Int) => WhatsAppException =
            (m, code) =>
                val (s, d, det, t) = described(code)
                build(m, s, d, det, t)
        def many(build: (String, Int, Maybe[Int], String, Maybe[String], Maybe[String]) => WhatsAppException)
            : (String, Int) => WhatsAppException =
            (m, code) =>
                val (s, d, det, t) = described(code)
                build(m, code, s, d, det, t)
        Chunk(
            (OtherApi, 131042, many(WhatsAppOtherApiException(_, _, _, _, _, _))),
            (TokenExpired, 190, one(WhatsAppTokenExpiredException(_, _, _, _, _))),
            (AccessDenied, 10, many(WhatsAppAccessDeniedException(_, _, _, _, _, _))),
            (AppRateLimit, 4, one(WhatsAppAppRateLimitException(_, _, _, _, _))),
            (BusinessAccountRateLimit, 80007, one(WhatsAppBusinessAccountRateLimitException(_, _, _, _, _))),
            (ThroughputRateLimit, 130429, one(WhatsAppThroughputRateLimitException(_, _, _, _, _))),
            (RecipientPairRateLimit, 131056, one(WhatsAppRecipientPairRateLimitException(_, _, _, _, _))),
            (Undeliverable, 131026, one(WhatsAppUndeliverableException(_, _, _, _, _))),
            (SenderIsRecipient, 131021, one(WhatsAppSenderIsRecipientException(_, _, _, _, _))),
            (WindowClosed, 131047, one(WhatsAppWindowClosedException(_, _, _, _, _))),
            (TemplateNotFound, 132001, one(WhatsAppTemplateNotFoundException(_, _, _, _, _))),
            (TemplateParameterCount, 132000, one(WhatsAppTemplateParameterCountException(_, _, _, _, _))),
            (TemplateParameterFormat, 132012, one(WhatsAppTemplateParameterFormatException(_, _, _, _, _))),
            (TemplateContentPolicy, 132007, one(WhatsAppTemplateContentPolicyException(_, _, _, _, _))),
            (TemplatePaused, 132015, one(WhatsAppTemplatePausedException(_, _, _, _, _))),
            (TemplateTextTooLong, 132005, one(WhatsAppTemplateTextTooLongException(_, _, _, _, _))),
            (MediaUpload, 131053, one(WhatsAppMediaUploadException(_, _, _, _, _))),
            (InvalidParameter, 100, many(WhatsAppInvalidParameterException(_, _, _, _, _, _))),
            (ServiceUnavailable, 131000, many(WhatsAppServiceUnavailableException(_, _, _, _, _, _)))
        )
    end graphLeaves

    /** What each route of the local server answers. `info` defaults to a media info that points `downloadFrom` back at `bytes`. */
    final case class Replies(
        messages: (kyo.HttpStatus, String),
        media: (kyo.HttpStatus, String),
        info: (kyo.HttpStatus, String),
        delete: (kyo.HttpStatus, String),
        custom: (kyo.HttpStatus, String),
        bytes: (kyo.HttpStatus, String)
    )

    val ok = kyo.HttpStatus(200)

    def defaultReplies(port: Int): Replies = Replies(
        messages = (ok, """{"messaging_product":"whatsapp","messages":[{"id":"wamid.X"}]}"""),
        media = (ok, s"""{"id":"${mediaId.value}"}"""),
        info = (
            ok,
            s"""{"messaging_product":"whatsapp","url":"http://localhost:$port/bytes$credentialQuery","mime_type":"image/png","sha256":"h","file_size":"3","id":"${mediaId.value}"}"""
        ),
        delete = (ok, """{"success":true}"""),
        custom = (ok, """{"value":"v"}"""),
        bytes = (ok, "abc")
    )

    /** The replies with the route `op` calls (for `D`, its first step) answering `reply`. */
    def failing(op: Op, port: Int, reply: (kyo.HttpStatus, String)): Replies =
        val base = defaultReplies(port)
        op match
            case S | T | R => base.copy(messages = reply)
            case U         => base.copy(media = reply)
            case V | D     => base.copy(info = reply)
            case X         => base.copy(delete = reply)
            case C         => base.copy(custom = reply)
            case F         => base.copy(bytes = reply)
            case N | G     => base
        end match
    end failing

    val refusedPort = 1

    def infoAt(at: HttpUrl): WhatsAppMedia.MediaInfo = WhatsAppMedia.MediaInfo(mediaId, at, "image/png", "h", 3.bytes)

    /** Runs the operation against `baseUrl`; `downloadFrom` fetches `bytesUrl`, and the media verbs use `media`. */
    def run(op: Op, baseUrl: HttpUrl, bytesUrl: HttpUrl, media: WhatsAppId.MediaId = mediaId)(using
        Frame
    ): Result[WhatsAppException, Unit] < (Async & Scope) =
        Abort.run[WhatsAppException] {
            WhatsApp.let(WhatsAppConfig(WhatsAppToken(token), phoneId, baseUrl = baseUrl)) {
                op match
                    case S     => WhatsApp.send(to, WhatsAppMessage.Text("hi")).unit
                    case T     => WhatsApp.sendTemplate(to, WhatsAppTemplate("hello_world", "en_US")).unit
                    case R     => WhatsApp.markRead(WhatsAppId.MessageId("wamid.IN"))
                    case C     => WhatsApp.custom[Unit, CustomDto](HttpMethod.GET, WhatsAppPath("custom")).unit
                    case U     => WhatsAppMedia.upload(Span.from("abc".getBytes("UTF-8")), WhatsAppMedia.MediaType.ImagePng).unit
                    case V     => WhatsAppMedia.resolveUrl(media).unit
                    case D     => WhatsAppMedia.download(media).unit
                    case F     => WhatsAppMedia.downloadFrom(infoAt(bytesUrl)).unit
                    case X     => WhatsAppMedia.delete(media)
                    case N | G => Kyo.unit
            }
        }
    end run

    /** The method a leaf names for `op` (`download` fails in its first step here). */
    def methodOf(op: Op): String = op match
        case S     => "send"
        case T     => "sendTemplate"
        case R     => "markRead"
        case C     => "custom"
        case U     => "upload"
        case V | D => "resolveUrl"
        case F     => "downloadFrom"
        case X     => "delete"
        case N | G => "webhook"

    val httpOps: Chunk[Op] = Chunk(S, T, R, C, U, V, D, F, X)

    "every (leaf, operation) pair of the table is produced through the real client, compared by whole value" in {
        AtomicRef.init(defaultReplies(0)).map { replies =>
            def reply(pick: Replies => (kyo.HttpStatus, String)) =
                replies.get.map(r => HttpResponse(pick(r)._1).addField("body", pick(r)._2))
            val handlers = Seq(
                HttpRoute.postRaw("v25.0" / phoneId.value / "messages").request(_.bodyBinary).response(_.bodyText)
                    .handler(_ => reply(_.messages)),
                HttpRoute.postRaw(s"v25.0/${phoneId.value}/media").request(_.bodyMultipart).response(_.bodyText)
                    .handler(_ => reply(_.media)),
                HttpRoute.getRaw("v25.0" / mediaId.value).response(_.bodyText).handler(_ => reply(_.info)),
                HttpRoute.deleteRaw("v25.0" / mediaId.value).response(_.bodyText).handler(_ => reply(_.delete)),
                HttpRoute.getRaw("v25.0" / "custom").response(_.bodyText).handler(_ => reply(_.custom)),
                HttpRoute.getRaw("bytes").response(_.bodyText).handler(_ => reply(_.bytes))
            )
            HttpServer.init(0, "localhost")(handlers*).map { server =>
                val local    = url(s"http://localhost:${server.port}")
                val bytesUrl = url(s"http://localhost:${server.port}/bytes$credentialQuery")

                /** Runs `op` with `replies`, checks the failure equals `expected`, that neither the token nor the pre-signed URL's
                  * credential appears in any rendering of the failure or its causes, and returns its (leaf, operation) pair.
                  */
                def produce(op: Op, r: Replies, baseUrl: HttpUrl, fetchUrl: HttpUrl, media: WhatsAppId.MediaId = mediaId)(
                    expected: WhatsAppException => WhatsAppException
                ) =
                    replies.set(r).andThen(run(op, baseUrl, fetchUrl, media)).map { result =>
                        val e = failureOf[WhatsAppException](result)
                        assert(e == expected(e), s"$op")
                        BaseWhatsAppTest.renderings(e).foreach { text =>
                            assert(!text.contains(token), s"token rendered for $op")
                            assert(!text.contains(queryCredential), s"URL credential rendered for $op")
                        }
                        (kindOf(e), op)
                    }

                val graph = Kyo.foreach(httpOps) { op =>
                    Kyo.foreach(graphLeaves) { case (leaf, code, build) =>
                        val named =
                            if table(leaf).contains(op) then build(methodOf(op), code)
                            else
                                val (s, d, det, t) = described(code)
                                WhatsAppOtherApiException(methodOf(op), code, s, d, det, t)
                        produce(op, failing(op, server.port, (kyo.HttpStatus(400), fullErrorBody(code))), local, bytesUrl)(_ => named)
                    }
                }.map(_.flatten)

                val status = Kyo.foreach(httpOps) { op =>
                    val body = "Bad Gateway from proxy"
                    val r    = if op == D then defaultReplies(server.port).copy(bytes = (kyo.HttpStatus(502), body))
                    else failing(op, server.port, (kyo.HttpStatus(502), body))
                    val method = if op == D then "downloadFrom" else methodOf(op)
                    produce(op, r, local, bytesUrl)(_ => WhatsAppUnexpectedStatusException(method, kyo.HttpStatus(502)))
                }

                // A relative media url, which a caller's base url would resolve, and a media id that is not one path segment.
                val refused = Kyo.foreach(Chunk(V, D, F, X)) { op =>
                    produce(op, defaultReplies(server.port), local, url(s"/bytes$credentialQuery"), WhatsAppId.MediaId("M1/extra"))(_ =>
                        WhatsAppRefusedUrlException(methodOf(op))
                    )
                }

                val transport = Kyo.foreach(httpOps) { op =>
                    val base = if op == F then local else url(s"http://localhost:$refusedPort")
                    produce(op, defaultReplies(server.port), base, url(s"http://localhost:$refusedPort/bytes$credentialQuery")) { e =>
                        val t = e match
                            case t: WhatsAppTransportException => t
                            case other                         => fail(s"expected a transport leaf for $op, got: $other")
                        assert(t.cause.map(_.getClass.getSimpleName).nonEmpty, s"no kyo-net cause for $op")
                        WhatsAppTransportException(methodOf(op), WhatsAppTransportException.Kind.Connect, "localhost", refusedPort, Absent)(
                            t.cause
                        )
                    }
                }

                val decode = Kyo.foreach(httpOps.filter(_ != F)) { op =>
                    // A JSON string where every response expects an object: kyo-schema rejects it with a ParseException.
                    produce(op, failing(op, server.port, (ok, "\"x\"")), local, bytesUrl) { _ =>
                        WhatsAppDecodeException(
                            methodOf(op),
                            WhatsAppDecodeException.Part.Response,
                            WhatsAppDecodeException.Failure.Parse,
                            Chunk.empty,
                            Present(0)
                        )
                    }
                }

                // An answer that decodes but is not the call's success is the decode leaf, located at the field.
                def shape(op: Op, failure: WhatsAppDecodeException.Failure, field: String) =
                    WhatsAppDecodeException(methodOf(op), WhatsAppDecodeException.Part.Response, failure, Chunk(field), Absent)
                val checks = Kyo.foreach(Chunk(S, T)) { op =>
                    produce(op, failing(op, server.port, (ok, """{"messaging_product":"whatsapp","messages":[]}""")), local, bytesUrl)(_ =>
                        shape(op, WhatsAppDecodeException.Failure.MissingField, "messages")
                    )
                }.map { missing =>
                    Kyo.foreach(Chunk(R, X)) { op =>
                        produce(op, failing(op, server.port, (ok, """{"success":false}""")), local, bytesUrl)(_ =>
                            shape(op, WhatsAppDecodeException.Failure.ConstructorRejected, "success")
                        )
                    }.map(missing ++ _)
                }

                val webhookConfig = WhatsAppWebhookConfig(WhatsAppAppSecret("s"), WhatsAppVerifyToken("v"))
                val webhook       =
                    Abort.run[WhatsAppWebhookDecodeFailure](WhatsAppWebhook.decode(Span.from("\"x\"".getBytes("UTF-8")))).map {
                        decoded =>
                            val d = failureOf[WhatsAppDecodeException](decoded)
                            assert(d == WhatsAppDecodeException(
                                "webhook",
                                WhatsAppDecodeException.Part.Notification,
                                WhatsAppDecodeException.Failure.Parse,
                                Chunk.empty,
                                Present(0)
                            ))
                            val signatures: Chunk[WhatsAppException] = Chunk(
                                WhatsAppWebhook.verify(webhookConfig, Absent, Span.empty),
                                WhatsAppWebhook.verify(webhookConfig, Present("nope"), Span.empty),
                                WhatsAppWebhook.verify(webhookConfig, Present("sha256=" + "0" * 64), Span.empty)
                            ).map(failureOf[WhatsAppException](_))
                            assert(signatures == Chunk[WhatsAppException](
                                WhatsAppSignatureMissingException(),
                                WhatsAppSignatureMalformedException(),
                                WhatsAppSignatureMismatchException()
                            ))
                            signatures.map(e => (kindOf(e), G)) :+ (kindOf(d), N)
                    }

                for
                    g  <- graph
                    s  <- status
                    rf <- refused
                    t  <- transport
                    d  <- decode
                    c  <- checks
                    wh <- webhook
                yield
                    val produced = (g ++ s ++ rf ++ t ++ d ++ c ++ wh).toSet
                    assert(produced -- tablePairs == Set.empty, "pairs produced that the table does not admit")
                    assert(tablePairs -- produced == Set.empty, "pairs of the table no path produced")
                end for
            }
        }
    }

end WhatsAppExceptionMembershipTest

object WhatsAppExceptionMembershipTest:

    /** Operations, abbreviated as in the table. */
    enum Op derives CanEqual:
        case S, T, R, C, U, V, D, F, X, N, G

    /** One case per leaf class. */
    enum Leaf derives CanEqual:
        case OtherApi, TokenExpired, AccessDenied, AppRateLimit, BusinessAccountRateLimit, ThroughputRateLimit, RecipientPairRateLimit,
            Undeliverable, SenderIsRecipient, WindowClosed, TemplateNotFound, TemplateParameterCount, TemplateParameterFormat,
            TemplateContentPolicy, TemplatePaused, TemplateTextTooLong, MediaUpload, InvalidParameter, ServiceUnavailable, UnexpectedStatus,
            RefusedUrl, Transport, Decode, SignatureMissing, SignatureMalformed, SignatureMismatch
    end Leaf

    final case class CustomDto(value: String) derives Schema

    /** The leaf class of a failure a row can carry. Exhaustive over the sealed hierarchy but for the construction panics, which no row
      * admits, so a new leaf fails compilation here until the table names it.
      */
    def kindOf(e: WhatsAppException): Leaf = e match
        case _: WhatsAppOtherApiException                 => Leaf.OtherApi
        case _: WhatsAppTokenExpiredException             => Leaf.TokenExpired
        case _: WhatsAppAccessDeniedException             => Leaf.AccessDenied
        case _: WhatsAppAppRateLimitException             => Leaf.AppRateLimit
        case _: WhatsAppBusinessAccountRateLimitException => Leaf.BusinessAccountRateLimit
        case _: WhatsAppThroughputRateLimitException      => Leaf.ThroughputRateLimit
        case _: WhatsAppRecipientPairRateLimitException   => Leaf.RecipientPairRateLimit
        case _: WhatsAppUndeliverableException            => Leaf.Undeliverable
        case _: WhatsAppSenderIsRecipientException        => Leaf.SenderIsRecipient
        case _: WhatsAppWindowClosedException             => Leaf.WindowClosed
        case _: WhatsAppTemplateNotFoundException         => Leaf.TemplateNotFound
        case _: WhatsAppTemplateParameterCountException   => Leaf.TemplateParameterCount
        case _: WhatsAppTemplateParameterFormatException  => Leaf.TemplateParameterFormat
        case _: WhatsAppTemplateContentPolicyException    => Leaf.TemplateContentPolicy
        case _: WhatsAppTemplatePausedException           => Leaf.TemplatePaused
        case _: WhatsAppTemplateTextTooLongException      => Leaf.TemplateTextTooLong
        case _: WhatsAppMediaUploadException              => Leaf.MediaUpload
        case _: WhatsAppInvalidParameterException         => Leaf.InvalidParameter
        case _: WhatsAppServiceUnavailableException       => Leaf.ServiceUnavailable
        case _: WhatsAppUnexpectedStatusException         => Leaf.UnexpectedStatus
        case _: WhatsAppRefusedUrlException               => Leaf.RefusedUrl
        case _: WhatsAppTransportException                => Leaf.Transport
        case _: WhatsAppDecodeException                   => Leaf.Decode
        case _: WhatsAppSignatureMissingException         => Leaf.SignatureMissing
        case _: WhatsAppSignatureMalformedException       => Leaf.SignatureMalformed
        case _: WhatsAppSignatureMismatchException        => Leaf.SignatureMismatch
        case e @ (_: WhatsAppInvalidConfigException | _: WhatsAppInvalidTokenException | _: WhatsAppInvalidPathException |
            _: WhatsAppInvalidWebhookConfigException) =>
            throw new IllegalArgumentException(s"a construction panic is on no row: $e")

end WhatsAppExceptionMembershipTest

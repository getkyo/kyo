package kyo

import WhatsAppTransportException.Kind
import kyo.internal.whatsapp.Graph
import kyo.internal.whatsapp.Wire

class WhatsAppExceptionTest extends BaseWhatsAppTest:

    val token = WhatsAppToken("EAAG-SECRET-TOKEN")

    def dto(code: Int)(using Frame): Wire.ErrorDto =
        Wire.ErrorDto("msg", Present("OAuthException"), code, fbtrace_id = Present("T"))

    def leaf(code: Int)(using Frame): WhatsAppApiException = Graph.leafFor(token, "send", dto(code))

    "a Graph error body decodes every documented field into its leaf" in {
        val body =
            """{"error":{"message":"(#130429) Rate limit hit","type":"OAuthException","code":130429,"error_data":{"messaging_product":"whatsapp","details":"Cloud API message throughput has been reached."},"error_subcode":2494055,"fbtrace_id":"Az8or2yhqkZfEZ-_4Qn_Bam","error_user_title":"Slow down","error_user_msg":"Too many messages"}}"""
        assert(Json.decode[Wire.ErrorEnvelope](body).map(e => Graph.leafFor(token, "send", e.error)) ==
            Result.succeed(WhatsAppThroughputRateLimitException(
                "send",
                Present(2494055),
                "(#130429) Rate limit hit",
                Present("Cloud API message throughput has been reached."),
                Present("Az8or2yhqkZfEZ-_4Qn_Bam")
            )))
    }

    "a Graph leaf's message names the method, the code, the subcode, the description, the details and the trace id" in {
        val e = WhatsAppWindowClosedException("send", Present(2494010), "Re-engagement message", Present("24 hours passed"), Present("fb1"))
        assert(e.getMessage.contains(
            "WhatsApp send answered Graph error 131047, subcode 2494010: Re-engagement message (24 hours passed) [fbtrace_id fb1]"
        ))
    }

    "a Graph leaf without the optional fields shows only the method, the code and the description" in {
        val e = WhatsAppOtherApiException("custom", 131042, Absent, "Payment issue", Absent, Absent)
        assert(e.getMessage.contains("WhatsApp custom answered Graph error 131042: Payment issue"))
        assert(!e.getMessage.contains("subcode"))
        assert(!e.getMessage.contains("fbtrace_id"))
    }

    "every documented code maps to its leaf, carrying Meta's fields" in {
        assert(leaf(190) == WhatsAppTokenExpiredException("send", Absent, "msg", Absent, Present("T")))
        Seq(0, 3, 10, 200, 250, 299, 131005).foreach { code =>
            assert(leaf(code) == WhatsAppAccessDeniedException("send", code, Absent, "msg", Absent, Present("T")))
        }
        assert(leaf(4) == WhatsAppAppRateLimitException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(80007) == WhatsAppBusinessAccountRateLimitException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(130429) == WhatsAppThroughputRateLimitException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(131056) == WhatsAppRecipientPairRateLimitException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(131026) == WhatsAppUndeliverableException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(131021) == WhatsAppSenderIsRecipientException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(131047) == WhatsAppWindowClosedException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(132000) == WhatsAppTemplateParameterCountException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(132001) == WhatsAppTemplateNotFoundException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(132005) == WhatsAppTemplateTextTooLongException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(132007) == WhatsAppTemplateContentPolicyException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(132012) == WhatsAppTemplateParameterFormatException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(132015) == WhatsAppTemplatePausedException("send", Absent, "msg", Absent, Present("T")))
        assert(leaf(131053) == WhatsAppMediaUploadException("send", Absent, "msg", Absent, Present("T")))
        Seq(100, 131008, 131009, 135000).foreach { code =>
            assert(leaf(code) == WhatsAppInvalidParameterException("send", code, Absent, "msg", Absent, Present("T")))
        }
        Seq(131000, 131016).foreach { code =>
            assert(leaf(code) == WhatsAppServiceUnavailableException("send", code, Absent, "msg", Absent, Present("T")))
        }
    }

    "a code no leaf names, including the webhook-only 131052, is WhatsAppOtherApiException carrying it" in {
        Seq(131042, 131052, 199, 300, 1).foreach { code =>
            assert(leaf(code) == WhatsAppOtherApiException("send", code, Absent, "msg", Absent, Present("T")))
        }
    }

    "the token's value is redacted from the description and the details, wherever it occurs" in {
        val err = Wire.ErrorDto(
            s"Invalid OAuth access token - ${token.value} (${token.value})",
            code = 190,
            error_data = Present(Wire.ErrorDataDto(details = Present(s"token ${token.value} expired")))
        )
        val e = Graph.leafFor(token, "send", err)
        assert(e == WhatsAppTokenExpiredException(
            "send",
            Absent,
            "Invalid OAuth access token - <redacted> (<redacted>)",
            Present("token <redacted> expired"),
            Absent
        ))
        assert(!e.getMessage.contains(token.value))
    }

    "WhatsAppUnexpectedStatusException names the method and the status, and nothing else" in {
        assert(WhatsAppUnexpectedStatusException("downloadFrom", HttpStatus(404)).getMessage.contains(
            "WhatsApp downloadFrom answered HTTP 404 without a Graph error body."
        ))
    }

    "each reachable kyo-http failure is described by its kind, its timeout and kyo-net's cause" in {
        val net = kyo.net.NetConnectException("graph.facebook.com", 443, "refused")
        val tls = kyo.net.NetTlsHandshakeException("graph.facebook.com", 443, "bad certificate")
        val dns = kyo.net.NetDnsResolutionException("graph.facebook.com")
        assert(Graph.describe(HttpConnectException("graph.facebook.com", 443, net)) == (Kind.Connect, Absent, Present(net)))
        assert(Graph.describe(HttpConnectException("graph.facebook.com", 443, tls)) == (Kind.Tls, Absent, Present(tls)))
        assert(Graph.describe(HttpConnectException("graph.facebook.com", 443, new java.io.IOException("x"))) ==
            (Kind.Connect, Absent, Absent))
        assert(Graph.describe(HttpDnsResolutionException("graph.facebook.com", dns)) == (Kind.Dns, Absent, Present(dns)))
        assert(Graph.describe(HttpDnsResolutionException("graph.facebook.com", new java.io.IOException("x"))) == (Kind.Dns, Absent, Absent))
        assert(Graph.describe(HttpConnectTimeoutException("graph.facebook.com", 443, 3.seconds)) ==
            (Kind.ConnectTimeout, Present(3.seconds), Absent))
        assert(Graph.describe(HttpPoolExhaustedException("graph.facebook.com", 443, 8, summon[Frame])) ==
            (Kind.PoolExhausted(8), Absent, Absent))
        assert(Graph.describe(HttpTimeoutException(5.seconds, "POST", "https://graph.facebook.com/v25.0/1/messages")) ==
            (Kind.Timeout, Present(5.seconds), Absent))
        assert(Graph.describe(HttpConnectionClosedException()) == (Kind.ConnectionClosed, Absent, Absent))
        assert(Graph.describe(HttpProtocolException("bad head")) == (Kind.Protocol, Absent, Absent))
        assert(Graph.describe(HttpMalformedBodyException("bad chunk size")) == (Kind.Protocol, Absent, Absent))
        assert(Graph.describe(HttpPayloadTooLargeException(200, 100)) ==
            (Kind.PayloadTooLarge(ByteSize.fromBytes(200), ByteSize.fromBytes(100)), Absent, Absent))
    }

    "a kyo-http failure no call can produce is a module defect, raised by its name" in {
        val e = Result.catching[Throwable](Graph.describe(HttpBindException("localhost", 8080, new java.io.IOException("in use"))))
        assert(e.failure.map(_.getMessage).exists(_.contains("BUG HttpBindException reached a kyo-whatsapp call")))
    }

    "WhatsAppTransportException's message is its own text, with the timeout when one ran out" in {
        val closed  = WhatsAppTransportException("send", Kind.ConnectionClosed, "graph.facebook.com", 443, Absent)()
        val timeout = WhatsAppTransportException("upload", Kind.Timeout, "graph.facebook.com", 443, Present(10.seconds))()
        assert(closed.getMessage.contains(
            "WhatsApp send failed at the transport to graph.facebook.com:443: the connection closed before the response body."
        ))
        assert(timeout.getMessage.contains(
            s"WhatsApp upload failed at the transport to graph.facebook.com:443: no answer arrived after ${10.seconds.show}."
        ))
    }

    // Built apart from the construction line: a leaf's development-mode message renders the source lines around its frame.
    val causeText = Seq("CAUSE", "TEXT", "91b3").mkString("-")

    "WhatsAppTransportException chains kyo-net's cause through getCause and leaves it out of equality and the message" in {
        val net = kyo.net.NetConnectException("graph.facebook.com", 443, causeText)
        val t   = WhatsAppTransportException("send", Kind.Connect, "graph.facebook.com", 443, Absent)(Present(net))
        assert(t == WhatsAppTransportException("send", Kind.Connect, "graph.facebook.com", 443, Absent)())
        assert(t.getCause eq net)
        assert(!t.getMessage.contains(causeText))
        assert(WhatsAppTransportException("send", Kind.Connect, "graph.facebook.com", 443, Absent)().getCause == null)
    }

    "each kyo-schema decode leaf maps to its failure, its path, and a position only for a parse failure" in {
        import WhatsAppDecodeException.Failure
        import WhatsAppDecodeException.Part
        val path  = Seq("messages", "id")
        val cases = Chunk[(DecodeException, (Failure, Chunk[String], Maybe[Int]))](
            ParseException(Json(), "input", "diagnostic", path, 12)          -> (Failure.Parse, Chunk.from(path), Present(12)),
            ParseException(Json(), "input", "diagnostic")                    -> (Failure.Parse, Chunk.empty, Absent),
            MissingFieldException(path, "id")                                -> (Failure.MissingField, Chunk.from(path), Absent),
            TypeMismatchException(path, "String", "input")                   -> (Failure.TypeMismatch, Chunk.from(path), Absent),
            UnknownVariantException(path, "input")                           -> (Failure.UnknownVariant, Chunk.from(path), Absent),
            UnknownFieldException(path, "input")                             -> (Failure.UnknownField, Chunk.from(path), Absent),
            NoVariantMatchException(path, Chunk("A"))                        -> (Failure.NoVariantMatch, Chunk.from(path), Absent),
            AmbiguousVariantMatchException(path, Chunk("A", "B"))            -> (Failure.AmbiguousVariantMatch, Chunk.from(path), Absent),
            MissingTagKeyException(path, "type")                             -> (Failure.MissingTagKey, Chunk.from(path), Absent),
            ConstructorRejectedException(path, "Id", "input")                -> (Failure.ConstructorRejected, Chunk.from(path), Absent),
            TruncatedInputException(Json(), "input")                         -> (Failure.TruncatedInput, Chunk.empty, Absent),
            TrailingInputException(Json(), "input")                          -> (Failure.TrailingInput, Chunk.empty, Absent),
            RecordDecodeException(3, 120, MissingFieldException(path, "id")) -> (Failure.RecordDecode, Chunk.empty, Absent),
            LimitExceededException("depth", 600, 512)                        -> (Failure.LimitExceeded, Chunk.empty, Absent),
            RangeException(-1, "Long", 0, 10)                                -> (Failure.Range, Chunk.empty, Absent)
        )
        cases.foreach { case (schemaLeaf, (failure, leafPath, position)) =>
            assert(WhatsAppDecodeException.of("send", Part.Response, schemaLeaf) ==
                WhatsAppDecodeException("send", Part.Response, failure, leafPath, position))
        }
        succeed
    }

    "a decode failure's message names the method, the part, the failure, the path and the position, and it has no cause" in {
        import WhatsAppDecodeException.Failure
        import WhatsAppDecodeException.Part
        val e = WhatsAppDecodeException("webhook", Part.Notification, Failure.Parse, Chunk("entry", "0"), Present(46))
        assert(e.getMessage.contains("WhatsApp webhook notification did not decode: the JSON does not parse at entry.0, position 46."))
        assert(e.getCause == null)
    }

    "a decode failure holds no byte of the body it rejected, in its message, toString or cause chain" in {
        val planted = "PLANTED-BODY-SECRET"
        val result  = Json.decode[WhatsAppExceptionTest.IdDto](s"""{"id":$planted}""")
            .mapFailure(WhatsAppDecodeException.of("custom", WhatsAppDecodeException.Part.Response, _))
        val e        = failureOf[WhatsAppDecodeException](result)
        val rendered = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).flatMap(t => Seq(t.getMessage, t.toString)).toList
        assert(rendered.forall(!_.contains(planted)))
    }

    "a token refusal names the token and the problem, and not the value" in {
        import WhatsAppInvalidTokenException.*
        val e = WhatsAppInvalidTokenException(Token.AccessToken, Problem.InvalidCharacter(3))
        assert(e.getMessage.contains("WhatsAppToken is not usable: the character at position 3 is not allowed."))
    }

    "a path refusal names the problem, and not the path" in {
        import WhatsAppInvalidPathException.Problem
        assert(WhatsAppInvalidPathException(Problem.DotSegment(1)).getMessage.contains("WhatsAppPath is not usable: segment 1 is . or ..."))
    }

end WhatsAppExceptionTest

object WhatsAppExceptionTest:
    final case class IdDto(id: String) derives Schema

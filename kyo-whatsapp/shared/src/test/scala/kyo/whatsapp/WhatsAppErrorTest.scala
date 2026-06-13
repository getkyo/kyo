package kyo.whatsapp

import kyo.*
import kyo.whatsapp.WhatsAppError.*

class WhatsAppErrorTest extends BaseWhatsAppTest:

    "Cloud carries every envelope field" in {
        val e = Cloud(
            130429,
            Present(2494055),
            "OAuthException",
            "(#130429) Rate limit hit",
            Present("Cloud API message throughput has been reached."),
            Present("Az8or2")
        )
        assert(e.code == 130429)
        assert(e.subcode == Present(2494055))
        assert(e.errorType == "OAuthException")
        assert(e.message == "(#130429) Rate limit hit")
        assert(e.details == Present("Cloud API message throughput has been reached."))
        assert(e.fbtraceId == Present("Az8or2"))
        assert(e.getMessage == "(#130429) Rate limit hit")
    }

    "Cloud defaults subcode/details/fbtraceId to Absent" in {
        val e = Cloud(131000, errorType = "Unknown", message = "Something went wrong")
        assert(e.subcode == Absent)
        assert(e.details == Absent)
        assert(e.fbtraceId == Absent)
    }

    "AuthError leaves are distinct case objects under the trait" in {
        val te: WhatsAppError = AuthError.TokenExpired
        val ad: WhatsAppError = AuthError.AccessDenied
        assert(te.isInstanceOf[AuthError])
        assert(ad.isInstanceOf[AuthError])
        assert(te != ad)
        te match
            case AuthError.TokenExpired => ()
            case _                      => assert(false)
        ad match
            case AuthError.AccessDenied => ()
            case _                      => assert(false)
    }

    "RateLimited carries the Scope union" in {
        val e = RateLimited(130429, "Rate limit hit", RateLimited.Throughput)
        assert(e.scope == RateLimited.Throughput)
        assert(RateLimited.Throughput != RateLimited.Waba)
        assert(RateLimited.Waba != RateLimited.PhoneNumber)
        assert(RateLimited.PhoneNumber != RateLimited.Throughput)
    }

    "RecipientError has two distinct leaves" in {
        val u: WhatsAppError  = RecipientError.Undeliverable
        val se: WhatsAppError = RecipientError.SenderEqualsRecipient
        assert(u.isInstanceOf[RecipientError])
        assert(se.isInstanceOf[RecipientError])
        assert(u != se)
    }

    "WindowClosed is a case object with a documented message" in {
        val e: WhatsAppError = WhatsAppError.WindowClosed
        assert(e.getMessage.contains("24 hours"))
        assert(e.isInstanceOf[WhatsAppError])
    }

    "TemplateError has six distinct leaves" in {
        val leaves: Set[TemplateError] = Set(
            TemplateError.DoesNotExist,
            TemplateError.ParamCountMismatch,
            TemplateError.ParamFormatMismatch,
            TemplateError.ContentPolicy,
            TemplateError.Paused,
            TemplateError.TextTooLong
        )
        assert(leaves.size == 6)
        assert(leaves.forall(_.isInstanceOf[TemplateError]))
    }

    "MediaError has two distinct leaves" in {
        val d: MediaError = MediaError.DownloadFailed
        val u: MediaError = MediaError.UploadFailed
        assert(d != u)
        assert(d.isInstanceOf[MediaError])
        assert(u.isInstanceOf[MediaError])
    }

    "InvalidParameter and ServiceUnavailable carry code+message" in {
        val ip = InvalidParameter(100, "Invalid parameter")
        val su = ServiceUnavailable(131016, "Service unavailable")
        assert(ip.code == 100)
        assert(ip.message == "Invalid parameter")
        assert(su.code == 131016)
        assert(su.message == "Service unavailable")
    }

    "Transport wraps an HttpException and surfaces its message" in {
        val cause = HttpConnectException("graph.facebook.com", 443, new RuntimeException("refused"))
        val t     = WhatsAppError.Transport(cause)
        assert(t.cause eq cause)
        assert(t.getMessage.startsWith("transport failure:"))
    }

    "DecodeError carries its cause string" in {
        val e = DecodeError("unexpected end of input")
        assert(e.cause == "unexpected end of input")
        assert(e.getMessage == "unexpected end of input")
    }

    "SignatureError has three distinct leaves" in {
        val leaves: Set[SignatureError] = Set(
            SignatureError.Missing,
            SignatureError.Malformed,
            SignatureError.Mismatch
        )
        assert(leaves.size == 3)
        assert(leaves.forall(_.isInstanceOf[SignatureError]))
        assert(leaves.forall(_.isInstanceOf[WhatsAppError]))
    }

    "mapError decodes a Graph envelope and maps code 130429 to RateLimited.Throughput" in {
        val bodyJson =
            """{"error":{"message":"(#130429) Rate limit hit","type":"OAuthException","code":130429,"error_data":{"messaging_product":"whatsapp","details":"Cloud API message throughput has been reached."},"error_subcode":2494055,"fbtrace_id":"Az8or2yhqkZfEZ-_4Qn_Bam"}}"""
        val ex = HttpStatusException(HttpStatus(400), "POST", "https://graph.facebook.com/v25.0/123/messages", bodyJson)
        kyo.whatsapp.internal.Codec.mapError(ex) match
            case WhatsAppError.RateLimited(code, msg, scope) =>
                assert(code == 130429)
                assert(msg == "(#130429) Rate limit hit")
                assert(scope == WhatsAppError.RateLimited.Throughput)
            case r => assert(false, s"unexpected: $r")
        end match
    }

    "mapCode maps every documented error code to its named leaf" in {
        import kyo.whatsapp.internal.{Codec, Wire}
        import WhatsAppError.*
        def dto(code: Int) = Wire.ErrorDto("msg", "T", code)
        assert(Codec.mapCode(dto(190)) == AuthError.TokenExpired)
        assert(Codec.mapCode(dto(0)) == AuthError.AccessDenied)
        assert(Codec.mapCode(dto(10)) == AuthError.AccessDenied)
        assert(Codec.mapCode(dto(200)) == AuthError.AccessDenied)
        assert(Codec.mapCode(dto(131005)) == AuthError.AccessDenied)
        assert(Codec.mapCode(dto(130429)) == RateLimited(130429, "msg", RateLimited.Throughput))
        assert(Codec.mapCode(dto(80007)) == RateLimited(80007, "msg", RateLimited.Waba))
        assert(Codec.mapCode(dto(4)) == RateLimited(4, "msg", RateLimited.PhoneNumber))
        assert(Codec.mapCode(dto(131026)) == RecipientError.Undeliverable)
        assert(Codec.mapCode(dto(131021)) == RecipientError.SenderEqualsRecipient)
        assert(Codec.mapCode(dto(131047)) == WindowClosed)
        assert(Codec.mapCode(dto(132000)) == TemplateError.ParamCountMismatch)
        assert(Codec.mapCode(dto(132001)) == TemplateError.DoesNotExist)
        assert(Codec.mapCode(dto(132005)) == TemplateError.TextTooLong)
        assert(Codec.mapCode(dto(132007)) == TemplateError.ContentPolicy)
        assert(Codec.mapCode(dto(132012)) == TemplateError.ParamFormatMismatch)
        assert(Codec.mapCode(dto(132015)) == TemplateError.Paused)
        assert(Codec.mapCode(dto(131052)) == MediaError.DownloadFailed)
        assert(Codec.mapCode(dto(131053)) == MediaError.UploadFailed)
        assert(Codec.mapCode(dto(100)) == InvalidParameter(100, "msg"))
        assert(Codec.mapCode(dto(131008)) == InvalidParameter(131008, "msg"))
        assert(Codec.mapCode(dto(131009)) == InvalidParameter(131009, "msg"))
        assert(Codec.mapCode(dto(135000)) == InvalidParameter(135000, "msg"))
        assert(Codec.mapCode(dto(131000)) == ServiceUnavailable(131000, "msg"))
        assert(Codec.mapCode(dto(131016)) == ServiceUnavailable(131016, "msg"))
    }

    "unlisted code maps to Cloud preserving the full envelope" in {
        import kyo.whatsapp.internal.{Codec, Wire}
        val dto = Wire.ErrorDto(
            "Business eligibility error",
            "OAuthException",
            131042,
            Present(99999),
            Present(Wire.ErrorDataDto(Present("whatsapp"), Present("payment method issue"))),
            Present("trace123")
        )
        Codec.mapCode(dto) match
            case WhatsAppError.Cloud(code, subcode, errorType, message, details, fbtraceId) =>
                assert(code == 131042)
                assert(subcode == Present(99999))
                assert(errorType == "OAuthException")
                assert(message == "Business eligibility error")
                assert(details == Present("payment method issue"))
                assert(fbtraceId == Present("trace123"))
            case r => assert(false, s"unexpected: $r")
        end match
    }

    "a non-HttpStatusException maps to Transport" in {
        val cause = HttpConnectException("graph.facebook.com", 443, new RuntimeException("refused"))
        kyo.whatsapp.internal.Codec.mapError(cause) match
            case WhatsAppError.Transport(e) => assert(e eq cause)
            case r                          => assert(false, s"unexpected: $r")
    }

    "Transport wraps a raw IOException cause (not only HttpException)" in {
        val io = new java.io.IOException("connection closed")
        val t  = WhatsAppError.Transport(io)
        assert(t.cause eq io)
        assert(t.getMessage.startsWith("transport failure:"))
    }

    "mapTransportPanic maps IOException to Transport failure" in {
        val io = new java.io.IOException("connection closed")
        kyo.whatsapp.internal.Codec.mapTransportPanic(io) match
            case Result.Failure(WhatsAppError.Transport(e)) => assert(e eq io)
            case r                                          => assert(false, s"expected Transport failure, got: $r")
    }

    "mapTransportPanic re-panics non-IOException throwables" in {
        val npe = new NullPointerException("internal bug")
        kyo.whatsapp.internal.Codec.mapTransportPanic(npe) match
            case Result.Panic(e) => assert(e eq npe)
            case r               => assert(false, s"expected Panic, got: $r")
    }

    "mapTransportPanic maps IOException subclasses to Transport failure" in {
        val eof = new java.io.EOFException("connection closed unexpectedly")
        kyo.whatsapp.internal.Codec.mapTransportPanic(eof) match
            case Result.Failure(WhatsAppError.Transport(e)) => assert(e eq eof)
            case r                                          => assert(false, s"expected Transport failure, got: $r")
    }

end WhatsAppErrorTest

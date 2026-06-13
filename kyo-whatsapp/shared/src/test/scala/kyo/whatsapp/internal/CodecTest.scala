package kyo.whatsapp.internal

import kyo.*
import kyo.whatsapp.*
import kyo.whatsapp.BaseWhatsAppTest
import kyo.whatsapp.WhatsApp.SendResult
import kyo.whatsapp.WhatsApp.SendResult.Status
import kyo.whatsapp.internal.Codec as WhatsAppCodec
import kyo.whatsapp.internal.Wire

class CodecTest extends BaseWhatsAppTest:

    "decodeSendResult parses E response with message_status accepted" in {
        val bytes =
            Span.from("""{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"16505551234"}],"messages":[{"id":"wamid.X","message_status":"accepted"}]}""".getBytes(
                "UTF-8"
            ))
        val result = WhatsAppCodec.decodeSendResult(bytes)
        result match
            case Result.Success(r) =>
                assert(r.messageId == Id.MessageId("wamid.X"))
                assert(r.contactWaId == Present(Id.WhatsAppId("16505551234")))
                assert(r.status == Present(Status.Accepted))
            case _ => assert(false, s"unexpected: $result")
        end match
    }

    "decodeSendResult maps all three message_status enum values" in {
        def decode(status: String): Result[WhatsAppError.DecodeError, SendResult] =
            val bytes = Span.from(
                s"""{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"W"}],"messages":[{"id":"wamid.X","message_status":"$status"}]}""".getBytes(
                    "UTF-8"
                )
            )
            WhatsAppCodec.decodeSendResult(bytes)
        end decode
        decode("accepted") match
            case Result.Success(r) => assert(r.status == Present(Status.Accepted))
            case r                 => assert(false, s"accepted: $r")
        decode("held_for_quality_assessment") match
            case Result.Success(r) => assert(r.status == Present(Status.HeldForQualityAssessment))
            case r                 => assert(false, s"held: $r")
        decode("paused") match
            case Result.Success(r) => assert(r.status == Present(Status.Paused))
            case r                 => assert(false, s"paused: $r")
    }

    "decodeSendResult with no message_status yields status Absent" in {
        val bytes = Span.from(
            """{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"W"}],"messages":[{"id":"wamid.Y"}]}""".getBytes("UTF-8")
        )
        WhatsAppCodec.decodeSendResult(bytes) match
            case Result.Success(r) =>
                assert(r.messageId == Id.MessageId("wamid.Y"))
                assert(r.contactWaId == Present(Id.WhatsAppId("W")))
                assert(r.status == Absent)
            case r => assert(false, s"unexpected: $r")
        end match
    }

    "decodeSendResult with no contacts yields contactWaId Absent" in {
        val bytes = Span.from("""{"messaging_product":"whatsapp","messages":[{"id":"wamid.Z"}]}""".getBytes("UTF-8"))
        WhatsAppCodec.decodeSendResult(bytes) match
            case Result.Success(r) =>
                assert(r.messageId == Id.MessageId("wamid.Z"))
                assert(r.contactWaId == Absent)
                assert(r.status == Absent)
            case r => assert(false, s"unexpected: $r")
        end match
    }

    "decodeSendResult fails on empty messages array" in {
        val bytes = Span.from("""{"messaging_product":"whatsapp","messages":[]}""".getBytes("UTF-8"))
        WhatsAppCodec.decodeSendResult(bytes) match
            case Result.Failure(e: WhatsAppError.DecodeError) =>
                assert(e.getMessage.contains("no messages"))
            case r => assert(false, s"expected DecodeError, got: $r")
        end match
    }

    "decodeSuccess parses success:true to Unit" in {
        val bytes = Span.from("""{"success":true}""".getBytes("UTF-8"))
        WhatsAppCodec.decodeSuccess(bytes) match
            case Result.Success(()) => succeed
            case r                  => assert(false, s"unexpected: $r")
    }

    "decodeSuccess rejects success:false as a typed DecodeError" in {
        val bytes = Span.from("""{"success":false}""".getBytes("UTF-8"))
        WhatsAppCodec.decodeSuccess(bytes) match
            case Result.Failure(e: WhatsAppError.DecodeError) =>
                assert(e.cause.contains("false"))
            case r => assert(false, s"expected DecodeError for success:false, got: $r")
        end match
    }

    "sendStatus maps an unrecognized value to Status.Other" in {
        val bytes = Span.from(
            """{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"W"}],"messages":[{"id":"wamid.X","message_status":"pending_review"}]}""".getBytes(
                "UTF-8"
            )
        )
        WhatsAppCodec.decodeSendResult(bytes) match
            case Result.Success(r) => assert(r.status == Present(Status.Other("pending_review")))
            case r                 => assert(false, s"unexpected: $r")
    }

    "decodeMediaId parses id field to MediaId" in {
        val bytes = Span.from("""{"id":"2762702944112137"}""".getBytes("UTF-8"))
        WhatsAppCodec.decodeMediaId(bytes) match
            case Result.Success(id) => assert(id == Id.MediaId("2762702944112137"))
            case r                  => assert(false, s"unexpected: $r")
    }

    "a structurally-broken response yields DecodeError" in {
        val bytes = Span.from("""{"messaging_product":""".getBytes("UTF-8"))
        WhatsAppCodec.decodeSendResult(bytes) match
            case Result.Failure(_: WhatsAppError.DecodeError) => succeed
            case r                                            => assert(false, s"expected DecodeError, got: $r")
    }

    "encodeMarkRead without typing produces the mark-as-read shape" in {
        val bytes = WhatsAppCodec.encodeMarkRead(Id.MessageId("wamid.MSG"), typing = false)
        val json  = new String(bytes.toArray, "UTF-8")
        assert(json.contains("\"messaging_product\":\"whatsapp\""))
        assert(json.contains("\"status\":\"read\""))
        assert(json.contains("\"message_id\":\"wamid.MSG\""))
        assert(!json.contains("typing_indicator"))
    }

end CodecTest

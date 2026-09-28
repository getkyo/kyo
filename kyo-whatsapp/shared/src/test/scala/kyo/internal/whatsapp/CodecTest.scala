package kyo.internal.whatsapp

import kyo.*
import kyo.BaseWhatsAppTest
import kyo.WhatsAppDecodeException.Failure
import kyo.WhatsAppDecodeException.Part
import kyo.WhatsAppSendResult
import kyo.WhatsAppSendResult.Status
import kyo.internal.whatsapp.Codec as WhatsAppCodec

class CodecTest extends BaseWhatsAppTest:

    def bytesOf(json: String): Span[Byte] = Span.from(json.getBytes("UTF-8"))

    def sendResult(json: String)(using Frame): Result[WhatsAppException, WhatsAppSendResult] =
        WhatsAppCodec.decodeSendResult("send", bytesOf(json))

    def response(method: String, failure: Failure, path: Chunk[String], position: Maybe[Int] = Absent)(using
        Frame
    ): WhatsAppDecodeException =
        WhatsAppDecodeException(method, Part.Response, failure, path, position)

    "decodeSendResult parses E response with message_status accepted" in {
        assert(sendResult(
            """{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"16505551234"}],"messages":[{"id":"wamid.X","message_status":"accepted"}]}"""
        ) == Result.succeed(WhatsAppSendResult(
            WhatsAppId.MessageId("wamid.X"),
            Present(WhatsAppId.WaId("16505551234")),
            Present(Status.Accepted)
        )))
    }

    "decodeSendResult maps all three message_status enum values" in {
        def decode(status: String): Result[WhatsAppException, WhatsAppSendResult] =
            sendResult(
                s"""{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"W"}],"messages":[{"id":"wamid.X","message_status":"$status"}]}"""
            )
        def sent(status: Status): Result[WhatsAppException, WhatsAppSendResult] =
            Result.succeed(WhatsAppSendResult(WhatsAppId.MessageId("wamid.X"), Present(WhatsAppId.WaId("W")), Present(status)))
        assert(decode("accepted") == sent(Status.Accepted))
        assert(decode("held_for_quality_assessment") == sent(Status.HeldForQualityAssessment))
        assert(decode("paused") == sent(Status.Paused))
        assert(decode("queued") == sent(Status.Other("queued")))
    }

    "decodeSendResult with no message_status yields status Absent" in {
        assert(sendResult("""{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"W"}],"messages":[{"id":"wamid.Y"}]}""") ==
            Result.succeed(WhatsAppSendResult(WhatsAppId.MessageId("wamid.Y"), Present(WhatsAppId.WaId("W")), Absent)))
    }

    "decodeSendResult with no contacts yields contactWaId Absent" in {
        assert(sendResult("""{"messaging_product":"whatsapp","messages":[{"id":"wamid.Z"}]}""") ==
            Result.succeed(WhatsAppSendResult(WhatsAppId.MessageId("wamid.Z"), Absent, Absent)))
    }

    "a send response with an empty messages array is a decode failure at messages, naming the method" in {
        val empty = bytesOf("""{"messaging_product":"whatsapp","messages":[]}""")
        assert(WhatsAppCodec.decodeSendResult("send", empty) == Result.fail(response("send", Failure.MissingField, Chunk("messages"))))
        assert(WhatsAppCodec.decodeSendResult("sendTemplate", empty) ==
            Result.fail(response("sendTemplate", Failure.MissingField, Chunk("messages"))))
    }

    "decodeSuccess parses success:true to Unit" in {
        assert(WhatsAppCodec.decodeSuccess("markRead", bytesOf("""{"success":true}""")) == Result.unit)
    }

    "success:false is a decode failure at success, naming the method" in {
        val nack = bytesOf("""{"success":false}""")
        assert(WhatsAppCodec.decodeSuccess("markRead", nack) ==
            Result.fail(response("markRead", Failure.ConstructorRejected, Chunk("success"))))
        assert(WhatsAppCodec.decodeSuccess("delete", nack) ==
            Result.fail(response("delete", Failure.ConstructorRejected, Chunk("success"))))
    }

    "sendStatus maps an unrecognized value to Status.Other" in {
        assert(sendResult(
            """{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"W"}],"messages":[{"id":"wamid.X","message_status":"pending_review"}]}"""
        ) == Result.succeed(
            WhatsAppSendResult(WhatsAppId.MessageId("wamid.X"), Present(WhatsAppId.WaId("W")), Present(Status.Other("pending_review")))
        ))
    }

    "decodeMediaId parses id field to MediaId" in {
        assert(WhatsAppCodec.decodeMediaId("upload", bytesOf("""{"id":"2762702944112137"}""")) ==
            Result.succeed(WhatsAppId.MediaId("2762702944112137")))
    }

    "a structurally-broken response is a Parse decode failure at its position, with no cause" in {
        val e = failureOf[WhatsAppDecodeException](sendResult("""{"messaging_product":"""))
        assert(e == response("send", Failure.Parse, Chunk.empty, Present(21)))
        assert(e.getCause == null)
    }

    "a response missing a required field is a MissingField decode failure" in {
        val e = failureOf[WhatsAppDecodeException](
            sendResult("""{"messaging_product":"whatsapp","messages":[{"message_status":"accepted"}]}""")
        )
        assert(e == response("send", Failure.MissingField, Chunk.empty))
    }

    "a response with more elements than kyo-schema's collection limit is a LimitExceeded decode failure" in {
        val e = failureOf[WhatsAppDecodeException](
            sendResult("""{"messages":[""" + Seq.fill(100001)("""{"id":"a"}""").mkString(",") + "]}")
        )
        assert(e == response("send", Failure.LimitExceeded, Chunk.empty))
    }

    def mediaInfoWith(fileSize: String, url: String = "https://lookaside.fbsbx.com/x")(using
        Frame
    ): Result[WhatsAppException, WhatsAppMedia.MediaInfo] =
        WhatsAppCodec.decodeMediaInfo(
            "resolveUrl",
            bytesOf(
                s"""{"messaging_product":"whatsapp","url":"$url","mime_type":"image/png","sha256":"h","file_size":$fileSize,"id":"M"}"""
            )
        )

    def assertRejectedSizeString(text: String)(using Frame, kyo.test.AssertScope) =
        val e = failureOf[WhatsAppDecodeException](mediaInfoWith(s"\"$text\""))
        assert(e == response("resolveUrl", Failure.Parse, Chunk.empty))
    end assertRejectedSizeString

    "a media info file_size string that is not a number is a Parse decode failure" in {
        assertRejectedSizeString("big")
    }

    "a file_size string with a minus sign is a Parse decode failure" in {
        assertRejectedSizeString("-5")
    }

    "a file_size string with a plus sign is a Parse decode failure" in {
        assertRejectedSizeString("+5")
    }

    "a file_size string of Arabic-Indic digits is a Parse decode failure" in {
        assertRejectedSizeString("١٢٣")
    }

    "a file_size string of ASCII digits past the largest Long is a Parse decode failure" in {
        assertRejectedSizeString("9223372036854775808")
    }

    "a negative file_size number is a Range decode failure" in {
        val e = failureOf[WhatsAppDecodeException](mediaInfoWith("-5"))
        assert(e == response("resolveUrl", Failure.Range, Chunk.empty))
    }

    "file_size accepts zero and the largest Long, as a string or a number" in {
        val url              = HttpUrl.parse("https://lookaside.fbsbx.com/x").getOrThrow
        def info(size: Long) =
            Result.succeed(WhatsAppMedia.MediaInfo(WhatsAppId.MediaId("M"), url, "image/png", "h", ByteSize.fromBytes(size)))
        assert(mediaInfoWith("\"0\"") == info(0L))
        assert(mediaInfoWith("0") == info(0L))
        assert(mediaInfoWith("\"12345\"") == info(12345L))
        assert(mediaInfoWith("12345") == info(12345L))
        assert(mediaInfoWith(s"\"${Long.MaxValue}\"") == info(Long.MaxValue))
        assert(mediaInfoWith(s"${Long.MaxValue}") == info(Long.MaxValue))
    }

    "a media info url that does not parse is a ConstructorRejected decode failure at url" in {
        val e = failureOf[WhatsAppDecodeException](mediaInfoWith("1", url = "ftp://lookaside.fbsbx.com/x"))
        assert(e == response("resolveUrl", Failure.ConstructorRejected, Chunk("url")))
    }

    "a decode failure's message names the method, the part and the failure" in {
        val e = failureOf[WhatsAppDecodeException](WhatsAppCodec.decodeSuccess("markRead", bytesOf("""{"ok":1}""")))
        assert(e == response("markRead", Failure.MissingField, Chunk.empty))
        assert(e.getMessage.contains("WhatsApp markRead response did not decode: a required field is missing."))
    }

    "encodeMarkRead without typing produces the mark-as-read shape" in {
        val bytes = WhatsAppCodec.encodeMarkRead(WhatsAppId.MessageId("wamid.MSG"), typing = false)
        val json  = new String(bytes.toArray, "UTF-8")
        assert(json == """{"messaging_product":"whatsapp","status":"read","message_id":"wamid.MSG"}""")
    }

end CodecTest

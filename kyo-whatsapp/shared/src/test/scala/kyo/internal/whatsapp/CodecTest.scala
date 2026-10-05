package kyo.internal.whatsapp

import kyo.*
import kyo.BaseWhatsAppTest
import kyo.WhatsAppDecodeException.Failure
import kyo.WhatsAppDecodeException.Part
import kyo.WhatsAppSendResult
import kyo.WhatsAppSendResult.Status
import kyo.internal.whatsapp.Codec as WhatsAppCodec

class CodecTest extends BaseWhatsAppTest:

    def bytesOf(json: String): Span[Byte] = utf8(json)

    def sendResult(json: String)(using Frame): Result[WhatsAppException, WhatsAppSendResult] =
        WhatsAppCodec.decodeSendResult("send", bytesOf(json))

    def response(method: String, failure: Failure, path: Chunk[String], position: Maybe[Int] = Absent)(using
        Frame
    ): WhatsAppDecodeException =
        WhatsAppDecodeException(method, Part.Response, failure, path, position)

    def result(
        id: String,
        contacts: Chunk[WhatsAppSendResult.Contact],
        status: Maybe[Status]
    )(using Frame): Result[WhatsAppException, WhatsAppSendResult] =
        Result.succeed(sendResultOf(contacts, Chunk(WhatsAppSendResult.Message(WhatsAppId.MessageId(id), status))))

    "Meta's send answer decodes to the result and encodes back to the same JSON" in {
        val metaAnswer =
            """{"messaging_product":"whatsapp","contacts":[{"input":"+16505551234","wa_id":"16505551234"}],
              |"messages":[{"id":"wamid.HBgLMTY0NjcwNDM1OTUVAgARGBI1RjQyNUE3NEYxMzAzMzQ5MkEA"}]}""".stripMargin
        val expected = result(
            "wamid.HBgLMTY0NjcwNDM1OTUVAgARGBI1RjQyNUE3NEYxMzAzMzQ5MkEA",
            Chunk(WhatsAppSendResult.Contact(Present("+16505551234"), Present(WhatsAppId.WaId("16505551234")))),
            Absent
        )
        assert(sendResult(metaAnswer) == expected)
        assert(unordered(Json.encode(expected.getOrThrow)) == unordered(metaAnswer.replace(""""messaging_product":"whatsapp",""", "")))
        assert(expected.map(r => (r.messageId, r.contactWaId, r.status)) == Result.succeed((
            WhatsAppId.MessageId("wamid.HBgLMTY0NjcwNDM1OTUVAgARGBI1RjQyNUE3NEYxMzAzMzQ5MkEA"),
            Present(WhatsAppId.WaId("16505551234")),
            Absent
        )))
    }

    "decodeSendResult maps all three message_status enum values, and any other to Other" in {
        def decode(status: String): Result[WhatsAppException, WhatsAppSendResult] =
            sendResult(
                s"""{"messaging_product":"whatsapp","contacts":[{"input":"P","wa_id":"W"}],"messages":[{"id":"wamid.X","message_status":"$status"}]}"""
            )
        def sent(status: Status): Result[WhatsAppException, WhatsAppSendResult] =
            result("wamid.X", Chunk(WhatsAppSendResult.Contact(Present("P"), Present(WhatsAppId.WaId("W")))), Present(status))
        assert(decode("accepted") == sent(Status.Accepted))
        assert(decode("held_for_quality_assessment") == sent(Status.HeldForQualityAssessment))
        assert(decode("paused") == sent(Status.Paused))
        assert(decode("queued") == sent(Status.Other("queued")))
    }

    "decodeSendResult with no contacts yields contactWaId Absent" in {
        assert(sendResult("""{"messaging_product":"whatsapp","messages":[{"id":"wamid.Z"}]}""") == result("wamid.Z", Chunk.empty, Absent))
        assert(sendResult("""{"messaging_product":"whatsapp","messages":[{"id":"wamid.Z"}]}""").map(_.contactWaId) ==
            Result.succeed(Absent))
    }

    "a send answer with no message is refused by its constructor, naming the method" in {
        val empty = bytesOf("""{"messaging_product":"whatsapp","messages":[]}""")
        assert(WhatsAppCodec.decodeSendResult("send", empty) == Result.fail(response("send", Failure.ConstructorRejected, Chunk.empty)))
        assert(WhatsAppCodec.decodeSendResult("sendTemplate", empty) ==
            Result.fail(response("sendTemplate", Failure.ConstructorRejected, Chunk.empty)))
        assert(WhatsAppSendResult.init(Chunk.empty, Chunk.empty).isFailure)
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

    "decodeMediaId parses id field to MediaId" in {
        assert(WhatsAppCodec.decodeMediaId("upload", bytesOf("""{"id":"2762702944112137"}""")) ==
            Result.succeed(WhatsAppId.MediaId("2762702944112137")))
    }

    "a structurally-broken response is a Parse decode failure at its position, with no cause" in {
        val e = failureOf[WhatsAppDecodeException](sendResult("""{"messaging_product":}"""))
        assert(e == response("send", Failure.Parse, Chunk.empty, Present(21)))
        assert(e.getCause == null)
    }

    "a response cut off before its end is a TruncatedInput decode failure, with no cause" in {
        val e = failureOf[WhatsAppDecodeException](sendResult("""{"messaging_product":"""))
        assert(e == response("send", Failure.TruncatedInput, Chunk.empty))
        assert(e.getCause == null)
    }

    "a response missing a required field is a MissingField decode failure at the object that lacks it" in {
        val e = failureOf[WhatsAppDecodeException](
            sendResult("""{"messaging_product":"whatsapp","messages":[{"message_status":"accepted"}]}""")
        )
        assert(e == response("send", Failure.MissingField, Chunk("messages", "0")))
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

    def assertRejectedSize(json: String)(using Frame, kyo.test.AssertScope) =
        val e = failureOf[WhatsAppDecodeException](mediaInfoWith(json))
        assert(e == response("resolveUrl", Failure.ConstructorRejected, Chunk("file_size")), s"for $json: ${e.show}")
    end assertRejectedSize

    "a media info file_size string that is not a number is a ConstructorRejected decode failure" in {
        assertRejectedSize("\"big\"")
    }

    "a file_size string with a minus sign is refused by its constructor" in {
        assertRejectedSize("\"-5\"")
    }

    "a file_size string with a plus sign is refused by its constructor" in {
        assertRejectedSize("\"+5\"")
    }

    "a file_size string of Arabic-Indic digits is refused by its constructor" in {
        assertRejectedSize("\"١٢٣\"")
    }

    "a file_size of ASCII digits past the largest Long, as a string or a number, is refused by its constructor" in {
        assertRejectedSize("\"9223372036854775808\"")
        assertRejectedSize("9223372036854775808")
    }

    "a negative, fractional, or non-numeric file_size is refused by its constructor" in {
        assertRejectedSize("-5")
        assertRejectedSize("1.5")
        assertRejectedSize("true")
        assertRejectedSize("null")
    }

    "file_size accepts zero and the largest Long, as a string or a number" in {
        val url              = HttpUrl.parse("https://lookaside.fbsbx.com/x").getOrThrow
        def info(size: Long) =
            Result.succeed(WhatsAppMedia.MediaInfo(
                WhatsAppId.MediaId("M"),
                WhatsAppMediaUrl(url),
                "image/png",
                "h",
                ByteSize.fromBytes(size)
            ))
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

    "encodeMarkRead is Meta's mark-as-read body, with the typing indicator when asked" in {
        def body(typing: Boolean) =
            unordered(textOf(WhatsAppCodec.encodeMarkRead(WhatsAppId.MessageId("wamid.MSG"), typing)))
        assert(body(false) == unordered("""{"messaging_product":"whatsapp","status":"read","message_id":"wamid.MSG"}"""))
        assert(body(true) ==
            unordered("""{"messaging_product":"whatsapp","status":"read","message_id":"wamid.MSG","typing_indicator":{"type":"text"}}"""))
    }

end CodecTest

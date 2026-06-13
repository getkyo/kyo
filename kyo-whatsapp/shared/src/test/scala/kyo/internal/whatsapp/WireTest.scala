package kyo.internal.whatsapp

import kyo.*
import kyo.BaseWhatsAppTest

class WireTest extends BaseWhatsAppTest:

    "Absent body field is omitted from JSON" in {
        val env  = Wire.SendEnvelope("whatsapp", Present("individual"), "P", "text", text = Present(Wire.TextBody("hi")))
        val json = Json.encode(env)
        assert(json.contains("\"text\""))
        assert(!json.contains("\"image\""))
        assert(!json.contains("\"video\""))
        assert(!json.contains("\"document\""))
        assert(!json.contains("\"audio\""))
        assert(!json.contains("\"sticker\""))
        assert(!json.contains("\"location\""))
        assert(!json.contains("\"contacts\""))
        assert(!json.contains("\"reaction\""))
        assert(!json.contains("\"interactive\""))
        assert(!json.contains("\"template\""))
        assert(!json.contains("\"context\""))
        assert(!json.contains("null"))
        assert(json == """{"messaging_product":"whatsapp","recipient_type":"individual","to":"P","type":"text","text":{"body":"hi"}}""")
    }

    "snake_case field names produce messaging_product and recipient_type" in {
        val env  = Wire.SendEnvelope("whatsapp", Present("individual"), "P", "text", text = Present(Wire.TextBody("hi")))
        val json = Json.encode(env)
        assert(json.contains("\"messaging_product\""))
        assert(json.contains("\"recipient_type\""))
    }

    "TextBody emits preview_url when true" in {
        val tb   = Wire.TextBody("link here", preview_url = Present(true))
        val json = Json.encode(tb)
        assert(json == """{"body":"link here","preview_url":true}""")
    }

    "MediaBody emits only id when set by id, only link when set by link" in {
        val byId     = Wire.MediaBody(id = Present("M"))
        val byLink   = Wire.MediaBody(link = Present("https://x"))
        val jsonId   = Json.encode(byId)
        val jsonLink = Json.encode(byLink)
        assert(jsonId == """{"id":"M"}""")
        assert(jsonLink == """{"link":"https://x"}""")
    }

    "ReactionBody uses message_id and keeps empty emoji" in {
        val r    = Wire.ReactionBody("wamid.X", "")
        val json = Json.encode(r)
        assert(json == """{"message_id":"wamid.X","emoji":""}""")
    }

    "ErrorDto uses the three snake_case field names and round-trips" in {
        val dto = Wire.ErrorDto(
            "(#130429) Rate limit hit",
            "OAuthException",
            130429,
            Present(2494055),
            Present(Wire.ErrorDataDto(Present("whatsapp"), Present("..."))),
            Present("Az8or2")
        )
        val json = Json.encode(dto)
        assert(json.contains("\"error_subcode\""))
        assert(json.contains("\"error_data\""))
        assert(json.contains("\"fbtrace_id\""))
        val decoded = Json.decode[Wire.ErrorDto](json)
        decoded match
            case Result.Success(v) =>
                assert(v.message == dto.message)
                assert(v.code == dto.code)
                assert(v.error_subcode == dto.error_subcode)
                assert(v.fbtrace_id == dto.fbtrace_id)
            case _ => assert(false, s"decode failed: $decoded")
        end match
    }

    "MediaInfoResponse decodes file_size from a JSON string" in {
        val bytes = Span.from(
            """{"messaging_product":"whatsapp","url":"u","mime_type":"image/jpeg","sha256":"h","file_size":"12345","id":"M"}""".getBytes(
                "UTF-8"
            )
        )
        val result = Json.decodeBytes[Wire.MediaInfoResponse](bytes)
        result match
            case Result.Success(r) =>
                assert(r.file_size.value == "12345")
                assert(r.mime_type == "image/jpeg")
            case _ => assert(false, s"decode failed: $result")
        end match
    }

    "MediaInfoResponse decodes file_size from a JSON number" in {
        val bytes = Span.from(
            """{"messaging_product":"whatsapp","url":"u","mime_type":"image/jpeg","sha256":"h","file_size":12345,"id":"M"}""".getBytes(
                "UTF-8"
            )
        )
        val result = Json.decodeBytes[Wire.MediaInfoResponse](bytes)
        result match
            case Result.Success(r) =>
                assert(r.file_size.value == "12345")
            case _ => assert(false, s"decode failed for numeric file_size: $result")
        end match
    }

end WireTest

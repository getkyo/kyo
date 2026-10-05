package kyo.internal.whatsapp

import kyo.*

class MethodsTest extends kyo.BaseWhatsAppTest:

    "markRead's body is the Cloud API's read status, with the typing indicator only when asked for" in {
        val id = WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBJDQjZCMzlEQUE4OTJBMTE4RTUA")
        assert(Json.encode(Methods.MarkRead(id, typing = false)) ==
            s"""{"messaging_product":"whatsapp","status":"read","message_id":"${id.value}"}""")
        assert(Json.encode(Methods.MarkRead(id, typing = true)) ==
            s"""{"messaging_product":"whatsapp","status":"read","message_id":"${id.value}","typing_indicator":{"type":"text"}}""")
    }

    "the success answer and the upload answer decode from the Graph API's documented answers" in {
        assert(Json.decode[Methods.SuccessAnswer]("""{"success":true}""") == Result.succeed(Methods.SuccessAnswer(true)))
        assert(Json.decode[Methods.UploadAnswer]("""{"id":"1037543291543636"}""") ==
            Result.succeed(Methods.UploadAnswer(WhatsAppId.MediaId("1037543291543636"))))
    }

    "an upload answer without its id fails to decode, naming the key Meta left out" in {
        val result = Json.decode[Methods.UploadAnswer]("""{"messaging_product":"whatsapp"}""")
        assert(result.failure.exists { case e: MissingFieldException => e.fieldName == "id"; case _ => false }, result.toString)
    }

end MethodsTest

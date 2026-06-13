package kyo

import kyo.WhatsAppId.*

class WhatsAppMessageTest extends BaseWhatsAppTest:

    "Text carries body and previewUrl with a false default" in {
        val t1 = WhatsAppMessage.Text("hi")
        val t2 = WhatsAppMessage.Text("hi", previewUrl = true)
        assert(t1.body == "hi")
        assert(t1.previewUrl == false)
        assert(t2.body == "hi")
        assert(t2.previewUrl == true)
    }

    "Image/Video/Document/Audio/Sticker hold a Source" in {
        val src = WhatsAppMedia.Source.ById(WhatsAppId.MediaId("m"))
        val img = WhatsAppMessage.Image(src)
        val vid = WhatsAppMessage.Video(src)
        val doc = WhatsAppMessage.Document(src)
        val aud = WhatsAppMessage.Audio(src)
        val stk = WhatsAppMessage.Sticker(src)
        assert(img.source == src)
        assert(vid.source == src)
        assert(doc.source == src)
        assert(doc.filename == Absent)
        assert(aud.source == src)
        assert(stk.source == src)
    }

    "Location holds numeric lat/long and optional name/address" in {
        val loc = WhatsAppMessage.Location(37.42, -122.08, Present("HQ"), Absent)
        assert(loc.latitude == 37.42)
        assert(loc.longitude == -122.08)
        assert(loc.name == Present("HQ"))
        assert(loc.address == Absent)
    }

    "Reaction carries a MessageId and an emoji, empty allowed" in {
        val r1 = WhatsAppMessage.Reaction(WhatsAppId.MessageId("wamid.X"), "")
        val r2 = WhatsAppMessage.Reaction(WhatsAppId.MessageId("wamid.X"), "😀")
        assert(r1.emoji == "")
        assert(r2.emoji == "😀")
    }

    "Contacts wraps a Chunk[Contact] and OfInteractive wraps WhatsAppInteractive" in {
        val aContact = WhatsAppContact(WhatsAppContact.Name("Alice"))
        val aButtons = WhatsAppInteractive.Buttons(Chunk(WhatsAppInteractive.ReplyButton("yes", "Yes")))
        val contacts = WhatsAppMessage.Contacts(Chunk(aContact))
        val ofInter  = WhatsAppMessage.OfInteractive(aButtons)
        assert(contacts.contacts.size == 1)
        assert(ofInter.interactive == aButtons)
    }

    "Text encodes to the expected wire shape" in {
        val to   = WhatsAppId.WaId("+16505551234")
        val msg  = WhatsAppMessage.Text("As requested, here's the link...", previewUrl = true)
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"messaging_product\":\"whatsapp\""))
        assert(json.contains("\"recipient_type\":\"individual\""))
        assert(json.contains("\"to\":\"+16505551234\""))
        assert(json.contains("\"type\":\"text\""))
        assert(json.contains("\"preview_url\":true"))
        assert(json.contains("\"body\":\"As requested, here's the link...\""))
    }

    "Image-by-id encodes to the expected JSON" in {
        val to   = WhatsAppId.WaId("PHONE-NUMBER")
        val msg  = WhatsAppMessage.Image(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("MEDIA-OBJECT-ID")))
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(
            json == """{"messaging_product":"whatsapp","recipient_type":"individual","to":"PHONE-NUMBER","type":"image","image":{"id":"MEDIA-OBJECT-ID"}}"""
        )
    }

    "Image-by-link with caption omits id key" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val msg = WhatsAppMessage.Image(
            WhatsAppMedia.Source.ByLink("https://www.example.com/path/to/image.jpg"),
            Present("Optional caption text")
        )
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"link\":\"https://www.example.com/path/to/image.jpg\""))
        assert(json.contains("\"caption\":\"Optional caption text\""))
        assert(!json.contains("\"id\""))
    }

    "Document-by-id with filename and caption encodes expected document shape" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val msg = WhatsAppMessage.Document(
            WhatsAppMedia.Source.ById(WhatsAppId.MediaId("MEDIA_ID")),
            Present("Optional caption text"),
            Present("report.pdf")
        )
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"id\":\"MEDIA_ID\""))
        assert(json.contains("\"filename\":\"report.pdf\""))
        assert(json.contains("\"caption\":\"Optional caption text\""))
    }

    "Audio encodes id only with no voice field; Sticker has no caption or filename" in {
        val to     = WhatsAppId.WaId("P")
        val audio  = WhatsAppMessage.Audio(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("A")))
        val stick  = WhatsAppMessage.Sticker(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("S")))
        val jaudio = new String(kyo.internal.whatsapp.Codec.encodeSend(to, audio, Absent).toArray, "UTF-8")
        val jstick = new String(kyo.internal.whatsapp.Codec.encodeSend(to, stick, Absent).toArray, "UTF-8")
        assert(jaudio.contains("\"audio\":{\"id\":\"A\"}"))
        assert(!jaudio.contains("\"voice\""))
        assert(jstick.contains("\"sticker\":{\"id\":\"S\"}"))
        assert(!jstick.contains("\"caption\""))
    }

    "Location encodes numeric lat/long" in {
        val to   = WhatsAppId.WaId("P")
        val msg  = WhatsAppMessage.Location(37.4419, -122.1430, Present("HQ"), Present("1 Hacker Way"))
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"latitude\":37.4419"))
        assert(json.contains("\"longitude\":-122.143"))
        assert(json.contains("\"name\":\"HQ\""))
        assert(json.contains("\"address\":\"1 Hacker Way\""))
    }

    "Reaction encodes message_id and emoji; empty emoji preserved" in {
        val to    = WhatsAppId.WaId("P")
        val react = WhatsAppMessage.Reaction(WhatsAppId.MessageId("wamid.HBgLM"), "😀")
        val empty = WhatsAppMessage.Reaction(WhatsAppId.MessageId("wamid.HBgLM"), "")
        val jr    = new String(kyo.internal.whatsapp.Codec.encodeSend(to, react, Absent).toArray, "UTF-8")
        val je    = new String(kyo.internal.whatsapp.Codec.encodeSend(to, empty, Absent).toArray, "UTF-8")
        assert(jr.contains("\"message_id\":\"wamid.HBgLM\""))
        assert(jr.contains("\"emoji\":\"😀\""))
        assert(je.contains("\"emoji\":\"\""))
    }

    "Contacts encodes an array; empty Contacts encodes contacts:[]" in {
        val to     = WhatsAppId.WaId("P")
        val one    = WhatsAppMessage.Contacts(Chunk(WhatsAppContact(WhatsAppContact.Name("NAME"))))
        val empty  = WhatsAppMessage.Contacts(Chunk.empty)
        val jone   = new String(kyo.internal.whatsapp.Codec.encodeSend(to, one, Absent).toArray, "UTF-8")
        val jempty = new String(kyo.internal.whatsapp.Codec.encodeSend(to, empty, Absent).toArray, "UTF-8")
        assert(jone.contains("\"type\":\"contacts\""))
        assert(jone.contains("\"formatted_name\":\"NAME\""))
        assert(jempty.contains("\"contacts\":[]"))
    }

    "replyTo context is added at the top level" in {
        val to      = WhatsAppId.WaId("P")
        val msg     = WhatsAppMessage.Text("reply body")
        val replyTo = Present(WhatsAppId.MessageId("MSG_ID"))
        val json    = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, replyTo).toArray, "UTF-8")
        assert(json.contains("\"context\":{\"message_id\":\"MSG_ID\"}"))
        assert(json.contains("\"type\":\"text\""))
    }

    "OfInteractive routes to type:interactive with interactive sibling" in {
        val to = WhatsAppId.WaId("P")
        val msg =
            WhatsAppMessage.OfInteractive(WhatsAppInteractive.Buttons(Chunk(WhatsAppInteractive.ReplyButton("track", "Track shipment"))))
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"type\":\"interactive\""))
        assert(json.contains("\"interactive\":{"))
    }

end WhatsAppMessageTest

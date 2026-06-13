package kyo.whatsapp

import kyo.*
import kyo.whatsapp.Id.*

class MessageTest extends BaseWhatsAppTest:

    "Text carries body and previewUrl with a false default" in {
        val t1 = Message.Text("hi")
        val t2 = Message.Text("hi", previewUrl = true)
        assert(t1.body == "hi")
        assert(t1.previewUrl == false)
        assert(t2.body == "hi")
        assert(t2.previewUrl == true)
    }

    "Image/Video/Document/Audio/Sticker hold a Source" in {
        val src = Media.Source.ById(Id.MediaId("m"))
        val img = Message.Image(src)
        val vid = Message.Video(src)
        val doc = Message.Document(src)
        val aud = Message.Audio(src)
        val stk = Message.Sticker(src)
        assert(img.source == src)
        assert(vid.source == src)
        assert(doc.source == src)
        assert(doc.filename == Absent)
        assert(aud.source == src)
        assert(stk.source == src)
    }

    "Location holds numeric lat/long and optional name/address" in {
        val loc = Message.Location(37.42, -122.08, Present("HQ"), Absent)
        assert(loc.latitude == 37.42)
        assert(loc.longitude == -122.08)
        assert(loc.name == Present("HQ"))
        assert(loc.address == Absent)
    }

    "Reaction carries a MessageId and an emoji, empty allowed" in {
        val r1 = Message.Reaction(Id.MessageId("wamid.X"), "")
        val r2 = Message.Reaction(Id.MessageId("wamid.X"), "😀")
        assert(r1.emoji == "")
        assert(r2.emoji == "😀")
    }

    "Contacts wraps a Chunk[Contact] and OfInteractive wraps Interactive" in {
        val aContact = Contact(Contact.Name("Alice"))
        val aButtons = Interactive.Buttons(Chunk(Interactive.ReplyButton("yes", "Yes")))
        val contacts = Message.Contacts(Chunk(aContact))
        val ofInter  = Message.OfInteractive(aButtons)
        assert(contacts.contacts.size == 1)
        assert(ofInter.interactive == aButtons)
    }

    "Text encodes to the expected wire shape" in {
        val to   = Id.WhatsAppId("+16505551234")
        val msg  = Message.Text("As requested, here's the link...", previewUrl = true)
        val json = new String(kyo.whatsapp.internal.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"messaging_product\":\"whatsapp\""))
        assert(json.contains("\"recipient_type\":\"individual\""))
        assert(json.contains("\"to\":\"+16505551234\""))
        assert(json.contains("\"type\":\"text\""))
        assert(json.contains("\"preview_url\":true"))
        assert(json.contains("\"body\":\"As requested, here's the link...\""))
    }

    "Image-by-id encodes to the expected JSON" in {
        val to   = Id.WhatsAppId("PHONE-NUMBER")
        val msg  = Message.Image(Media.Source.ById(Id.MediaId("MEDIA-OBJECT-ID")))
        val json = new String(kyo.whatsapp.internal.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(
            json == """{"messaging_product":"whatsapp","recipient_type":"individual","to":"PHONE-NUMBER","type":"image","image":{"id":"MEDIA-OBJECT-ID"}}"""
        )
    }

    "Image-by-link with caption omits id key" in {
        val to   = Id.WhatsAppId("PHONE_NUMBER")
        val msg  = Message.Image(Media.Source.ByLink("https://www.example.com/path/to/image.jpg"), Present("Optional caption text"))
        val json = new String(kyo.whatsapp.internal.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"link\":\"https://www.example.com/path/to/image.jpg\""))
        assert(json.contains("\"caption\":\"Optional caption text\""))
        assert(!json.contains("\"id\""))
    }

    "Document-by-id with filename and caption encodes expected document shape" in {
        val to   = Id.WhatsAppId("PHONE_NUMBER")
        val msg  = Message.Document(Media.Source.ById(Id.MediaId("MEDIA_ID")), Present("Optional caption text"), Present("report.pdf"))
        val json = new String(kyo.whatsapp.internal.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"id\":\"MEDIA_ID\""))
        assert(json.contains("\"filename\":\"report.pdf\""))
        assert(json.contains("\"caption\":\"Optional caption text\""))
    }

    "Audio encodes id only with no voice field; Sticker has no caption or filename" in {
        val to     = Id.WhatsAppId("P")
        val audio  = Message.Audio(Media.Source.ById(Id.MediaId("A")))
        val stick  = Message.Sticker(Media.Source.ById(Id.MediaId("S")))
        val jaudio = new String(kyo.whatsapp.internal.Codec.encodeSend(to, audio, Absent).toArray, "UTF-8")
        val jstick = new String(kyo.whatsapp.internal.Codec.encodeSend(to, stick, Absent).toArray, "UTF-8")
        assert(jaudio.contains("\"audio\":{\"id\":\"A\"}"))
        assert(!jaudio.contains("\"voice\""))
        assert(jstick.contains("\"sticker\":{\"id\":\"S\"}"))
        assert(!jstick.contains("\"caption\""))
    }

    "Location encodes numeric lat/long" in {
        val to   = Id.WhatsAppId("P")
        val msg  = Message.Location(37.4419, -122.1430, Present("HQ"), Present("1 Hacker Way"))
        val json = new String(kyo.whatsapp.internal.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"latitude\":37.4419"))
        assert(json.contains("\"longitude\":-122.143"))
        assert(json.contains("\"name\":\"HQ\""))
        assert(json.contains("\"address\":\"1 Hacker Way\""))
    }

    "Reaction encodes message_id and emoji; empty emoji preserved" in {
        val to    = Id.WhatsAppId("P")
        val react = Message.Reaction(Id.MessageId("wamid.HBgLM"), "😀")
        val empty = Message.Reaction(Id.MessageId("wamid.HBgLM"), "")
        val jr    = new String(kyo.whatsapp.internal.Codec.encodeSend(to, react, Absent).toArray, "UTF-8")
        val je    = new String(kyo.whatsapp.internal.Codec.encodeSend(to, empty, Absent).toArray, "UTF-8")
        assert(jr.contains("\"message_id\":\"wamid.HBgLM\""))
        assert(jr.contains("\"emoji\":\"😀\""))
        assert(je.contains("\"emoji\":\"\""))
    }

    "Contacts encodes an array; empty Contacts encodes contacts:[]" in {
        val to     = Id.WhatsAppId("P")
        val one    = Message.Contacts(Chunk(Contact(Contact.Name("NAME"))))
        val empty  = Message.Contacts(Chunk.empty)
        val jone   = new String(kyo.whatsapp.internal.Codec.encodeSend(to, one, Absent).toArray, "UTF-8")
        val jempty = new String(kyo.whatsapp.internal.Codec.encodeSend(to, empty, Absent).toArray, "UTF-8")
        assert(jone.contains("\"type\":\"contacts\""))
        assert(jone.contains("\"formatted_name\":\"NAME\""))
        assert(jempty.contains("\"contacts\":[]"))
    }

    "replyTo context is added at the top level" in {
        val to      = Id.WhatsAppId("P")
        val msg     = Message.Text("reply body")
        val replyTo = Present(Id.MessageId("MSG_ID"))
        val json    = new String(kyo.whatsapp.internal.Codec.encodeSend(to, msg, replyTo).toArray, "UTF-8")
        assert(json.contains("\"context\":{\"message_id\":\"MSG_ID\"}"))
        assert(json.contains("\"type\":\"text\""))
    }

    "OfInteractive routes to type:interactive with interactive sibling" in {
        val to   = Id.WhatsAppId("P")
        val msg  = Message.OfInteractive(Interactive.Buttons(Chunk(Interactive.ReplyButton("track", "Track shipment"))))
        val json = new String(kyo.whatsapp.internal.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"type\":\"interactive\""))
        assert(json.contains("\"interactive\":{"))
    }

end MessageTest

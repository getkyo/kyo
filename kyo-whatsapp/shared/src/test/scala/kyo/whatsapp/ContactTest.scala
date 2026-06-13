package kyo.whatsapp

import kyo.*
import kyo.whatsapp.Id.*

class ContactTest extends BaseWhatsAppTest:

    "Contact requires a Name and defaults the collections empty" in {
        val c = Contact(Contact.Name("Sheena Nelson"))
        assert(c.name.formattedName == "Sheena Nelson")
        assert(c.phones == Chunk.empty)
        assert(c.emails == Chunk.empty)
        assert(c.addresses == Chunk.empty)
        assert(c.org == Absent)
        assert(c.urls == Chunk.empty)
        assert(c.birthday == Absent)
    }

    "Name requires formattedName and defaults the rest Absent" in {
        val n = Contact.Name("NAME", Present("FIRST"), Present("LAST"))
        assert(n.formattedName == "NAME")
        assert(n.first == Present("FIRST"))
        assert(n.last == Present("LAST"))
        assert(n.middle == Absent)
        assert(n.prefix == Absent)
        assert(n.suffix == Absent)
    }

    "Phone/Email/Address/Org/Url all-optional fields default Absent" in {
        val phone   = Contact.Phone()
        val email   = Contact.Email()
        val address = Contact.Address()
        val org     = Contact.Org()
        val url     = Contact.Url()
        assert(phone.phone == Absent)
        assert(phone.kind == Absent)
        assert(phone.waId == Absent)
        assert(email.email == Absent)
        assert(email.kind == Absent)
        assert(address.street == Absent)
        assert(address.city == Absent)
        assert(address.state == Absent)
        assert(address.zip == Absent)
        assert(address.country == Absent)
        assert(address.countryCode == Absent)
        assert(address.kind == Absent)
        assert(org.company == Absent)
        assert(org.department == Absent)
        assert(org.title == Absent)
        assert(url.url == Absent)
        assert(url.kind == Absent)
    }

    "full contact encodes to the documented snake_case shape" in {
        val to = Id.WhatsAppId("PHONE_NUMBER")
        val contact = Contact(
            Contact.Name("NAME", Present("FIRST_NAME"), Present("LAST_NAME")),
            phones = Chunk(Contact.Phone(Present("PHONE_NUMBER"), Present("HOME"), Present(Id.WhatsAppId("WHATSAPP_ID")))),
            emails = Chunk(Contact.Email(Present("EMAIL"), Present("WORK"))),
            addresses = Chunk(Contact.Address(
                Present("STREET"),
                Present("CITY"),
                Present("STATE"),
                Present("ZIP"),
                Present("COUNTRY"),
                Present("COUNTRY_CODE"),
                Present("HOME")
            )),
            org = Present(Contact.Org(Present("COMPANY"), Present("DEPARTMENT"), Present("TITLE"))),
            urls = Chunk(Contact.Url(Present("URL"), Present("WORK"))),
            birthday = Present("2000-01-01")
        )
        val json = new String(kyo.whatsapp.internal.Codec.encodeSend(to, Message.Contacts(Chunk(contact)), Absent).toArray, "UTF-8")
        assert(json.contains("\"formatted_name\":\"NAME\""))
        assert(json.contains("\"first_name\":\"FIRST_NAME\""))
        assert(json.contains("\"last_name\":\"LAST_NAME\""))
        assert(json.contains("\"country_code\":\"COUNTRY_CODE\""))
        assert(json.contains("\"wa_id\":\"WHATSAPP_ID\""))
        assert(json.contains("\"birthday\":\"2000-01-01\""))
    }

    "contact with only required name omits all optional sub-objects" in {
        val to      = Id.WhatsAppId("P")
        val contact = Contact(Contact.Name("NAME"))
        val json    = new String(kyo.whatsapp.internal.Codec.encodeSend(to, Message.Contacts(Chunk(contact)), Absent).toArray, "UTF-8")
        assert(json.contains("\"formatted_name\":\"NAME\""))
        assert(!json.contains("\"phones\""))
        assert(!json.contains("\"emails\""))
        assert(!json.contains("\"addresses\""))
        assert(!json.contains("\"org\""))
        assert(!json.contains("\"urls\""))
        assert(!json.contains("\"birthday\""))
    }

end ContactTest

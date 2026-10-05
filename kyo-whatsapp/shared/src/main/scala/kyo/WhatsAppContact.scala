package kyo

import kyo.schema.omit
import kyo.schema.rename

/** A single contact card for the WhatsApp contacts-message type. The Cloud API models each
  * contact with a required `Name` plus optional collections of phones, emails, addresses,
  * org details, URLs, and an optional birthday string.
  *
  * Referenced in both directions: outbound via `WhatsAppMessage.Contacts` (the `WhatsApp.send`
  * path) and inbound via `WhatsAppInboundMessage.Contacts` (the `WhatsApp.Webhook.decode`
  * path). Its `Schema` and its parts' are the Cloud API's contact object: an empty collection
  * leaves its key out, and a missing key reads as an empty collection.
  *
  * Nested types:
  *   - `Name` carries `formattedName` (required) plus optional first, last, middle, prefix,
  *     and suffix components
  *   - `Phone` carries an optional phone string, optional kind label, and optional wa_id for
  *     contacts that are also WhatsApp users
  *   - `Email` carries an optional email string and optional kind label
  *   - `Address` carries optional street, city, state, zip, country, country-code, and kind
  *   - `Org` carries optional company, department, and title strings
  *   - `Url` carries an optional URL string and optional kind label
  *
  * The `kind` field is the Cloud API `type` field; `type` is reserved in Scala
  * so the module uses `kind` as the disambiguation throughout.
  */
final case class WhatsAppContact(
    name: WhatsAppContact.Name,
    @omit(omit.WhenEmpty) phones: Chunk[WhatsAppContact.Phone] = Chunk.empty,
    @omit(omit.WhenEmpty) emails: Chunk[WhatsAppContact.Email] = Chunk.empty,
    @omit(omit.WhenEmpty) addresses: Chunk[WhatsAppContact.Address] = Chunk.empty,
    org: Maybe[WhatsAppContact.Org] = Absent,
    @omit(omit.WhenEmpty) urls: Chunk[WhatsAppContact.Url] = Chunk.empty,
    birthday: Maybe[String] = Absent
) derives CanEqual, Schema

object WhatsAppContact:

    final case class Name(
        @rename("formatted_name") formattedName: String,
        @rename("first_name") first: Maybe[String] = Absent,
        @rename("last_name") last: Maybe[String] = Absent,
        @rename("middle_name") middle: Maybe[String] = Absent,
        prefix: Maybe[String] = Absent,
        suffix: Maybe[String] = Absent
    ) derives CanEqual, Schema
    final case class Phone(
        phone: Maybe[String] = Absent,
        @rename("type") kind: Maybe[String] = Absent,
        @rename("wa_id") waId: Maybe[WhatsAppId.WaId] = Absent
    ) derives CanEqual, Schema
    final case class Email(email: Maybe[String] = Absent, @rename("type") kind: Maybe[String] = Absent) derives CanEqual, Schema
    final case class Address(
        street: Maybe[String] = Absent,
        city: Maybe[String] = Absent,
        state: Maybe[String] = Absent,
        zip: Maybe[String] = Absent,
        country: Maybe[String] = Absent,
        @rename("country_code") countryCode: Maybe[String] = Absent,
        @rename("type") kind: Maybe[String] = Absent
    ) derives CanEqual, Schema
    final case class Org(company: Maybe[String] = Absent, department: Maybe[String] = Absent, title: Maybe[String] = Absent)
        derives CanEqual, Schema

    /** A web address on the card. `url` is the text the person typed, relayed by Meta, not a URL the module or Meta sends to, so it stays
      * text: a card may hold `www.example.com`.
      */
    final case class Url(url: Maybe[String] = Absent, @rename("type") kind: Maybe[String] = Absent) derives CanEqual, Schema
end WhatsAppContact

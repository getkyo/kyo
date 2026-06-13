package kyo

/** A single contact card for the WhatsApp contacts-message type. The Cloud API models each
  * contact with a required `Name` plus optional collections of phones, emails, addresses,
  * org details, URLs, and an optional birthday string.
  *
  * Referenced in both directions: outbound via `WhatsAppMessage.Contacts` (the `WhatsApp.send`
  * path) and inbound via `WhatsAppNotification.Content.Contacts` (the `WhatsAppWebhook.decode`
  * path).
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
  * The `kind` field corresponds to the Cloud API `type` field; `type` is reserved in Scala
  * so the module uses `kind` as the disambiguation throughout.
  */
final case class WhatsAppContact(
    name: WhatsAppContact.Name,
    phones: Chunk[WhatsAppContact.Phone] = Chunk.empty,
    emails: Chunk[WhatsAppContact.Email] = Chunk.empty,
    addresses: Chunk[WhatsAppContact.Address] = Chunk.empty,
    org: Maybe[WhatsAppContact.Org] = Absent,
    urls: Chunk[WhatsAppContact.Url] = Chunk.empty,
    birthday: Maybe[String] = Absent
) derives CanEqual

object WhatsAppContact:
    final case class Name(
        formattedName: String,
        first: Maybe[String] = Absent,
        last: Maybe[String] = Absent,
        middle: Maybe[String] = Absent,
        prefix: Maybe[String] = Absent,
        suffix: Maybe[String] = Absent
    ) derives CanEqual
    final case class Phone(phone: Maybe[String] = Absent, kind: Maybe[String] = Absent, waId: Maybe[WhatsAppId.WaId] = Absent)
        derives CanEqual
    final case class Email(email: Maybe[String] = Absent, kind: Maybe[String] = Absent) derives CanEqual
    final case class Address(
        street: Maybe[String] = Absent,
        city: Maybe[String] = Absent,
        state: Maybe[String] = Absent,
        zip: Maybe[String] = Absent,
        country: Maybe[String] = Absent,
        countryCode: Maybe[String] = Absent,
        kind: Maybe[String] = Absent
    ) derives CanEqual
    final case class Org(company: Maybe[String] = Absent, department: Maybe[String] = Absent, title: Maybe[String] = Absent)
        derives CanEqual
    final case class Url(url: Maybe[String] = Absent, kind: Maybe[String] = Absent) derives CanEqual
end WhatsAppContact

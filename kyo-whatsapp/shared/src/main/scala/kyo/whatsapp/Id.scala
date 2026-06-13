package kyo.whatsapp

import kyo.*

/** The five opaque `String` identifier types for the WhatsApp Cloud API, grouped under one
  * object so they are mutually non-interchangeable at the compile-time type level. Each id
  * is a distinct opaque type alias for `String`, so passing a `WabaId` where a `MessageId`
  * is expected is a compile error rather than a silent wrong call to the Graph API.
  *
  * Id types:
  *   - `WabaId` is the WhatsApp Business Account id, the parent asset that owns phone numbers
  *     and templates. Appears as `entry[].id` in an inbound webhook envelope.
  *   - `PhoneNumberId` is the Graph API id of a registered business phone number. It is the
  *     path segment of every send, mark-read, and typing-indicator call, and appears in
  *     `WhatsApp.Config` and inbound `Notification.Metadata`.
  *   - `MediaId` identifies a previously uploaded media asset returned by `Media.upload` and
  *     used to construct `Media.Source.ById`.
  *   - `MessageId` is the WAMID (WhatsApp message id) returned by `WhatsApp.send` and present
  *     on every inbound message. Used for reply context, reactions, and mark-as-read targets.
  *   - `WhatsAppId` is the consumer wa_id (the normalized phone number in E.164 form). Used
  *     as the `to` field on outbound sends and the `from` field on inbound messages.
  *
  * Each type exposes `apply(String)` for construction and a `value` extension method for
  * reading the underlying string. Schema and CanEqual instances are derived for each so the
  * ids serialize transparently and support equality assertions in tests.
  */
object Id:

    /** WhatsApp Business Account id (`entry[].id` in a webhook envelope). */
    opaque type WabaId = String
    object WabaId:
        def apply(value: String): WabaId           = value
        given CanEqual[WabaId, WabaId]             = CanEqual.derived
        given Schema[WabaId]                       = summon[Schema[String]]
        extension (self: WabaId) def value: String = self
    end WabaId

    /** Registered business phone-number id (the send-endpoint path segment). */
    opaque type PhoneNumberId = String
    object PhoneNumberId:
        def apply(value: String): PhoneNumberId           = value
        given CanEqual[PhoneNumberId, PhoneNumberId]      = CanEqual.derived
        given Schema[PhoneNumberId]                       = summon[Schema[String]]
        extension (self: PhoneNumberId) def value: String = self
    end PhoneNumberId

    /** Uploaded media asset id. */
    opaque type MediaId = String
    object MediaId:
        def apply(value: String): MediaId           = value
        given CanEqual[MediaId, MediaId]            = CanEqual.derived
        given Schema[MediaId]                       = summon[Schema[String]]
        extension (self: MediaId) def value: String = self
    end MediaId

    /** Message WAMID (the reply context, the mark-read target, the reaction target). */
    opaque type MessageId = String
    object MessageId:
        def apply(value: String): MessageId           = value
        given CanEqual[MessageId, MessageId]          = CanEqual.derived
        given Schema[MessageId]                       = summon[Schema[String]]
        extension (self: MessageId) def value: String = self
    end MessageId

    /** Consumer wa_id / recipient phone (`to` outbound, `from` inbound). */
    opaque type WhatsAppId = String
    object WhatsAppId:
        def apply(value: String): WhatsAppId           = value
        given CanEqual[WhatsAppId, WhatsAppId]         = CanEqual.derived
        given Schema[WhatsAppId]                       = summon[Schema[String]]
        extension (self: WhatsAppId) def value: String = self
    end WhatsAppId

end Id

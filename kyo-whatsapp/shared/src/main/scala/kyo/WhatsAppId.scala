package kyo

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
  *     `WhatsAppConfig` and inbound `WhatsAppNotification.Metadata`.
  *   - `MediaId` identifies a previously uploaded media asset returned by `WhatsAppMedia.upload`
  *     and used to construct `WhatsAppMedia.Source.ById`.
  *   - `MessageId` is the WAMID (WhatsApp message id) returned by `WhatsApp.send` and present
  *     on every inbound message. Used for reply context, reactions, and mark-as-read targets.
  *   - `WaId` is the consumer wa_id (the normalized phone number in E.164 form). Used as the
  *     `to` field on outbound sends and the `from` field on inbound messages.
  *
  * Each type exposes `apply(String)` for construction and a `value` extension method for
  * reading the underlying string. Each derives `Schema` via `Schema.stringSchema.transform`
  * so the `Schema` is typed at the opaque type and the id serializes to and from a JSON string
  * verbatim, and a `CanEqual` for safe equality comparison.
  */
object WhatsAppId:

    /** WhatsApp Business Account id (`entry[].id` in a webhook envelope). */
    opaque type WabaId = String
    object WabaId:
        def apply(value: String): WabaId           = value
        extension (self: WabaId) def value: String = self
        given Schema[WabaId]                       = Schema.stringSchema.transform[WabaId](apply)(_.value)
        given CanEqual[WabaId, WabaId]             = CanEqual.derived
    end WabaId

    /** Registered business phone-number id (the send-endpoint path segment). */
    opaque type PhoneNumberId = String
    object PhoneNumberId:
        def apply(value: String): PhoneNumberId           = value
        extension (self: PhoneNumberId) def value: String = self
        given Schema[PhoneNumberId]                       = Schema.stringSchema.transform[PhoneNumberId](apply)(_.value)
        given CanEqual[PhoneNumberId, PhoneNumberId]      = CanEqual.derived
    end PhoneNumberId

    /** Uploaded media asset id. */
    opaque type MediaId = String
    object MediaId:
        def apply(value: String): MediaId           = value
        extension (self: MediaId) def value: String = self
        given Schema[MediaId]                       = Schema.stringSchema.transform[MediaId](apply)(_.value)
        given CanEqual[MediaId, MediaId]            = CanEqual.derived
    end MediaId

    /** Message WAMID (the reply context, the mark-read target, the reaction target). */
    opaque type MessageId = String
    object MessageId:
        def apply(value: String): MessageId           = value
        extension (self: MessageId) def value: String = self
        given Schema[MessageId]                       = Schema.stringSchema.transform[MessageId](apply)(_.value)
        given CanEqual[MessageId, MessageId]          = CanEqual.derived
    end MessageId

    /** Consumer wa_id / recipient phone (`to` outbound, `from` inbound). */
    opaque type WaId = String
    object WaId:
        def apply(value: String): WaId           = value
        extension (self: WaId) def value: String = self
        given Schema[WaId]                       = Schema.stringSchema.transform[WaId](apply)(_.value)
        given CanEqual[WaId, WaId]               = CanEqual.derived
    end WaId

end WhatsAppId

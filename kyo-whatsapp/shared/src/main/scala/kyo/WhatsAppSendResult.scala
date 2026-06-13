package kyo

/** The decoded send response: the message WAMID, the resolved recipient wa_id (if
  * present), and the optional `message_status`. The WAMID in `messageId` is the stable
  * identifier used for reply context, reactions, and mark-as-read. `contactWaId` carries
  * the normalized recipient wa_id returned by the API when available. `status` reflects
  * the Cloud API `message_status` field; `Absent` means the API did not include it.
  */
final case class WhatsAppSendResult(
    messageId: WhatsAppId.MessageId,
    contactWaId: Maybe[WhatsAppId.WaId] = Absent,
    status: Maybe[WhatsAppSendResult.Status] = Absent
) derives CanEqual

object WhatsAppSendResult:

    /** The `message_status` discriminator decoded from the send response. Known values
      * map to typed case objects; any unrecognized string maps to `Other(value)` so a
      * future API value does not silently collapse to a misleading known state.
      *
      * Known values: `Accepted` (request received), `HeldForQualityAssessment` (held for
      * review), `Paused` (sending paused due to quality). Any other `message_status`
      * string the API may introduce maps to `Other(value)`.
      */
    sealed trait Status derives CanEqual
    object Status:
        case object Accepted                  extends Status
        case object HeldForQualityAssessment  extends Status
        case object Paused                    extends Status
        final case class Other(value: String) extends Status derives CanEqual
    end Status
end WhatsAppSendResult

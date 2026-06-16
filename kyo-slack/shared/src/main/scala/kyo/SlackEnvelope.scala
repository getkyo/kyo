package kyo

/** Inbound frame ADT: the unit the receive loop yields. Typed cases for the named
  * envelope types plus `Unknown` carrying the raw frame for any unmodeled type (no
  * data loss). `Hello` and `Disconnect` carry no `Meta` (no `envelope_id`), so they
  * are delivered but never acked.
  */
sealed trait SlackEnvelope

object SlackEnvelope:

    case class Hello(
        numConnections: Int,
        appId: SlackId.AppId,
        debugInfo: Maybe[String] = Absent
    ) extends SlackEnvelope derives Schema, CanEqual

    case class EventsApi(meta: SlackEnvelope.Meta, event: SlackEvent) extends SlackEnvelope derives CanEqual

    case class Interactive(meta: SlackEnvelope.Meta, interaction: SlackInteraction) extends SlackEnvelope derives CanEqual

    case class SlashCommand(meta: SlackEnvelope.Meta, command: SlackCommand) extends SlackEnvelope derives CanEqual

    case class Disconnect(reason: SlackEnvelope.DisconnectReason) extends SlackEnvelope derives Schema, CanEqual

    case class Unknown(`type`: String, payloadJson: String) extends SlackEnvelope derives CanEqual

    /** Per-envelope metadata read from the outer wire frame: the id used to ack,
      * whether the envelope accepts a response payload, and the retry attempt/reason
      * Slack sets on a re-delivery.
      */
    case class Meta(
        envelopeId: SlackId.EnvelopeId,
        acceptsResponsePayload: Boolean = false,
        retryAttempt: Maybe[Int] = Absent,
        retryReason: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** A `disconnect` frame reason. `Warning`/`RefreshRequested` are routine;
      * `LinkDisabled` is terminal; `Unknown(raw)` absorbs an unmodeled value for
      * forward-safety.
      */
    enum DisconnectReason derives CanEqual:
        case Warning
        case RefreshRequested
        case LinkDisabled
        case Unknown(raw: String)
    end DisconnectReason

    object DisconnectReason:
        /** Serialises as the Slack wire string. `Unknown(raw)` preserves the raw value
          * for forward-safety (no exception thrown for unrecognized values).
          *
          * Frame-free given: a Frame-parameterised given cannot be resolved by
          * `Schema.derived`'s implicit search (the derivation macro has no enclosing Frame).
          */
        given Schema[DisconnectReason] = Schema.init[DisconnectReason](
            writeFn = (v, w) =>
                w.string(v match
                    case Warning          => "warning"
                    case RefreshRequested => "refresh_requested"
                    case LinkDisabled     => "link_disabled"
                    case Unknown(raw)     => raw),
            readFn = r =>
                r.string() match
                    case "warning"           => Warning
                    case "refresh_requested" => RefreshRequested
                    case "link_disabled"     => LinkDisabled
                    case other               => Unknown(other)
        )
    end DisconnectReason

end SlackEnvelope

package kyo

/** What Slack delivers over Socket Mode. `A` is the answer the handler owes for the case:
  *   - `EventsApi`, `Interactive`, `SlashCommand` and `Unknown` are `SlackEnvelope[SlackAck]`: Slack
  *     waits for an acknowledgement of each, and the module sends exactly one, built from the
  *     `SlackAck` the handler returns;
  *   - `Hello`, `Disconnect` and `UnknownFrame` are `SlackEnvelope[Unit]`: they carry no `envelope_id`,
  *     so there is nothing to acknowledge.
  *
  * The handler is the polymorphic function `[A] => SlackEnvelope[A] => A < ...`, and matching a case
  * refines `A`, so acknowledging a `Hello` or answering an `EventsApi` with nothing does not compile.
  * The cases are grouped by their answer, `Acknowledged` and `Plain`, so a handler's fallback is a
  * checked type test that refines `A` too: `case _: SlackEnvelope.Acknowledged => SlackAck.Ack`,
  * `case _: SlackEnvelope.Plain => Kyo.unit`. A bare `case _` leaves `A` unknown and does not compile.
  *
  * `Unknown` and `UnknownFrame` carry a frame of a type the module does not model, or an envelope whose
  * payload lacks a field its type requires, as a [[kyo.SlackRawJson]], so nothing is lost. The frame's
  * `envelope_id` decides which: `Unknown` has one and its `Meta`, and is acknowledged; `UnknownFrame`
  * has none, and takes no answer. Slack never issues an empty `envelope_id`, so an empty one is none.
  *
  * No envelope type has a `Schema`: kyo-slack decodes Slack's own frames through its internal
  * wire types, and a given makes `summon[Schema[X]]` a compile error saying so, since kyo-schema
  * would otherwise derive one on demand.
  */
sealed trait SlackEnvelope[A]

object SlackEnvelope:

    given CanEqual[SlackEnvelope[?], SlackEnvelope[?]] = CanEqual.derived

    // Bounded rather than on `SlackEnvelope` alone, so the refusal also answers a summon of one case, such as `Hello`.
    inline given [U <: SlackEnvelope[?]]: Schema[U] = compiletime.error(
        "SlackEnvelope has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
    )

    /** The envelopes Slack waits to see acknowledged: `EventsApi`, `Interactive`, `SlashCommand` and `Unknown`. */
    sealed trait Acknowledged extends SlackEnvelope[SlackAck]

    /** The envelopes that take no answer, since they carry no `envelope_id`: `Hello`, `Disconnect` and `UnknownFrame`. */
    sealed trait Plain extends SlackEnvelope[Unit]

    /** The first frame of a connection. `debugHost` is the Slack host serving it, from `debug_info.host`, `Absent` when the frame
      * names none.
      */
    final case class Hello(
        numConnections: Int,
        appId: SlackId.AppId,
        debugHost: Maybe[String] = Absent
    ) extends Plain derives CanEqual

    /** An Events API delivery. `eventId` is Slack's `event_id`, "globally unique across all
      * workspaces": the id to deduplicate the event by. `meta.envelopeId` is the id of this Socket
      * Mode delivery, used for the ack.
      */
    final case class EventsApi(meta: SlackEnvelope.Meta, event: SlackEvent, eventId: SlackId.EventId) extends Acknowledged
        derives CanEqual

    final case class Interactive(meta: SlackEnvelope.Meta, interaction: SlackInteraction) extends Acknowledged derives CanEqual

    final case class SlashCommand(meta: SlackEnvelope.Meta, command: SlackCommand) extends Acknowledged derives CanEqual

    final case class Disconnect(reason: SlackEnvelope.DisconnectReason) extends Plain derives CanEqual

    /** An envelope, with its `envelope_id`, of a type the module does not model, or whose payload lacks a field its type requires.
      * Slack waits for its acknowledgement like any other envelope.
      */
    final case class Unknown(`type`: String, payload: SlackRawJson, meta: SlackEnvelope.Meta) extends Acknowledged derives CanEqual

    /** A frame the module does not model, or one lacking a field its type requires, that carries no `envelope_id`: there is no
      * envelope to acknowledge, so it takes no answer.
      */
    final case class UnknownFrame(`type`: String, payload: SlackRawJson) extends Plain derives CanEqual

    /** Per-envelope metadata read from the outer wire frame: the id used to ack,
      * whether the envelope accepts a response payload (`Absent` when the frame does not
      * say), and the retry attempt/reason Slack sets on a re-delivery.
      */
    final case class Meta(
        envelopeId: SlackId.EnvelopeId,
        acceptsResponsePayload: Maybe[Boolean] = Absent,
        retryAttempt: Maybe[Int] = Absent,
        retryReason: Maybe[String] = Absent
    ) derives CanEqual

    object Meta:
        inline given noSchema: Schema[Meta] = compiletime.error(
            "SlackEnvelope.Meta has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
        )
    end Meta

    /** A `disconnect` frame reason. `Warning`/`RefreshRequested` are routine;
      * `LinkDisabled` is terminal; `Unknown(raw)` absorbs an unmodeled value for
      * forward-safety; `Unspecified` is a disconnect whose frame names no reason, handled as
      * routine.
      */
    enum DisconnectReason derives CanEqual:
        case Warning
        case RefreshRequested
        case LinkDisabled
        case Unknown(raw: String)
        case Unspecified
    end DisconnectReason

    object DisconnectReason:
        inline given noSchema: Schema[DisconnectReason] = compiletime.error(
            "SlackEnvelope.DisconnectReason has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
        )
    end DisconnectReason

end SlackEnvelope

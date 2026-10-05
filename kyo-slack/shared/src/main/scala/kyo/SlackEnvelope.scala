package kyo

import kyo.schema.rename

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
  * An acknowledged case holds the frame's keys as Slack sends them: `envelopeId`, the `payload`, and
  * `acceptsResponsePayload`, `retryAttempt` and `retryReason`, `Absent` when the frame leaves them out.
  *
  * `Unknown` and `UnknownFrame` carry a frame of a type the module does not model, or an envelope whose
  * payload lacks a field its type requires, as a [[kyo.SlackRawJson]], so nothing is lost. The frame's
  * `envelope_id` decides which: `Unknown` has one, and is acknowledged; `UnknownFrame` has none, and
  * takes no answer. Slack never issues an empty `envelope_id`, so an empty one is none.
  *
  * `Hello` has a `Schema` that is Slack's `hello` frame, the `type` key aside, which the root names.
  * `SlackEnvelope` itself and its other cases have none yet: a given makes summoning one a compile
  * error, since kyo-schema would otherwise derive one that is not Slack's JSON.
  */
sealed trait SlackEnvelope[A]

object SlackEnvelope:

    given CanEqual[SlackEnvelope[?], SlackEnvelope[?]] = CanEqual.derived

    // Bounded rather than on `SlackEnvelope` alone, so the refusal also answers a summon of a case with no `Schema` of its own.
    inline given [U <: SlackEnvelope[?]]: Schema[U] = compiletime.error(
        "SlackEnvelope has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
    )

    /** The envelopes Slack waits to see acknowledged: `EventsApi`, `Interactive`, `SlashCommand` and `Unknown`. `envelopeId` is
      * the id the ack answers; `acceptsResponsePayload` says whether the ack may carry a payload (`Absent` when the frame does not
      * say); `retryAttempt` and `retryReason` are set on a re-delivery.
      */
    sealed trait Acknowledged extends SlackEnvelope[SlackAck]:
        def envelopeId: SlackId.EnvelopeId
        def acceptsResponsePayload: Maybe[Boolean]
        def retryAttempt: Maybe[Int]
        def retryReason: Maybe[String]
    end Acknowledged

    /** The envelopes that take no answer, since they carry no `envelope_id`: `Hello`, `Disconnect` and `UnknownFrame`. */
    sealed trait Plain extends SlackEnvelope[Unit]

    /** The first frame of a connection, with Slack's `connection_info` and `debug_info` objects. `appId` is the app the connection
      * serves; `debugHost` is the Slack host serving it, `Absent` when the frame names none.
      */
    final case class Hello(
        @rename("num_connections") numConnections: Int,
        @rename("connection_info") connectionInfo: Hello.ConnectionInfo,
        @rename("debug_info") debugInfo: Maybe[Hello.DebugInfo] = Absent
    ) extends Plain derives CanEqual, Schema:
        def appId: SlackId.AppId     = connectionInfo.appId
        def debugHost: Maybe[String] = debugInfo.flatMap(_.host)
    end Hello

    object Hello:
        final case class ConnectionInfo(@rename("app_id") appId: SlackId.AppId) derives CanEqual, Schema

        /** The keys of Slack's `debug_info` the module models: the `host` serving the connection. */
        final case class DebugInfo(host: Maybe[String] = Absent) derives CanEqual, Schema
    end Hello

    /** An Events API delivery. `envelopeId` is the id of this Socket Mode delivery, used for the ack; the payload's `eventId`
      * is Slack's `event_id`, "globally unique across all workspaces": the id to deduplicate the event by.
      */
    final case class EventsApi(
        envelopeId: SlackId.EnvelopeId,
        payload: EventsApi.Payload,
        acceptsResponsePayload: Maybe[Boolean] = Absent,
        retryAttempt: Maybe[Int] = Absent,
        retryReason: Maybe[String] = Absent
    ) extends Acknowledged derives CanEqual

    object EventsApi:
        /** The Events API callback an `events_api` envelope carries: the event and its `event_id`. */
        final case class Payload(eventId: SlackId.EventId, event: SlackEvent) derives CanEqual

    final case class Interactive(
        envelopeId: SlackId.EnvelopeId,
        payload: SlackInteraction,
        acceptsResponsePayload: Maybe[Boolean] = Absent,
        retryAttempt: Maybe[Int] = Absent,
        retryReason: Maybe[String] = Absent
    ) extends Acknowledged derives CanEqual

    final case class SlashCommand(
        envelopeId: SlackId.EnvelopeId,
        payload: SlackCommand,
        acceptsResponsePayload: Maybe[Boolean] = Absent,
        retryAttempt: Maybe[Int] = Absent,
        retryReason: Maybe[String] = Absent
    ) extends Acknowledged derives CanEqual

    final case class Disconnect(reason: SlackEnvelope.DisconnectReason) extends Plain derives CanEqual

    /** An envelope, with its `envelope_id`, of a type the module does not model, or whose payload lacks a field its type requires.
      * `payload` is the whole frame. Slack waits for its acknowledgement like any other envelope.
      */
    final case class Unknown(
        `type`: String,
        payload: SlackRawJson,
        envelopeId: SlackId.EnvelopeId,
        acceptsResponsePayload: Maybe[Boolean] = Absent,
        retryAttempt: Maybe[Int] = Absent,
        retryReason: Maybe[String] = Absent
    ) extends Acknowledged derives CanEqual

    /** A frame the module does not model, or one lacking a field its type requires, that carries no `envelope_id`: there is no
      * envelope to acknowledge, so it takes no answer.
      */
    final case class UnknownFrame(`type`: String, payload: SlackRawJson) extends Plain derives CanEqual

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

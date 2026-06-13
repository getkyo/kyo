package kyo.internal

import kyo.*

/** The Socket Mode wire codec: permissive-header decode then typed-or-raw
  * sub-decode per envelope type (the `CdpWireMessage` pattern), plus the ack-frame
  * encoder and the `apps.connections.open` request/response codec. Pure functions
  * over JSON strings; no effect coupling beyond `Frame` for `Json` and `Log`.
  *
  * The decode never aborts on a malformed payload: a known type whose inner payload
  * fails decode yields a typed `Unknown` carrying the raw JSON; a structurally
  * uncorrelatable frame (not valid JSON, or no recoverable type) yields `Skip` with
  * a reason the engine logs. A structural decode failure is surfaced as an `Abort`
  * only at the one structural-decode site, `Slack.custom`, never here.
  *
  * The internal wire DTOs use snake_case field names that match the Slack wire
  * verbatim (e.g. `envelope_id`, `trigger_id`). The public-facing types use
  * idiomatic camelCase; the codec maps between the two explicitly.
  */
private[kyo] object SlackWire:

    // CanEqual witness for SlackAck case object matching: the sealed trait does not
    // derive CanEqual but the Scala 3 pattern match for a case object compiles to ==.
    private given CanEqual[SlackAck.Ack.type, SlackAck] = CanEqual.derived

    /** The result of decoding one inbound frame. `Envelope` carries the typed
      * envelope, whether it is ackable (it carries a `Meta.envelopeId`), and the
      * captured `response_url` for a block_actions interaction (the public
      * `BlockActions` has no such field; the engine correlates it at ack time).
      * `Skip` carries a reason for the structurally-uncorrelatable case.
      */
    enum Decoded derives CanEqual:
        case Envelope(value: SlackEnvelope, ackable: Boolean, responseUrl: Maybe[String])
        case Skip(reason: String)

    // Internal wire DTOs: snake_case field names match Slack's wire format.
    // All fields are named exactly as Slack sends them so the derived Schema's JSON
    // keys are the wire keys with zero renaming needed.

    /** Permissive routing header: reads only the routing and meta fields, tolerating
      * all other fields via the permissive decoder. Field names are snake_case to
      * match the Slack wire (`envelope_id`, `accepts_response_payload`, etc.).
      */
    final private[kyo] case class SlackWireHeader(
        `type`: Maybe[String] = Absent,
        envelope_id: Maybe[String] = Absent,
        accepts_response_payload: Boolean = false,
        retry_attempt: Maybe[Int] = Absent,
        retry_reason: Maybe[String] = Absent
    ) derives Schema

    /** Typed sub-decode envelope: decodes the `payload` slot from the same raw frame
      * to the per-route payload type `P`.
      */
    final private[kyo] case class SlackPayloadEnvelope[P](payload: P) derives Schema

    /** Lightweight discriminant probe: reads ONLY `payload.event.type`. */
    final private[kyo] case class EventTypeProbe(event: EventTypeOnly = EventTypeOnly()) derives Schema
    final private[kyo] case class EventTypeOnly(`type`: Maybe[String] = Absent) derives Schema

    /** A nested envelope typed to a concrete `SlackEvent` leaf `E`: decodes
      * `{"payload":{"event":<E>}}` directly to `E`. Inner DTOs use snake_case names
      * matching the Slack wire.
      */
    final private[kyo] case class EventLeafEnvelope[E](event: E) derives Schema

    // per-leaf event DTOs with snake_case field names.

    final private[kyo] case class WireMessage(
        channel: Maybe[String] = Absent,
        user: Maybe[String] = Absent,
        text: Maybe[String] = Absent,
        ts: Maybe[String] = Absent,
        thread_ts: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireAppMention(
        channel: Maybe[String] = Absent,
        user: Maybe[String] = Absent,
        text: Maybe[String] = Absent,
        ts: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireReactionAdded(
        reaction: Maybe[String] = Absent,
        user: Maybe[String] = Absent,
        item_channel: Maybe[String] = Absent,
        item_ts: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireAppHomeOpened(
        channel: Maybe[String] = Absent,
        user: Maybe[String] = Absent,
        tab: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireMemberJoinedChannel(
        channel: Maybe[String] = Absent,
        user: Maybe[String] = Absent,
        inviter: Maybe[String] = Absent
    ) derives Schema

    /** Interactive payload discriminant probe and response_url capture. */
    final private[kyo] case class InteractivePayload(
        `type`: Maybe[String] = Absent,
        response_url: Maybe[String] = Absent
    ) derives Schema

    // per-interaction DTOs with snake_case field names.
    // Real Slack interactive payloads send `user` and `channel` as JSON objects (the
    // Events API sends `user` as a bare string id, which the event DTOs above model
    // correctly). These nested refs read the `id` out of those objects. The `view` of a
    // view_submission/view_closed is likewise nested: its id is `view.id` and the
    // submitted form state is the `view.state` object.

    /** The `user` object Slack sends on every interactive payload: `{"id":...}` (plus
      * `username`/`team_id`/etc. tolerated by the permissive decoder).
      */
    final private[kyo] case class WireUserRef(id: Maybe[String] = Absent) derives Schema

    /** The `channel` object on a block_actions / message_action payload: `{"id":...}`. */
    final private[kyo] case class WireChannelRef(id: Maybe[String] = Absent) derives Schema

    /** The nested `view` object on a view_submission / view_closed payload: this typed DTO
      * reads only the `view.id`. The submitted form `view.state` is a free-form object that
      * the derived `Structure.Value` enum Schema cannot decode from raw JSON, so it is
      * recovered separately via `SlackRawJson.nestedJson` (which re-emits it as native JSON).
      */
    final private[kyo] case class WireViewRef(id: Maybe[String] = Absent) derives Schema

    final private[kyo] case class WireBlockActions(
        user: WireUserRef = WireUserRef(),
        trigger_id: Maybe[String] = Absent,
        channel: WireChannelRef = WireChannelRef(),
        actions: Chunk[WireAction] = Chunk.empty
    ) derives Schema

    final private[kyo] case class WireAction(
        action_id: String = "",
        block_id: String = "",
        value: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireViewSubmission(
        user: WireUserRef = WireUserRef(),
        view: WireViewRef = WireViewRef()
    ) derives Schema

    final private[kyo] case class WireViewClosed(
        user: WireUserRef = WireUserRef(),
        view: WireViewRef = WireViewRef(),
        is_cleared: Boolean = false
    ) derives Schema

    final private[kyo] case class WireShortcut(
        user: WireUserRef = WireUserRef(),
        trigger_id: Maybe[String] = Absent,
        callback_id: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireMessageAction(
        user: WireUserRef = WireUserRef(),
        trigger_id: Maybe[String] = Absent,
        callback_id: Maybe[String] = Absent,
        channel: WireChannelRef = WireChannelRef(),
        message: WireMessageActionMsg = WireMessageActionMsg()
    ) derives Schema

    /** The `message` object on a message_action payload: its `ts` is the targeted
      * message's timestamp.
      */
    final private[kyo] case class WireMessageActionMsg(ts: Maybe[String] = Absent) derives Schema

    /** Slash command payload DTO: snake_case names matching the Slack wire. */
    final private[kyo] case class WireSlashCommand(
        command: String = "",
        text: String = "",
        channel_id: Maybe[String] = Absent,
        user_id: Maybe[String] = Absent,
        trigger_id: Maybe[String] = Absent,
        response_url: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class HelloFrame(
        num_connections: Int = 0,
        connection_info: HelloConnInfo = HelloConnInfo(),
        debug_info: Maybe[HelloDebugInfo] = Absent
    ) derives Schema

    final private[kyo] case class HelloConnInfo(app_id: String = "") derives Schema

    final private[kyo] case class HelloDebugInfo(
        host: Maybe[String] = Absent,
        started: Maybe[String] = Absent,
        build_number: Maybe[Int] = Absent,
        approximate_connection_time: Maybe[Int] = Absent
    ) derives Schema

    final private[kyo] case class DisconnectFrame(reason: String = "") derives Schema

    /** Decode one inbound text frame into a `Decoded`. Never aborts. */
    def decode(frame: String)(using Frame): Decoded < Sync =
        Json.decode[SlackWireHeader](frame) match
            case Result.Success(h) =>
                h.`type` match
                    case Present("hello")          => decodeHello(frame)
                    case Present("disconnect")     => decodeDisconnect(frame)
                    case Present("events_api")     => decodeEventsApi(frame, h)
                    case Present("interactive")    => decodeInteractive(frame, h)
                    case Present("slash_commands") => decodeSlash(frame, h)
                    case Present(other)            =>
                        // An Unknown type carries no envelope id the engine can ack
                        // (`envelopeId` is Absent for Unknown), so it is delivered but
                        // not acked; marking it ackable would let emitAck silently drop it.
                        Decoded.Envelope(SlackEnvelope.Unknown(other, frame), ackable = false, responseUrl = Absent)
                    case Absent => Decoded.Skip(s"frame has no type field: $frame")
            case Result.Failure(err) => Decoded.Skip(s"header decode failed: ${err.getMessage}")
            case Result.Panic(ex)    => Decoded.Skip(s"header decode panicked: ${ex.getMessage}")

    private def meta(h: SlackWireHeader)(using Frame): Maybe[SlackEnvelope.Meta] =
        h.envelope_id.map { id =>
            SlackEnvelope.Meta(
                SlackId.EnvelopeId(id),
                h.accepts_response_payload,
                h.retry_attempt,
                h.retry_reason
            )
        }

    private def decodeHello(frame: String)(using Frame): Decoded < Sync =
        Json.decode[HelloFrame](frame) match
            case Result.Success(f) =>
                val debugInfo = f.debug_info.map(d => d.host.getOrElse(""))
                Decoded.Envelope(
                    SlackEnvelope.Hello(f.num_connections, SlackId.AppId(f.connection_info.app_id), debugInfo),
                    ackable = false,
                    responseUrl = Absent
                )
            case _ =>
                Decoded.Envelope(SlackEnvelope.Unknown("hello", frame), ackable = false, responseUrl = Absent)

    private def decodeDisconnect(frame: String)(using Frame): Decoded < Sync =
        val reason = Json.decode[SlackPayloadEnvelope[DisconnectFrame]](frame) match
            case Result.Success(env) => env.payload.reason
            case _ =>
                Json.decode[DisconnectFrame](frame) match
                    case Result.Success(f) => f.reason
                    case _                 => ""
        val dr = reason match
            case "warning"           => SlackEnvelope.DisconnectReason.Warning
            case "refresh_requested" => SlackEnvelope.DisconnectReason.RefreshRequested
            case "link_disabled"     => SlackEnvelope.DisconnectReason.LinkDisabled
            case other               => SlackEnvelope.DisconnectReason.Unknown(other)
        Decoded.Envelope(SlackEnvelope.Disconnect(dr), ackable = false, responseUrl = Absent)
    end decodeDisconnect

    private def decodeEventsApi(frame: String, h: SlackWireHeader)(using Frame): Decoded < Sync =
        meta(h) match
            case Absent => Decoded.Envelope(SlackEnvelope.Unknown("events_api", frame), ackable = false, responseUrl = Absent)
            case Present(m) =>
                decodeEvent(frame).map { event =>
                    Decoded.Envelope(SlackEnvelope.EventsApi(m, event), ackable = true, responseUrl = Absent)
                }

    /** Read `payload.event.type` (typed discriminant probe) and dispatch to the typed
      * `SlackEvent` leaf decoded via a wire DTO. Falls through to
      * `SlackEvent.Unknown(type, eventJson)` on an unmodeled or malformed inner event.
      * `eventJson` is the raw inner `payload.event` object recovered natively via
      * `SlackRawJson.nestedJson` (the derived `Structure.Value` enum Schema cannot decode
      * free-form JSON, so the inner object is navigated from the frame AST; no data loss).
      */
    private def decodeEvent(frame: String)(using Frame): SlackEvent < Sync =
        val eventJson: String =
            SlackRawJson.nestedJson(frame, Seq("payload", "event"), frame)
        val eventType: String =
            Json.decode[SlackPayloadEnvelope[EventTypeProbe]](frame) match
                case Result.Success(env) => env.payload.event.`type`.getOrElse("")
                case _                   => ""
        eventType match
            case "message"               => decodeMessageEvent(frame, eventJson)
            case "app_mention"           => decodeAppMentionEvent(frame, eventJson)
            case "reaction_added"        => decodeReactionAddedEvent(frame, eventJson)
            case "app_home_opened"       => decodeAppHomeOpenedEvent(frame, eventJson)
            case "member_joined_channel" => decodeMemberJoinedChannelEvent(frame, eventJson)
            case other                   => SlackEvent.Unknown(other, eventJson)
        end match
    end decodeEvent

    private def decodeMessageEvent(frame: String, eventJson: String)(using Frame): SlackEvent < Sync =
        Json.decode[SlackPayloadEnvelope[EventLeafEnvelope[WireMessage]]](frame) match
            case Result.Success(env) =>
                val w = env.payload.event
                (w.channel, w.user, w.text, w.ts) match
                    case (Present(ch), Present(u), Present(t), Present(ts)) =>
                        SlackEvent.Message(SlackId.ChannelId(ch), SlackId.UserId(u), t, SlackId.Ts(ts), w.thread_ts.map(SlackId.Ts(_)))
                    case _ =>
                        Log.warn(s"SlackWire: malformed message event; preserving as Unknown").andThen(
                            SlackEvent.Unknown("message", eventJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed message event; preserving as Unknown").andThen(
                    SlackEvent.Unknown("message", eventJson)
                )

    private def decodeAppMentionEvent(frame: String, eventJson: String)(using Frame): SlackEvent < Sync =
        Json.decode[SlackPayloadEnvelope[EventLeafEnvelope[WireAppMention]]](frame) match
            case Result.Success(env) =>
                val w = env.payload.event
                (w.channel, w.user, w.text, w.ts) match
                    case (Present(ch), Present(u), Present(t), Present(ts)) =>
                        SlackEvent.AppMention(SlackId.ChannelId(ch), SlackId.UserId(u), t, SlackId.Ts(ts))
                    case _ =>
                        Log.warn(s"SlackWire: malformed app_mention event; preserving as Unknown").andThen(
                            SlackEvent.Unknown("app_mention", eventJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed app_mention event; preserving as Unknown").andThen(
                    SlackEvent.Unknown("app_mention", eventJson)
                )

    private def decodeReactionAddedEvent(frame: String, eventJson: String)(using Frame): SlackEvent < Sync =
        Json.decode[SlackPayloadEnvelope[EventLeafEnvelope[WireReactionAdded]]](frame) match
            case Result.Success(env) =>
                val w = env.payload.event
                (w.user, w.reaction, w.item_channel, w.item_ts) match
                    case (Present(u), Present(r), Present(ch), Present(ts)) =>
                        SlackEvent.ReactionAdded(SlackId.UserId(u), r, SlackId.ChannelId(ch), SlackId.Ts(ts))
                    case _ =>
                        Log.warn(s"SlackWire: malformed reaction_added event; preserving as Unknown").andThen(
                            SlackEvent.Unknown("reaction_added", eventJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed reaction_added event; preserving as Unknown").andThen(
                    SlackEvent.Unknown("reaction_added", eventJson)
                )

    private def decodeAppHomeOpenedEvent(frame: String, eventJson: String)(using Frame): SlackEvent < Sync =
        Json.decode[SlackPayloadEnvelope[EventLeafEnvelope[WireAppHomeOpened]]](frame) match
            case Result.Success(env) =>
                val w = env.payload.event
                (w.user, w.channel) match
                    case (Present(u), Present(ch)) =>
                        SlackEvent.AppHomeOpened(SlackId.UserId(u), SlackId.ChannelId(ch), w.tab.getOrElse(""))
                    case _ =>
                        Log.warn(s"SlackWire: malformed app_home_opened event; preserving as Unknown").andThen(
                            SlackEvent.Unknown("app_home_opened", eventJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed app_home_opened event; preserving as Unknown").andThen(
                    SlackEvent.Unknown("app_home_opened", eventJson)
                )

    private def decodeMemberJoinedChannelEvent(frame: String, eventJson: String)(using Frame): SlackEvent < Sync =
        Json.decode[SlackPayloadEnvelope[EventLeafEnvelope[WireMemberJoinedChannel]]](frame) match
            case Result.Success(env) =>
                val w = env.payload.event
                (w.user, w.channel) match
                    case (Present(u), Present(ch)) =>
                        SlackEvent.MemberJoinedChannel(SlackId.UserId(u), SlackId.ChannelId(ch), w.inviter.map(SlackId.UserId(_)))
                    case _ =>
                        Log.warn(s"SlackWire: malformed member_joined_channel event; preserving as Unknown").andThen(
                            SlackEvent.Unknown("member_joined_channel", eventJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed member_joined_channel event; preserving as Unknown").andThen(
                    SlackEvent.Unknown("member_joined_channel", eventJson)
                )

    private def decodeInteractive(frame: String, h: SlackWireHeader)(using Frame): Decoded < Sync =
        meta(h) match
            case Absent => Decoded.Envelope(SlackEnvelope.Unknown("interactive", frame), ackable = false, responseUrl = Absent)
            case Present(m) =>
                decodeInteraction(frame).map { case (interaction, responseUrl) =>
                    Decoded.Envelope(SlackEnvelope.Interactive(m, interaction), ackable = true, responseUrl = responseUrl)
                }

    /** Read `payload.type` (typed probe) and dispatch to the typed `SlackInteraction`
      * leaf decoded via a wire DTO. Captures the block_actions `response_url` for the
      * engine to POST a `BlockActionsResponse` update (the public shape carries no such
      * field). The raw `payload` JSON (for Unknown / malformed) is the inner `payload`
      * object recovered natively via `SlackRawJson.nestedJson`.
      */
    private def decodeInteraction(frame: String)(using Frame): (SlackInteraction, Maybe[String]) < Sync =
        val payloadJson: String =
            SlackRawJson.nestedJson(frame, Seq("payload"), frame)
        Json.decode[SlackPayloadEnvelope[InteractivePayload]](frame) match
            case Result.Success(env) =>
                env.payload.`type` match
                    case Present("block_actions") =>
                        decodeBlockActions(frame, payloadJson).map((_, env.payload.response_url))
                    case Present("view_submission") =>
                        decodeViewSubmission(frame, payloadJson).map((_, Absent))
                    case Present("view_closed") =>
                        decodeViewClosed(frame, payloadJson).map((_, Absent))
                    case Present("shortcut") =>
                        decodeShortcut(frame, payloadJson).map((_, Absent))
                    case Present("message_action") =>
                        decodeMessageAction(frame, payloadJson).map((_, env.payload.response_url))
                    case Present(other) => (SlackInteraction.Unknown(other, payloadJson), Absent)
                    case Absent         => (SlackInteraction.Unknown("", payloadJson), Absent)
            case _ => (SlackInteraction.Unknown("", frame), Absent)
        end match
    end decodeInteraction

    private def decodeBlockActions(frame: String, payloadJson: String)(using Frame): SlackInteraction < Sync =
        Json.decode[SlackPayloadEnvelope[WireBlockActions]](frame) match
            case Result.Success(env) =>
                val w = env.payload
                w.user.id match
                    case Present(u) =>
                        val actions = w.actions.map(a =>
                            SlackInteraction.Action(a.action_id, a.block_id, a.value)
                        )
                        SlackInteraction.BlockActions(
                            SlackId.UserId(u),
                            SlackId.TriggerId(w.trigger_id.getOrElse("")),
                            w.channel.id.map(SlackId.ChannelId(_)),
                            actions
                        )
                    case Absent =>
                        Log.warn(s"SlackWire: malformed block_actions; preserving as Unknown").andThen(
                            SlackInteraction.Unknown("block_actions", payloadJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed block_actions; preserving as Unknown").andThen(
                    SlackInteraction.Unknown("block_actions", payloadJson)
                )

    private def decodeViewSubmission(frame: String, payloadJson: String)(using Frame): SlackInteraction < Sync =
        Json.decode[SlackPayloadEnvelope[WireViewSubmission]](frame) match
            case Result.Success(env) =>
                val w = env.payload
                w.user.id match
                    case Present(u) =>
                        SlackInteraction.ViewSubmission(
                            SlackId.UserId(u),
                            SlackId.ViewId(w.view.id.getOrElse("")),
                            SlackRawJson.nestedJson(frame, Seq("payload", "view", "state"), "{}")
                        )
                    case Absent =>
                        Log.warn(s"SlackWire: malformed view_submission; preserving as Unknown").andThen(
                            SlackInteraction.Unknown("view_submission", payloadJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed view_submission; preserving as Unknown").andThen(
                    SlackInteraction.Unknown("view_submission", payloadJson)
                )

    private def decodeViewClosed(frame: String, payloadJson: String)(using Frame): SlackInteraction < Sync =
        Json.decode[SlackPayloadEnvelope[WireViewClosed]](frame) match
            case Result.Success(env) =>
                val w = env.payload
                w.user.id match
                    case Present(u) =>
                        SlackInteraction.ViewClosed(
                            SlackId.UserId(u),
                            SlackId.ViewId(w.view.id.getOrElse("")),
                            w.is_cleared
                        )
                    case Absent =>
                        Log.warn(s"SlackWire: malformed view_closed; preserving as Unknown").andThen(
                            SlackInteraction.Unknown("view_closed", payloadJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed view_closed; preserving as Unknown").andThen(
                    SlackInteraction.Unknown("view_closed", payloadJson)
                )

    private def decodeShortcut(frame: String, payloadJson: String)(using Frame): SlackInteraction < Sync =
        Json.decode[SlackPayloadEnvelope[WireShortcut]](frame) match
            case Result.Success(env) =>
                val w = env.payload
                w.user.id match
                    case Present(u) =>
                        SlackInteraction.Shortcut(
                            SlackId.UserId(u),
                            SlackId.TriggerId(w.trigger_id.getOrElse("")),
                            w.callback_id.getOrElse("")
                        )
                    case Absent =>
                        Log.warn(s"SlackWire: malformed shortcut; preserving as Unknown").andThen(
                            SlackInteraction.Unknown("shortcut", payloadJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed shortcut; preserving as Unknown").andThen(
                    SlackInteraction.Unknown("shortcut", payloadJson)
                )

    private def decodeMessageAction(frame: String, payloadJson: String)(using Frame): SlackInteraction < Sync =
        Json.decode[SlackPayloadEnvelope[WireMessageAction]](frame) match
            case Result.Success(env) =>
                val w = env.payload
                w.user.id match
                    case Present(u) =>
                        SlackInteraction.MessageAction(
                            SlackId.UserId(u),
                            SlackId.TriggerId(w.trigger_id.getOrElse("")),
                            w.callback_id.getOrElse(""),
                            SlackId.ChannelId(w.channel.id.getOrElse("")),
                            SlackId.Ts(w.message.ts.getOrElse(""))
                        )
                    case Absent =>
                        Log.warn(s"SlackWire: malformed message_action; preserving as Unknown").andThen(
                            SlackInteraction.Unknown("message_action", payloadJson)
                        )
                end match
            case _ =>
                Log.warn(s"SlackWire: malformed message_action; preserving as Unknown").andThen(
                    SlackInteraction.Unknown("message_action", payloadJson)
                )

    private def decodeSlash(frame: String, h: SlackWireHeader)(using Frame): Decoded < Sync =
        meta(h) match
            case Absent => Decoded.Envelope(SlackEnvelope.Unknown("slash_commands", frame), ackable = false, responseUrl = Absent)
            case Present(m) =>
                Json.decode[SlackPayloadEnvelope[WireSlashCommand]](frame) match
                    case Result.Success(env) =>
                        val w = env.payload
                        val cmd = SlackCommand(
                            w.command,
                            w.text,
                            SlackId.ChannelId(w.channel_id.getOrElse("")),
                            SlackId.UserId(w.user_id.getOrElse("")),
                            SlackId.TriggerId(w.trigger_id.getOrElse("")),
                            w.response_url.getOrElse("")
                        )
                        Decoded.Envelope(SlackEnvelope.SlashCommand(m, cmd), ackable = true, responseUrl = Absent)
                    case _ =>
                        Decoded.Envelope(
                            SlackEnvelope.SlashCommand(
                                m,
                                SlackCommand("", "", SlackId.ChannelId(""), SlackId.UserId(""), SlackId.TriggerId(""), "")
                            ),
                            ackable = true,
                            responseUrl = Absent
                        )

    /** Encode exactly one outbound ack frame from the handler's returned `SlackAck`.
      * The bare ack emits `{"envelope_id":"<id>"}` (Slack's snake_case wire key).
      * `BlockActionsResponse` sends a bare ack; the message update is POSTed by the
      * engine to the captured `response_url` separately. `ViewResponse` and
      * `CommandResponse` carry their payloads inline as NATIVE Slack JSON (snake_case
      * keys, Block Kit blocks as a real JSON array), reusing the Web API request-body
      * DTOs (`Slack.ViewBody`, `Slack.PostMessageBody`) so the ack payload is byte-shaped
      * exactly like the corresponding Web API call.
      *
      * Building the native payload parses the message/view raw `blocksJson`, which can
      * fail with a typed `SlackDecodeException`, so this is effectful in `Abort`.
      */
    def encodeAck(envelopeId: SlackId.EnvelopeId, ack: SlackAck)(using Frame): String < Abort[SlackException] =
        val envId = envelopeId.value
        ack match
            case SlackAck.Ack                     => Json.encode(AckFrame(envId))
            case SlackAck.BlockActionsResponse(_) => Json.encode(AckFrame(envId))
            case SlackAck.CommandResponse(message) =>
                Slack.parseBlocks(message.blocksJson).map { blocks =>
                    Json.encode(AckMessage(envId, Slack.PostMessageBody(message.channel, message.text, blocks, message.threadTs)))
                }
            case SlackAck.ViewResponse(action) =>
                action match
                    case SlackAck.ViewAction.Clear =>
                        Json.encode(AckClear(envId, ViewActionClear("clear")))
                    case SlackAck.ViewAction.Errors(byBlock) =>
                        Json.encode(AckErrors(envId, ViewActionErrors("errors", byBlock)))
                    case SlackAck.ViewAction.Update(view) =>
                        Slack.encodeView(view).map(vb => Json.encode(AckView(envId, ViewActionView("update", vb))))
                    case SlackAck.ViewAction.Push(view) =>
                        Slack.encodeView(view).map(vb => Json.encode(AckView(envId, ViewActionView("push", vb))))
        end match
    end encodeAck

    // Ack frame DTOs: envelope_id field uses snake_case to match the Slack wire. Each
    // payload-bearing frame carries a concretely-typed native DTO whose derived Schema
    // serializes to the real Slack shape (snake_case keys, blocks as a JSON array),
    // never the tagged Structure.Value enum shape. One frame type per payload because
    // the Schema derivation macro does not accept a generic payload parameter.
    final private[kyo] case class AckFrame(envelope_id: String) derives Schema
    final private[kyo] case class AckClear(envelope_id: String, payload: ViewActionClear) derives Schema
    final private[kyo] case class AckErrors(envelope_id: String, payload: ViewActionErrors) derives Schema
    final private[kyo] case class AckView(envelope_id: String, payload: ViewActionView) derives Schema
    final private[kyo] case class AckMessage(envelope_id: String, payload: Slack.PostMessageBody) derives Schema

    final private[kyo] case class ViewActionErrors(response_action: String, errors: Map[String, String]) derives Schema
    final private[kyo] case class ViewActionView(response_action: String, view: Slack.ViewBody) derives Schema
    final private[kyo] case class ViewActionClear(response_action: String) derives Schema

    /** The `apps.connections.open` request body (empty) and the typed response
      * envelope; the bot opens the socket with the app-level token in the header.
      */
    final private[kyo] case class ConnectionsOpenBody() derives Schema
    final private[kyo] case class ConnectionsOpenResp(ok: Boolean = false, url: Maybe[String] = Absent, error: Maybe[String] = Absent)
        derives Schema

    def encodeConnectionsOpenBody(using Frame): String = Json.encode(ConnectionsOpenBody())

    def decodeConnectionsOpen(frame: String)(using Frame): Result[SlackException.SlackHandshakeException, String] =
        Json.decode[ConnectionsOpenResp](frame) match
            case Result.Success(r) if r.ok =>
                r.url match
                    case Present(u) => Result.succeed(u)
                    case Absent     => Result.fail(new SlackException.SlackHandshakeException("apps.connections.open ok but no url"))
            case Result.Success(r) =>
                Result.fail(new SlackException.SlackHandshakeException(s"apps.connections.open failed: ${r.error.getOrElse("unknown")}"))
            case other =>
                Result.fail(new SlackException.SlackHandshakeException(s"apps.connections.open response decode failed: $other"))

end SlackWire

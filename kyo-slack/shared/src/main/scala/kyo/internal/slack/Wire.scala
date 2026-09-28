package kyo.internal.slack

import kyo.*

/** The Socket Mode frame codec: permissive-header decode then typed-or-raw
  * sub-decode per envelope type, plus the ack-frame encoder. Pure functions
  * over JSON strings; no effect coupling beyond `Frame` for `Json` and `Log`.
  *
  * The decode never aborts on a malformed payload: a known type whose inner payload
  * fails decode yields a typed `Unknown` carrying the raw JSON; a structurally
  * uncorrelatable frame (not valid JSON, or no recoverable type) yields `Skip` with
  * a reason the engine logs. A decode failure is surfaced as a `SlackDecodeException`
  * only on a Web API answer, never here.
  *
  * The internal wire DTOs use snake_case field names that match the Slack wire
  * verbatim (e.g. `envelope_id`, `trigger_id`). The public-facing types use
  * idiomatic camelCase; the codec maps between the two explicitly.
  */
private[kyo] object Wire:

    /** The result of decoding one inbound frame. `Envelope` carries the typed
      * envelope and whether it is ackable (it carries a `Meta.envelopeId`). `Skip`
      * carries a reason for the structurally-uncorrelatable case.
      */
    enum Decoded derives CanEqual:
        case Envelope(value: SlackEnvelope[?], ackable: Boolean)
        case Skip(reason: String)

    // Internal wire DTOs: snake_case field names match Slack's wire format.
    // All fields are named exactly as Slack sends them so the derived Schema's JSON
    // keys are the wire keys with zero renaming needed.

    /** Permissive routing header: reads only the routing and meta fields, tolerating
      * all other fields via the permissive decoder. Field names are snake_case to
      * match the Slack wire (`envelope_id`, `accepts_response_payload`, etc.).
      */
    final private[kyo] case class WireHeader(
        `type`: Maybe[String] = Absent,
        envelope_id: Maybe[String] = Absent,
        accepts_response_payload: Maybe[Boolean] = Absent,
        retry_attempt: Maybe[Int] = Absent,
        retry_reason: Maybe[String] = Absent
    ) derives Schema

    /** Lightweight probe of the `event_callback` wrapper: reads only `payload.event_id` and `payload.event.type`. */
    final private[kyo] case class EventTypeProbe(event_id: Maybe[String] = Absent, event: EventTypeOnly = EventTypeOnly()) derives Schema
    final private[kyo] case class EventTypeOnly(`type`: Maybe[String] = Absent) derives Schema

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

    // reaction_added nests the reacted-to message coordinates inside an `item` object:
    // {"type":"message","channel":"C..","ts":".."}. Model the nesting so item.channel/item.ts
    // decode, rather than reading non-existent flat top-level fields.
    final private[kyo] case class WireReactionItem(
        channel: Maybe[String] = Absent,
        ts: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireReactionAdded(
        reaction: Maybe[String] = Absent,
        user: Maybe[String] = Absent,
        item: Maybe[WireReactionItem] = Absent
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

    /** Interactive payload discriminant probe. */
    final private[kyo] case class InteractivePayload(`type`: Maybe[String] = Absent) derives Schema

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
      * reads only the `view.id`. The submitted form `view.state` is a free-form object, read
      * separately from the frame's tree.
      */
    final private[kyo] case class WireViewRef(id: Maybe[String] = Absent) derives Schema

    final private[kyo] case class WireBlockActions(
        user: WireUserRef = WireUserRef(),
        trigger_id: Maybe[String] = Absent,
        channel: WireChannelRef = WireChannelRef(),
        // Present only when the action fired from inside a modal; the permissive decoder
        // leaves it an empty ref (id Absent) for a channel-message block_actions.
        view: WireViewRef = WireViewRef(),
        actions: Chunk[WireAction] = Chunk.empty,
        message: WireMessageRef = WireMessageRef(),
        response_url: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireAction(
        action_id: Maybe[String] = Absent,
        block_id: Maybe[String] = Absent,
        value: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class WireViewSubmission(
        user: WireUserRef = WireUserRef(),
        view: WireViewRef = WireViewRef()
    ) derives Schema

    final private[kyo] case class WireViewClosed(
        user: WireUserRef = WireUserRef(),
        view: WireViewRef = WireViewRef(),
        is_cleared: Maybe[Boolean] = Absent
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
        message: WireMessageRef = WireMessageRef(),
        response_url: Maybe[String] = Absent
    ) derives Schema

    /** The `message` object on a block_actions or message_action payload: its `ts` is the
      * timestamp of the message the interaction came from.
      */
    final private[kyo] case class WireMessageRef(ts: Maybe[String] = Absent) derives Schema

    /** Slash command payload DTO: snake_case names matching the Slack wire. */
    final private[kyo] case class WireSlashCommand(
        command: Maybe[String] = Absent,
        text: Maybe[String] = Absent,
        channel_id: Maybe[String] = Absent,
        user_id: Maybe[String] = Absent,
        trigger_id: Maybe[String] = Absent,
        response_url: Maybe[String] = Absent
    ) derives Schema

    final private[kyo] case class HelloFrame(
        num_connections: Maybe[Int] = Absent,
        connection_info: HelloConnInfo = HelloConnInfo(),
        debug_info: Maybe[HelloDebugInfo] = Absent
    ) derives Schema

    final private[kyo] case class HelloConnInfo(app_id: Maybe[String] = Absent) derives Schema

    final private[kyo] case class HelloDebugInfo(
        host: Maybe[String] = Absent,
        started: Maybe[String] = Absent,
        build_number: Maybe[Int] = Absent,
        approximate_connection_time: Maybe[Int] = Absent
    ) derives Schema

    final private[kyo] case class DisconnectFrame(reason: Maybe[String] = Absent) derives Schema

    /** Decode one inbound text frame into a `Decoded`. Never aborts. The frame is parsed once; every typed read and
      * every raw sub-object below comes from that one tree.
      */
    def decode(frame: String)(using Frame): Decoded < Sync =
        // A reason names the frame by its size, its position or a failure's kind only: the frame can hold a
        // response_url or a token.
        RawJson.parse(frame) match
            case Result.Failure(f)    => Decoded.Skip(s"frame is not JSON: ${f.problem.productPrefix} at position ${f.position}")
            case Result.Success(tree) =>
                Structure.decode[WireHeader](tree) match
                    case Result.Success(h) =>
                        h.`type` match
                            case Present("hello")          => decodeHello(frame, tree)
                            case Present("disconnect")     => decodeDisconnect(tree)
                            case Present("events_api")     => decodeEventsApi(frame, tree, h)
                            case Present("interactive")    => decodeInteractive(frame, tree, h)
                            case Present("slash_commands") => decodeSlash(frame, tree, h)
                            case Present(other)            => unknownEnvelope(other, frame, meta(h))
                            case Absent                    => Decoded.Skip(s"frame has no type field (${frame.length} characters)")
                    case Result.Failure(err) => Decoded.Skip(s"header decode failed: ${err.getClass.getSimpleName}")
                    case Result.Panic(ex)    => Decoded.Skip(s"header decode panicked: ${ex.getClass.getSimpleName}")

    /** The value at `path` in the frame's tree, decoded as `A`, or `Absent` when the path is absent or does not decode. */
    private def read[A: Schema](tree: Structure.Value, path: String*)(using Frame): Maybe[A] =
        RawJson.at(tree, path*).flatMap(v => Structure.decode[A](v).toMaybe)

    /** The raw JSON text of the value at `path`, or of the whole frame when the path is absent. */
    private def rawAt(frame: String, tree: Structure.Value, path: String*)(using Frame): SlackRawJson =
        SlackRawJson(RawJson.at(tree, path*).fold(frame)(Json.encode(_)))

    private def meta(h: WireHeader)(using Frame): Maybe[SlackEnvelope.Meta] =
        id(h.envelope_id)(SlackId.EnvelopeId(_)).map { envelopeId =>
            SlackEnvelope.Meta(
                envelopeId,
                h.accepts_response_payload,
                h.retry_attempt,
                h.retry_reason
            )
        }

    /** An envelope-level `Unknown` carrying the whole frame when the frame has an `envelope_id` (Slack waits for its ack and
      * delivers an unacked one again), and an `UnknownFrame`, which takes no answer, when it has none.
      */
    private def unknownEnvelope(kind: String, frame: String, meta: Maybe[SlackEnvelope.Meta]): Decoded =
        meta match
            case Present(m) => Decoded.Envelope(SlackEnvelope.Unknown(kind, SlackRawJson(frame), m), ackable = true)
            case Absent     => Decoded.Envelope(SlackEnvelope.UnknownFrame(kind, SlackRawJson(frame)), ackable = false)

    /** An id, or a `response_url`, read from a payload. An absent one and one Slack sent empty are
      * both none: an empty value names nothing, so a payload that needs it is its kind's `Unknown`.
      */
    private def id[I](raw: Maybe[String])(make: String => I): Maybe[I] = raw.filter(_.nonEmpty).map(make)

    /** The typed case a payload decoded to, or, when a field its kind requires is missing, the kind's
      * `Unknown` carrying the whole raw payload, with a warn line naming the kind only.
      */
    private def typedOr[A](label: String, unknown: => A)(typed: Maybe[A])(using Frame): A < Sync =
        typed match
            case Present(a) => a
            case Absent     => Log.warn(s"Wire: malformed $label; preserving as Unknown").andThen(unknown)

    private def decodeHello(frame: String, tree: Structure.Value)(using Frame): Decoded < Sync =
        val hello = read[HelloFrame](tree).flatMap { f =>
            for
                connections <- f.num_connections
                app         <- id(f.connection_info.app_id)(SlackId.AppId(_))
            yield SlackEnvelope.Hello(connections, app, f.debug_info.flatMap(_.host))
        }
        hello match
            case Present(h) => Decoded.Envelope(h, ackable = false)
            case Absent     => Log.warn("Wire: malformed hello; preserving as Unknown").andThen(unknownEnvelope("hello", frame, Absent))
    end decodeHello

    private def decodeDisconnect(tree: Structure.Value)(using Frame): Decoded < Sync =
        val reason = read[DisconnectFrame](tree, "payload").flatMap(_.reason)
            .orElse(read[DisconnectFrame](tree).flatMap(_.reason))
        val dr = reason match
            case Present("warning")           => SlackEnvelope.DisconnectReason.Warning
            case Present("refresh_requested") => SlackEnvelope.DisconnectReason.RefreshRequested
            case Present("link_disabled")     => SlackEnvelope.DisconnectReason.LinkDisabled
            case Present(other)               => SlackEnvelope.DisconnectReason.Unknown(other)
            case Absent                       => SlackEnvelope.DisconnectReason.Unspecified
        Decoded.Envelope(SlackEnvelope.Disconnect(dr), ackable = false)
    end decodeDisconnect

    private def decodeEventsApi(frame: String, tree: Structure.Value, h: WireHeader)(using Frame): Decoded < Sync =
        meta(h) match
            case Absent     => unknownEnvelope("events_api", frame, Absent)
            case Present(m) =>
                val probe = read[EventTypeProbe](tree, "payload")
                val typed =
                    for
                        kind    <- probe.flatMap(_.event.`type`).filter(_.nonEmpty)
                        eventId <- probe.flatMap(p => id(p.event_id)(SlackId.EventId(_)))
                    yield (kind, eventId)
                typed match
                    case Present((kind, eventId)) =>
                        decodeEvent(frame, tree, kind).map { event =>
                            Decoded.Envelope(SlackEnvelope.EventsApi(m, event, eventId), ackable = true)
                        }
                    case Absent =>
                        Log.warn("Wire: malformed events_api envelope; preserving as Unknown").andThen(
                            unknownEnvelope("events_api", frame, Present(m))
                        )
                end match

    /** Dispatch on the inner event's type to the typed `SlackEvent` leaf decoded via a wire DTO.
      * Falls through to `SlackEvent.Unknown(type, eventJson)` on an unmodeled event, or one missing a
      * field its type requires. `eventJson` is the inner `payload.event` object, encoded only on that path.
      */
    private def decodeEvent(frame: String, tree: Structure.Value, kind: String)(using Frame): SlackEvent < Sync =
        def eventJson                                            = rawAt(frame, tree, "payload", "event")
        def event(decoded: Maybe[SlackEvent]): SlackEvent < Sync =
            typedOr(s"$kind event", SlackEvent.Unknown(kind, eventJson))(decoded)
        kind match
            case "message" =>
                event(read[WireMessage](tree, "payload", "event").flatMap { w =>
                    for
                        ch   <- id(w.channel)(SlackId.ChannelId(_))
                        u    <- id(w.user)(SlackId.UserId(_))
                        text <- w.text
                        ts   <- id(w.ts)(SlackTs(_))
                    yield SlackEvent.Message(ch, u, text, ts, id(w.thread_ts)(SlackTs(_)))
                    end for
                })
            case "app_mention" =>
                event(read[WireAppMention](tree, "payload", "event").flatMap { w =>
                    for
                        ch   <- id(w.channel)(SlackId.ChannelId(_))
                        u    <- id(w.user)(SlackId.UserId(_))
                        text <- w.text
                        ts   <- id(w.ts)(SlackTs(_))
                    yield SlackEvent.AppMention(ch, u, text, ts)
                    end for
                })
            case "reaction_added" =>
                event(read[WireReactionAdded](tree, "payload", "event").flatMap { w =>
                    for
                        u        <- id(w.user)(SlackId.UserId(_))
                        reaction <- w.reaction
                        ch       <- id(w.item.flatMap(_.channel))(SlackId.ChannelId(_))
                        ts       <- id(w.item.flatMap(_.ts))(SlackTs(_))
                    yield SlackEvent.ReactionAdded(u, reaction, ch, ts)
                    end for
                })
            case "app_home_opened" =>
                event(read[WireAppHomeOpened](tree, "payload", "event").flatMap { w =>
                    for
                        u  <- id(w.user)(SlackId.UserId(_))
                        ch <- id(w.channel)(SlackId.ChannelId(_))
                    yield SlackEvent.AppHomeOpened(u, ch, w.tab)
                    end for
                })
            case "member_joined_channel" =>
                event(read[WireMemberJoinedChannel](tree, "payload", "event").flatMap { w =>
                    for
                        u  <- id(w.user)(SlackId.UserId(_))
                        ch <- id(w.channel)(SlackId.ChannelId(_))
                    yield SlackEvent.MemberJoinedChannel(u, ch, id(w.inviter)(SlackId.UserId(_)))
                    end for
                })
            case other => SlackEvent.Unknown(other, eventJson)
        end match
    end decodeEvent

    /** Read `payload.type` and dispatch to the typed `SlackInteraction`. A payload with no type, or
      * whose envelope does not decode, names no kind, so the envelope is an `Unknown` carrying its
      * `Meta`.
      */
    private def decodeInteractive(frame: String, tree: Structure.Value, h: WireHeader)(using Frame): Decoded < Sync =
        meta(h) match
            case Absent     => unknownEnvelope("interactive", frame, Absent)
            case Present(m) =>
                val kindOf: Maybe[String] = read[InteractivePayload](tree, "payload").flatMap(_.`type`).filter(_.nonEmpty)
                kindOf match
                    case Present(kind) =>
                        decodeInteraction(frame, tree, kind).map { interaction =>
                            Decoded.Envelope(SlackEnvelope.Interactive(m, interaction), ackable = true)
                        }
                    case Absent =>
                        Log.warn("Wire: malformed interactive envelope; preserving as Unknown").andThen(
                            unknownEnvelope("interactive", frame, Present(m))
                        )
                end match

    /** The typed interaction for `kind`, or `SlackInteraction.Unknown(kind, payloadJson)` for an
      * unmodeled kind or a payload missing a field its kind requires. `payloadJson` is the inner
      * `payload` object, encoded only on that path.
      */
    private def decodeInteraction(frame: String, tree: Structure.Value, kind: String)(using Frame): SlackInteraction < Sync =
        def payloadJson                                                            = rawAt(frame, tree, "payload")
        def interaction(decoded: Maybe[SlackInteraction]): SlackInteraction < Sync =
            typedOr(kind, SlackInteraction.Unknown(kind, payloadJson))(decoded)
        kind match
            case "block_actions" =>
                interaction(read[WireBlockActions](tree, "payload").flatMap { w =>
                    for
                        u       <- id(w.user.id)(SlackId.UserId(_))
                        trigger <- id(w.trigger_id)(SlackId.TriggerId(_))
                        actions <- actionsOf(w.actions)
                    yield SlackInteraction.BlockActions(
                        u,
                        trigger,
                        id(w.channel.id)(SlackId.ChannelId(_)),
                        id(w.view.id)(SlackId.ViewId(_)),
                        actions,
                        id(w.message.ts)(SlackTs(_)),
                        id(w.response_url)(SlackResponseUrl(_))
                    )
                    end for
                })
            case "view_submission" =>
                interaction(read[WireViewSubmission](tree, "payload").flatMap { w =>
                    for
                        u    <- id(w.user.id)(SlackId.UserId(_))
                        view <- id(w.view.id)(SlackId.ViewId(_))
                    yield SlackInteraction.ViewSubmission(
                        u,
                        view,
                        RawJson.at(tree, "payload", "view", "state").map(state => SlackRawJson(Json.encode(state)))
                    )
                    end for
                })
            case "view_closed" =>
                interaction(read[WireViewClosed](tree, "payload").flatMap { w =>
                    for
                        u       <- id(w.user.id)(SlackId.UserId(_))
                        view    <- id(w.view.id)(SlackId.ViewId(_))
                        cleared <- w.is_cleared
                    yield SlackInteraction.ViewClosed(u, view, cleared)
                    end for
                })
            case "shortcut" =>
                interaction(read[WireShortcut](tree, "payload").flatMap { w =>
                    for
                        u        <- id(w.user.id)(SlackId.UserId(_))
                        trigger  <- id(w.trigger_id)(SlackId.TriggerId(_))
                        callback <- w.callback_id
                    yield SlackInteraction.Shortcut(u, trigger, callback)
                    end for
                })
            case "message_action" =>
                interaction(read[WireMessageAction](tree, "payload").flatMap { w =>
                    for
                        u        <- id(w.user.id)(SlackId.UserId(_))
                        trigger  <- id(w.trigger_id)(SlackId.TriggerId(_))
                        callback <- w.callback_id
                        channel  <- id(w.channel.id)(SlackId.ChannelId(_))
                        ts       <- id(w.message.ts)(SlackTs(_))
                    yield SlackInteraction.MessageAction(u, trigger, callback, channel, ts, id(w.response_url)(SlackResponseUrl(_)))
                    end for
                })
            case other => SlackInteraction.Unknown(other, payloadJson)
        end match
    end decodeInteraction

    /** Every action of a block_actions payload, or `Absent` when any lacks the ids its kind requires. */
    private def actionsOf(wire: Chunk[WireAction]): Maybe[Chunk[SlackInteraction.Action]] =
        val decoded = wire.map { a =>
            for
                action <- id(a.action_id)(SlackId.ActionId(_))
                block  <- id(a.block_id)(SlackId.BlockId(_))
            yield SlackInteraction.Action(action, block, a.value)
        }
        if decoded.forall(_.isDefined) then Present(decoded.collect { case Present(a) => a }) else Absent
    end actionsOf

    private def decodeSlash(frame: String, tree: Structure.Value, h: WireHeader)(using Frame): Decoded < Sync =
        meta(h) match
            case Absent     => unknownEnvelope("slash_commands", frame, Absent)
            case Present(m) =>
                val command = read[WireSlashCommand](tree, "payload").flatMap { w =>
                    for
                        name    <- w.command
                        text    <- w.text
                        channel <- id(w.channel_id)(SlackId.ChannelId(_))
                        user    <- id(w.user_id)(SlackId.UserId(_))
                        trigger <- id(w.trigger_id)(SlackId.TriggerId(_))
                    yield SlackCommand(name, text, channel, user, trigger, id(w.response_url)(SlackResponseUrl(_)))
                    end for
                }
                command match
                    case Present(cmd) => Decoded.Envelope(SlackEnvelope.SlashCommand(m, cmd), ackable = true)
                    case Absent       =>
                        Log.warn("Wire: malformed slash_commands envelope; preserving as Unknown").andThen(
                            unknownEnvelope("slash_commands", frame, Present(m))
                        )
                end match

    /** Encode exactly one outbound ack frame from the handler's returned `SlackAck`.
      * The bare ack emits `{"envelope_id":"<id>"}` (Slack's snake_case wire key).
      * `ViewResponse` and `CommandResponse` carry their payloads inline as NATIVE Slack JSON (snake_case
      * keys, Block Kit blocks as a real JSON array); a view reuses the Web API's
      * `Slack.ViewBody`.
      */
    def encodeAck(envelopeId: SlackId.EnvelopeId, ack: SlackAck)(using Frame): String =
        val envId = envelopeId.value
        ack match
            case SlackAck.Ack                                => Json.encode(AckFrame(envId))
            case SlackAck.CommandResponse(reply, visibility) =>
                val responseType =
                    visibility match
                        case SlackAck.CommandResponse.Visibility.Ephemeral => "ephemeral"
                        case SlackAck.CommandResponse.Visibility.InChannel => "in_channel"
                Json.encode(AckPayload(envId, CommandReplyBody(responseType, reply.text, Slack.replyBlocks(reply))))
            case SlackAck.ViewResponse(action) =>
                action match
                    case SlackAck.ViewAction.Clear =>
                        Json.encode(AckPayload(envId, ViewActionClear("clear")))
                    case SlackAck.ViewAction.Errors(byBlock) =>
                        Json.encode(AckPayload(envId, ViewActionValidation("errors", byBlock.map((block, text) => block.value -> text))))
                    case SlackAck.ViewAction.Update(view) =>
                        Json.encode(AckPayload(envId, ViewActionView("update", Slack.encodeView(view))))
                    case SlackAck.ViewAction.Push(view) =>
                        Json.encode(AckPayload(envId, ViewActionView("push", Slack.encodeView(view))))
        end match
    end encodeAck

    final private[kyo] case class AckFrame(envelope_id: String) derives Schema
    final private[kyo] case class AckPayload[P](envelope_id: String, payload: P) derives Schema

    final private[kyo] case class CommandReplyBody(response_type: String, text: String, blocks: Maybe[Structure.Value]) derives Schema

    // Slack's wire key for per-block validation messages is `errors`.
    final private[kyo] case class ViewActionValidation(response_action: String, errors: Map[String, String]) derives Schema
    final private[kyo] case class ViewActionView(response_action: String, view: Slack.ViewBody) derives Schema
    final private[kyo] case class ViewActionClear(response_action: String) derives Schema

end Wire

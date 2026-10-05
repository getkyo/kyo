package kyo.internal.slack

import kyo.*
import kyo.SlackLiterals.*

class WireTest extends kyo.test.Test[Any]:

    private def meta(id: String): SlackId.EnvelopeId = SlackId.EnvelopeId(id)

    /** The `event_id` the `events_api` fixtures carry: `Ev` followed by the envelope id. */
    private def eventId(envelopeId: String): SlackId.EventId = SlackId.EventId(s"Ev$envelopeId")

    /** An `events_api` envelope with only its id and payload. */
    private def events(envelopeId: SlackId.EnvelopeId, event: SlackEvent, eventId: SlackId.EventId): SlackEnvelope.EventsApi =
        SlackEnvelope.EventsApi(envelopeId, SlackEnvelope.EventsApi.Payload(eventId, event))

    private def envelope(value: SlackEnvelope[?], ackable: Boolean): Wire.Decoded =
        Wire.Decoded.Envelope(value, ackable)

    private def user(id: String): SlackInteraction.User = SlackInteraction.User(SlackId.UserId(id))

    private val connectionA1 = SlackEnvelope.Hello.ConnectionInfo(SlackId.AppId("A1"))

    "hello frame decodes to a non-ackable Hello" in {
        Wire.decode("""{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}""").map { d =>
            assert(d == envelope(SlackEnvelope.Hello(1, connectionA1), ackable = false))
        }
    }

    "hello frame with debug_info populates Hello.debugHost from the host" in {
        val frame =
            """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"},"debug_info":{"host":"applink-7","build_number":42}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.Hello(1, connectionA1, Present(SlackEnvelope.Hello.DebugInfo(Present("applink-7")))),
                ackable = false
            ))
        }
    }

    "events_api member_joined_channel with inviter populates MemberJoinedChannel.inviter" in {
        val frame = eventsApi("E10", """{"type":"member_joined_channel","user":"U1","channel":"C1","inviter":"U999"}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E10"),
                    SlackEvent.MemberJoinedChannel(SlackId.UserId("U1"), SlackId.ChannelId("C1"), Present(SlackId.UserId("U999"))),
                    eventId("E10")
                ),
                ackable = true
            ))
        }
    }

    "events_api message decodes to an ackable EventsApi carrying a typed Message" in {
        val frame = eventsApi("E1", """{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E1"),
                    SlackEvent.Message(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "hi", SlackTs("1.2")),
                    eventId("E1")
                ),
                ackable = true
            ))
        }
    }

    "events_api carries Slack's event_id on the delivered envelope" in {
        val frame =
            """{"type":"events_api","envelope_id":"E62","payload":{"type":"event_callback","event_id":"Ev0DISTINCT7","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
        Wire.decode(frame).map { d =>
            assert(d.toString.contains("Ev0DISTINCT7"), d.toString)
            assert(d == envelope(
                events(
                    meta("E62"),
                    SlackEvent.Message(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "hi", SlackTs("1.2")),
                    SlackId.EventId("Ev0DISTINCT7")
                ),
                ackable = true
            ))
        }
    }

    Chunk("absent" -> "", "empty" -> ",\"event_id\":\"\"").foreach { (label, idField) =>
        s"events_api whose event_id is $label is the envelope-level Unknown, acked" in {
            val frame =
                s"""{"type":"events_api","envelope_id":"E63","payload":{"type":"event_callback"$idField,"event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
            Wire.decode(frame).map { d =>
                assert(d == envelope(SlackEnvelope.Unknown("events_api", SlackRawJsonTest.of(frame), meta("E63")), ackable = true))
            }
        }
    }

    "events_api message in a thread decodes thread_ts into Message.threadTs" in {
        val frame = eventsApi("E60", """{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2","thread_ts":"1.0"}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E60"),
                    SlackEvent.Message(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "hi", SlackTs("1.2"), Present(SlackTs("1.0"))),
                    eventId("E60")
                ),
                ackable = true
            ))
        }
    }

    "events_api app_mention decodes into a typed AppMention" in {
        val frame = eventsApi("E61", """{"type":"app_mention","channel":"C1","user":"U1","text":"<@B1> deploy","ts":"1.2"}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E61"),
                    SlackEvent.AppMention(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "<@B1> deploy", SlackTs("1.2")),
                    eventId("E61")
                ),
                ackable = true
            ))
        }
    }

    "an events_api envelope carries accepts_response_payload and the retry attempt and reason" in {
        val frame =
            """{"type":"events_api","envelope_id":"E1","accepts_response_payload":true,"retry_attempt":2,"retry_reason":"timeout","payload":{"type":"event_callback","event_id":"EvE1","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.EventsApi(
                    SlackId.EnvelopeId("E1"),
                    SlackEnvelope.EventsApi.Payload(
                        eventId("E1"),
                        SlackEvent.Message(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "hi", SlackTs("1.2"))
                    ),
                    acceptsResponsePayload = Present(true),
                    retryAttempt = Present(2),
                    retryReason = Present("timeout")
                ),
                ackable = true
            ))
        }
    }

    // reaction_added nests channel/ts inside an `item` object on the real Slack wire.
    "events_api reaction_added decodes the nested item channel/ts into a typed ReactionAdded" in {
        val frame = eventsApi(
            "E20",
            """{"type":"reaction_added","user":"U1","reaction":"rocket","item":{"type":"message","channel":"C1","ts":"1.2"}}"""
        )
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E20"),
                    SlackEvent.ReactionAdded(
                        SlackId.UserId("U1"),
                        "rocket",
                        SlackEvent.ReactionAdded.Item(SlackId.ChannelId("C1"), SlackTs("1.2"))
                    ),
                    eventId("E20")
                ),
                ackable = true
            ))
        }
    }

    "events_api with an unmodeled inner event yields SlackEvent.Unknown carrying only the inner event, still ackable" in {
        val frame = eventsApi("E3", """{"type":"team_join","user":{"id":"U9"}}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E3"),
                    SlackEvent.Unknown("team_join", SlackRawJsonTest.of("""{"type":"team_join","user":{"id":"U9"}}""")),
                    eventId("E3")
                ),
                ackable = true
            ))
        }
    }

    "events_api with a malformed known-type event yields SlackEvent.Unknown without aborting" in {
        // a message event missing its required channel field
        val frame = eventsApi("E4", """{"type":"message","user":"U1","text":"hi","ts":"1.2"}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E4"),
                    SlackEvent.Unknown("message", SlackRawJsonTest.of("""{"type":"message","user":"U1","text":"hi","ts":"1.2"}""")),
                    eventId("E4")
                ),
                ackable = true
            ))
        }
    }

    // Real Slack shape: user and channel are objects; trigger_id and response_url sit at the payload top level.
    "interactive block_actions decodes the real object-shaped user/channel, the message ts and the response_url" in {
        val frame =
            """{"type":"interactive","envelope_id":"E2","payload":{"type":"block_actions","user":{"id":"U1","username":"bob","team_id":"T1"},"trigger_id":"T1","team":{"id":"T1","domain":"d"},"channel":{"id":"C1","name":"general"},"message":{"ts":"1548261231.000200","text":"pick one"},"response_url":"https://hooks.slack.com/x","actions":[{"action_id":"a1","block_id":"b1","value":"v1","type":"button"}]}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(
                    meta("E2"),
                    SlackInteraction.BlockActions(
                        user("U1"),
                        SlackId.TriggerId("T1"),
                        Present(SlackId.ChannelId("C1")),
                        Absent,
                        Chunk(SlackInteraction.Action(SlackId.ActionId("a1"), SlackId.BlockId("b1"), Present("v1"))),
                        Present(SlackTs("1548261231.000200")),
                        Present(SlackResponseUrl("https://hooks.slack.com/x"))
                    )
                ),
                ackable = true
            ))
        }
    }

    "interactive block_actions from a modal captures the view id" in {
        val frame =
            """{"type":"interactive","envelope_id":"E2b","payload":{"type":"block_actions","user":{"id":"U1"},"trigger_id":"T1","view":{"id":"V9","type":"modal"},"actions":[{"action_id":"a1","block_id":"b1","type":"button"}]}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(
                    meta("E2b"),
                    SlackInteraction.BlockActions(
                        user("U1"),
                        SlackId.TriggerId("T1"),
                        Absent,
                        Present(SlackId.ViewId("V9")),
                        Chunk(SlackInteraction.Action(SlackId.ActionId("a1"), SlackId.BlockId("b1")))
                    )
                ),
                ackable = true
            ))
        }
    }

    "interactive view_submission decodes the nested view id and re-emits the state as native JSON" in {
        val frame =
            """{"type":"interactive","envelope_id":"E6","payload":{"type":"view_submission","team":{"id":"T1"},"user":{"id":"U1","username":"bob"},"trigger_id":"T6","view":{"id":"V1","type":"modal","callback_id":"cb1","state":{"values":{"b1":{"a1":{"type":"plain_text_input","value":"hello"}}}},"hash":"h"}}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(
                    meta("E6"),
                    SlackInteraction.ViewSubmission(
                        user("U1"),
                        SlackInteraction.ViewSubmission.View(
                            SlackId.ViewId("V1"),
                            Present(SlackRawJsonTest.of("""{"values":{"b1":{"a1":{"type":"plain_text_input","value":"hello"}}}}"""))
                        )
                    )
                ),
                ackable = true
            ))
        }
    }

    "a printed view_submission shows the state's length, not what the person typed" in {
        val typed = Seq("typed", "Q4nV").mkString
        val frame =
            s"""{"type":"interactive","envelope_id":"E7","payload":{"type":"view_submission","user":{"id":"U1"},"view":{"id":"V1","state":{"values":{"b1":{"a1":{"type":"plain_text_input","value":"$typed"}}}}}}}"""
        Wire.decode(frame).map {
            case Wire.Decoded.Envelope(env, _) => assert(!env.toString.contains(typed), env.toString)
            case other                         => fail(s"expected an envelope, got $other")
        }
    }

    "interactive view_closed decodes the nested view id and the top-level is_cleared" in {
        val frame =
            """{"type":"interactive","envelope_id":"E11","payload":{"type":"view_closed","team":{"id":"T1"},"user":{"id":"U1"},"view":{"id":"V1","callback_id":"cb1"},"is_cleared":true}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(
                    meta("E11"),
                    SlackInteraction.ViewClosed(user("U1"), SlackInteraction.ViewClosed.View(SlackId.ViewId("V1")), isCleared = true)
                ),
                ackable = true
            ))
        }
    }

    "interactive shortcut decodes the real object-shaped user with callback_id and trigger_id" in {
        val frame =
            """{"type":"interactive","envelope_id":"E12","payload":{"type":"shortcut","user":{"id":"U1","username":"bob"},"callback_id":"cb1","trigger_id":"T12","team":{"id":"T1"}}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(meta("E12"), SlackInteraction.Shortcut(user("U1"), SlackId.TriggerId("T12"), "cb1")),
                ackable = true
            ))
        }
    }

    "interactive message_action decodes the real object-shaped user/channel and nested message ts" in {
        val frame =
            """{"type":"interactive","envelope_id":"E13","payload":{"type":"message_action","callback_id":"cb1","trigger_id":"T13","response_url":"https://hooks.slack.com/y","user":{"id":"U1","name":"bob"},"channel":{"id":"C1","name":"general"},"team":{"id":"T1"},"message":{"ts":"1.2","text":"target"}}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(
                    meta("E13"),
                    SlackInteraction.MessageAction(
                        user("U1"),
                        SlackId.TriggerId("T13"),
                        "cb1",
                        SlackId.ChannelId("C1"),
                        SlackTs("1.2"),
                        Present(SlackResponseUrl("https://hooks.slack.com/y"))
                    )
                ),
                ackable = true
            ))
        }
    }

    "interactive with an unmodeled kind yields SlackInteraction.Unknown carrying only the inner payload" in {
        val frame =
            """{"type":"interactive","envelope_id":"E7","payload":{"type":"workflow_step_edit","user":{"id":"U1"}}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(
                    meta("E7"),
                    SlackInteraction.Unknown(
                        "workflow_step_edit",
                        SlackRawJsonTest.of("""{"type":"workflow_step_edit","user":{"id":"U1"}}""")
                    )
                ),
                ackable = true
            ))
        }
    }

    "slash_commands decodes to a SlashCommand carrying the command payload" in {
        val frame =
            """{"type":"slash_commands","envelope_id":"E8","payload":{"command":"/deploy","text":"prod","channel_id":"C1","user_id":"U1","trigger_id":"T8","response_url":"https://hooks/x"}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.SlashCommand(
                    meta("E8"),
                    SlackCommand(
                        "/deploy",
                        "prod",
                        SlackId.ChannelId("C1"),
                        SlackId.UserId("U1"),
                        SlackId.TriggerId("T8"),
                        Present(SlackResponseUrl("https://hooks/x"))
                    )
                ),
                ackable = true
            ))
        }
    }

    "a decoded command, block action and message action render no part of their response_url" in {
        // Built from parts so the secret's literal is off the source lines a failure renders.
        val secret = Seq("SECRET", "RESPONSE", "URL", "5e1f").mkString("-")
        val url    = s"https://hooks.slack.com/actions/T1/B1/$secret"
        val frames = Chunk(
            s"""{"type":"slash_commands","envelope_id":"E50","payload":{"command":"/deploy","text":"prod","channel_id":"C1","user_id":"U1","trigger_id":"T50","response_url":"$url"}}""",
            s"""{"type":"interactive","envelope_id":"E51","payload":{"type":"block_actions","user":{"id":"U1"},"trigger_id":"T51","channel":{"id":"C1"},"response_url":"$url","actions":[{"action_id":"a1","block_id":"b1"}]}}""",
            s"""{"type":"interactive","envelope_id":"E52","payload":{"type":"message_action","callback_id":"cb1","trigger_id":"T52","response_url":"$url","user":{"id":"U1"},"channel":{"id":"C1"},"message":{"ts":"1.2"}}}"""
        )
        Kyo.foreach(frames)(Wire.decode).map { decoded =>
            assert(decoded.size == 3)
            decoded.foreach(d => assert(!d.toString.contains(secret)))
            succeed
        }
    }

    Chunk("absent" -> "", "empty" -> ",\"response_url\":\"\"").foreach { (label, urlField) =>
        s"a command, block action and message action whose response_url is $label decode with responseUrl Absent" in {
            val frames = Chunk(
                s"""{"type":"slash_commands","envelope_id":"E53","payload":{"command":"/deploy","text":"prod","channel_id":"C1","user_id":"U1","trigger_id":"T53"$urlField}}""",
                s"""{"type":"interactive","envelope_id":"E54","payload":{"type":"block_actions","user":{"id":"U1"},"trigger_id":"T54"$urlField,"actions":[{"action_id":"a1","block_id":"b1"}]}}""",
                s"""{"type":"interactive","envelope_id":"E55","payload":{"type":"message_action","callback_id":"cb1","trigger_id":"T55"$urlField,"user":{"id":"U1"},"channel":{"id":"C1"},"message":{"ts":"1.2"}}}"""
            )
            Kyo.foreach(frames)(Wire.decode).map { decoded =>
                assert(decoded == Chunk(
                    envelope(
                        SlackEnvelope.SlashCommand(
                            meta("E53"),
                            SlackCommand("/deploy", "prod", SlackId.ChannelId("C1"), SlackId.UserId("U1"), SlackId.TriggerId("T53"), Absent)
                        ),
                        ackable = true
                    ),
                    envelope(
                        SlackEnvelope.Interactive(
                            meta("E54"),
                            SlackInteraction.BlockActions(
                                user("U1"),
                                SlackId.TriggerId("T54"),
                                Absent,
                                Absent,
                                Chunk(SlackInteraction.Action(SlackId.ActionId("a1"), SlackId.BlockId("b1")))
                            )
                        ),
                        ackable = true
                    ),
                    envelope(
                        SlackEnvelope.Interactive(
                            meta("E55"),
                            SlackInteraction.MessageAction(
                                user("U1"),
                                SlackId.TriggerId("T55"),
                                "cb1",
                                SlackId.ChannelId("C1"),
                                SlackTs("1.2")
                            )
                        ),
                        ackable = true
                    )
                ))
            }
        }
    }

    "disconnect frames decode each reason, non-ackable, with an unmodeled reason kept raw" in {
        val reasons = Chunk("warning", "refresh_requested", "link_disabled", "surprise")
        Kyo.foreach(reasons)(r => Wire.decode(s"""{"type":"disconnect","reason":"$r"}""")).map { decoded =>
            assert(decoded == Chunk(
                SlackEnvelope.DisconnectReason.Warning,
                SlackEnvelope.DisconnectReason.RefreshRequested,
                SlackEnvelope.DisconnectReason.LinkDisabled,
                SlackEnvelope.DisconnectReason.Unknown("surprise")
            ).map(r => envelope(SlackEnvelope.Disconnect(r), ackable = false)))
        }
    }

    "an unmodeled envelope type with an envelope_id yields an ackable SlackEnvelope.Unknown carrying the raw frame and its Meta" in {
        val frame = """{"type":"workflow_step_execute","envelope_id":"E9","payload":{}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(SlackEnvelope.Unknown("workflow_step_execute", SlackRawJsonTest.of(frame), meta("E9")), ackable = true))
        }
    }

    "an unmodeled envelope type without an envelope_id yields a non-ackable SlackEnvelope.UnknownFrame" in {
        val frame = """{"type":"workflow_step_execute","payload":{}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(SlackEnvelope.UnknownFrame("workflow_step_execute", SlackRawJsonTest.of(frame)), ackable = false))
        }
    }

    "an empty envelope_id is no envelope_id: every envelope type carrying one yields a non-ackable UnknownFrame" in {
        val frames = Chunk("workflow_step_execute", "events_api", "interactive", "slash_commands").map { kind =>
            kind -> s"""{"type":"$kind","envelope_id":"","payload":{}}"""
        }
        Kyo.foreach(frames)((_, frame) => Wire.decode(frame)).map { decoded =>
            assert(decoded ==
                frames.map((kind, frame) => envelope(SlackEnvelope.UnknownFrame(kind, SlackRawJsonTest.of(frame)), ackable = false)))
        }
    }

    // --- A field a payload's kind requires ---

    /** One leaf per field: the frame without it, or with an id Slack sent empty, decodes to `expected`. */
    private case class Row(name: String, frame: String, expected: Wire.Decoded)

    /** A row's field, read into a leaf name: "no trigger_id", or "an empty trigger_id". */
    private def missing(field: String): String = if field.startsWith("empty ") then s"an $field" else s"no $field"

    private def interactive(id: String, payload: String): String = s"""{"type":"interactive","envelope_id":"$id","payload":$payload}"""
    private def eventsApi(id: String, event: String): String     =
        s"""{"type":"events_api","envelope_id":"$id","payload":{"type":"event_callback","event_id":"Ev$id","event":$event}}"""
    private def unknownInteraction(id: String, kind: String, payload: String): Wire.Decoded =
        envelope(SlackEnvelope.Interactive(meta(id), SlackInteraction.Unknown(kind, SlackRawJsonTest.of(payload))), ackable = true)
    private def unknownEvent(id: String, kind: String, event: String): Wire.Decoded =
        envelope(events(meta(id), SlackEvent.Unknown(kind, SlackRawJsonTest.of(event)), eventId(id)), ackable = true)
    private def unknownEnvelope(kind: String, frame: String, id: Maybe[String]): Wire.Decoded =
        id match
            case Present(i) => envelope(SlackEnvelope.Unknown(kind, SlackRawJsonTest.of(frame), meta(i)), ackable = true)
            case Absent     => envelope(SlackEnvelope.UnknownFrame(kind, SlackRawJsonTest.of(frame)), ackable = false)

    private val blockActionsWithout = Map(
        "trigger_id"       -> """{"type":"block_actions","user":{"id":"U1"},"actions":[{"action_id":"a1","block_id":"b1"}]}""",
        "empty trigger_id" ->
            """{"type":"block_actions","user":{"id":"U1"},"trigger_id":"","actions":[{"action_id":"a1","block_id":"b1"}]}""",
        "empty user.id" -> """{"type":"block_actions","user":{"id":""},"trigger_id":"T1","actions":[{"action_id":"a1","block_id":"b1"}]}""",
        "actions.action_id" -> """{"type":"block_actions","user":{"id":"U1"},"trigger_id":"T1","actions":[{"block_id":"b1"}]}""",
        "empty action_id"   ->
            """{"type":"block_actions","user":{"id":"U1"},"trigger_id":"T1","actions":[{"action_id":"","block_id":"b1"}]}""",
        "actions.block_id" -> """{"type":"block_actions","user":{"id":"U1"},"trigger_id":"T1","actions":[{"action_id":"a1"}]}""",
        "empty block_id" -> """{"type":"block_actions","user":{"id":"U1"},"trigger_id":"T1","actions":[{"action_id":"a1","block_id":""}]}"""
    )
    private val viewSubmissionWithout = Map(
        "view.id"       -> """{"type":"view_submission","user":{"id":"U1"},"view":{"state":{"values":{}}}}""",
        "empty view.id" -> """{"type":"view_submission","user":{"id":"U1"},"view":{"id":"","state":{"values":{}}}}"""
    )
    private val viewClosedWithout = Map(
        "view.id"       -> """{"type":"view_closed","user":{"id":"U1"},"view":{},"is_cleared":false}""",
        "empty view.id" -> """{"type":"view_closed","user":{"id":"U1"},"view":{"id":""},"is_cleared":false}""",
        "is_cleared"    -> """{"type":"view_closed","user":{"id":"U1"},"view":{"id":"V1"}}"""
    )
    private val shortcutWithout = Map(
        "trigger_id"       -> """{"type":"shortcut","user":{"id":"U1"},"callback_id":"cb1"}""",
        "empty trigger_id" -> """{"type":"shortcut","user":{"id":"U1"},"trigger_id":"","callback_id":"cb1"}""",
        "callback_id"      -> """{"type":"shortcut","user":{"id":"U1"},"trigger_id":"T1"}"""
    )
    private val messageActionWithout = Map(
        "trigger_id" -> """{"type":"message_action","user":{"id":"U1"},"callback_id":"cb1","channel":{"id":"C1"},"message":{"ts":"1.2"}}""",
        "empty trigger_id" ->
            """{"type":"message_action","user":{"id":"U1"},"trigger_id":"","callback_id":"cb1","channel":{"id":"C1"},"message":{"ts":"1.2"}}""",
        "callback_id" -> """{"type":"message_action","user":{"id":"U1"},"trigger_id":"T1","channel":{"id":"C1"},"message":{"ts":"1.2"}}""",
        "channel.id"  ->
            """{"type":"message_action","user":{"id":"U1"},"trigger_id":"T1","callback_id":"cb1","channel":{},"message":{"ts":"1.2"}}""",
        "empty channel.id" ->
            """{"type":"message_action","user":{"id":"U1"},"trigger_id":"T1","callback_id":"cb1","channel":{"id":""},"message":{"ts":"1.2"}}""",
        "message.ts" ->
            """{"type":"message_action","user":{"id":"U1"},"trigger_id":"T1","callback_id":"cb1","channel":{"id":"C1"},"message":{}}""",
        "empty message.ts" ->
            """{"type":"message_action","user":{"id":"U1"},"trigger_id":"T1","callback_id":"cb1","channel":{"id":"C1"},"message":{"ts":""}}"""
    )
    private val slashWithout = Map(
        "command"    -> """{"text":"prod","channel_id":"C1","user_id":"U1","trigger_id":"T8","response_url":"https://hooks/x"}""",
        "text"       -> """{"command":"/deploy","channel_id":"C1","user_id":"U1","trigger_id":"T8","response_url":"https://hooks/x"}""",
        "channel_id" -> """{"command":"/deploy","text":"prod","user_id":"U1","trigger_id":"T8","response_url":"https://hooks/x"}""",
        "empty channel_id" ->
            """{"command":"/deploy","text":"prod","channel_id":"","user_id":"U1","trigger_id":"T8","response_url":"https://hooks/x"}""",
        "user_id"       -> """{"command":"/deploy","text":"prod","channel_id":"C1","trigger_id":"T8","response_url":"https://hooks/x"}""",
        "empty user_id" ->
            """{"command":"/deploy","text":"prod","channel_id":"C1","user_id":"","trigger_id":"T8","response_url":"https://hooks/x"}""",
        "trigger_id"       -> """{"command":"/deploy","text":"prod","channel_id":"C1","user_id":"U1","response_url":"https://hooks/x"}""",
        "empty trigger_id" ->
            """{"command":"/deploy","text":"prod","channel_id":"C1","user_id":"U1","trigger_id":"","response_url":"https://hooks/x"}"""
    )
    private val helloWithout = Map(
        "num_connections"              -> """{"type":"hello","connection_info":{"app_id":"A1"}}""",
        "connection_info.app_id"       -> """{"type":"hello","num_connections":1,"connection_info":{}}""",
        "empty connection_info.app_id" -> """{"type":"hello","num_connections":1,"connection_info":{"app_id":""}}"""
    )
    private val eventWithEmptyId = Map(
        "message"               -> """{"type":"message","channel":"C1","user":"","text":"hi","ts":"1.2"}""",
        "app_mention"           -> """{"type":"app_mention","channel":"C1","user":"U1","text":"hi","ts":""}""",
        "reaction_added"        -> """{"type":"reaction_added","user":"U1","reaction":"rocket","item":{"channel":"","ts":"1.2"}}""",
        "app_home_opened"       -> """{"type":"app_home_opened","user":"","channel":"D1","tab":"home"}""",
        "member_joined_channel" -> """{"type":"member_joined_channel","user":"U1","channel":""}"""
    )

    private val rows: Chunk[Row] =
        Chunk.from(blockActionsWithout.toSeq.map((f, p) =>
            Row(s"block_actions with ${missing(f)}", interactive("E30", p), unknownInteraction("E30", "block_actions", p))
        )) ++
            Chunk.from(viewSubmissionWithout.toSeq.map((f, p) =>
                Row(s"view_submission with ${missing(f)}", interactive("E31", p), unknownInteraction("E31", "view_submission", p))
            )) ++
            Chunk.from(viewClosedWithout.toSeq.map((f, p) =>
                Row(s"view_closed with ${missing(f)}", interactive("E32", p), unknownInteraction("E32", "view_closed", p))
            )) ++
            Chunk.from(shortcutWithout.toSeq.map((f, p) =>
                Row(s"shortcut with ${missing(f)}", interactive("E33", p), unknownInteraction("E33", "shortcut", p))
            )) ++
            Chunk.from(messageActionWithout.toSeq.map((f, p) =>
                Row(s"message_action with ${missing(f)}", interactive("E34", p), unknownInteraction("E34", "message_action", p))
            )) ++
            Chunk.from(slashWithout.toSeq.map { (f, p) =>
                val frame = s"""{"type":"slash_commands","envelope_id":"E35","payload":$p}"""
                Row(s"slash command with ${missing(f)}", frame, unknownEnvelope("slash_commands", frame, Present("E35")))
            }) ++
            Chunk.from(helloWithout.toSeq.map((f, frame) =>
                Row(s"hello with ${missing(f)}", frame, unknownEnvelope("hello", frame, Absent))
            )) ++
            Chunk.from(eventWithEmptyId.toSeq.map((kind, e) =>
                Row(s"$kind event with an empty id", eventsApi("E36", e), unknownEvent("E36", kind, e))
            )) ++
            Chunk(
                {
                    val frame = eventsApi("E37", """{"user":"U1"}""")
                    Row("events_api whose inner event has no type", frame, unknownEnvelope("events_api", frame, Present("E37")))
                }, {
                    val frame = eventsApi("E38", """{"type":"","user":"U1"}""")
                    Row("events_api whose inner event has an empty type", frame, unknownEnvelope("events_api", frame, Present("E38")))
                }, {
                    val frame = interactive("E39", """{"user":{"id":"U1"}}""")
                    Row("interactive whose payload has no type", frame, unknownEnvelope("interactive", frame, Present("E39")))
                }, {
                    val frame = interactive("E40", """{"type":5,"user":{"id":"U1"}}""")
                    Row("interactive whose payload type is not a string", frame, unknownEnvelope("interactive", frame, Present("E40")))
                }
            )

    rows.foreach { row =>
        s"${row.name} decodes to its kind's Unknown with the raw payload whole" in {
            Wire.decode(row.frame).map(d => assert(d == row.expected))
        }
    }

    "hello whose debug_info has no host leaves debugHost Absent" in {
        val frame = """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"},"debug_info":{"build_number":42}}"""
        Wire.decode(frame).map {
            case Wire.Decoded.Envelope(hello: SlackEnvelope.Hello, false) =>
                assert(hello == SlackEnvelope.Hello(1, connectionA1, Present(SlackEnvelope.Hello.DebugInfo())))
                assert(hello.debugHost == Absent)
            case other => fail(s"expected a Hello, got $other")
        }
    }

    "app_home_opened without tab decodes with tab Absent" in {
        val frame = eventsApi("E41", """{"type":"app_home_opened","user":"U1","channel":"D1"}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E41"),
                    SlackEvent.AppHomeOpened(SlackId.UserId("U1"), SlackId.ChannelId("D1"), Absent),
                    eventId("E41")
                ),
                ackable = true
            ))
        }
    }

    "app_home_opened with tab decodes with that tab" in {
        val frame = eventsApi("E42", """{"type":"app_home_opened","user":"U1","channel":"D1","tab":"home"}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    meta("E42"),
                    SlackEvent.AppHomeOpened(SlackId.UserId("U1"), SlackId.ChannelId("D1"), Present("home")),
                    eventId("E42")
                ),
                ackable = true
            ))
        }
    }

    "view_submission without view.state decodes with stateJson Absent" in {
        val payload = """{"type":"view_submission","user":{"id":"U1"},"view":{"id":"V1"}}"""
        Wire.decode(interactive("E43", payload)).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(
                    meta("E43"),
                    SlackInteraction.ViewSubmission(user("U1"), SlackInteraction.ViewSubmission.View(SlackId.ViewId("V1")))
                ),
                ackable = true
            ))
        }
    }

    "a shortcut whose callback_id Slack sent empty keeps it, since it is Slack's value" in {
        val payload = """{"type":"shortcut","user":{"id":"U1"},"trigger_id":"T1","callback_id":""}"""
        Wire.decode(interactive("E44", payload)).map { d =>
            assert(d == envelope(
                SlackEnvelope.Interactive(meta("E44"), SlackInteraction.Shortcut(user("U1"), SlackId.TriggerId("T1"), "")),
                ackable = true
            ))
        }
    }

    "a slash command whose text Slack sent empty keeps it, since it is Slack's value" in {
        val frame =
            """{"type":"slash_commands","envelope_id":"E45","payload":{"command":"/deploy","text":"","channel_id":"C1","user_id":"U1","trigger_id":"T8","response_url":"https://hooks/x"}}"""
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                SlackEnvelope.SlashCommand(
                    meta("E45"),
                    SlackCommand(
                        "/deploy",
                        "",
                        SlackId.ChannelId("C1"),
                        SlackId.UserId("U1"),
                        SlackId.TriggerId("T8"),
                        Present(SlackResponseUrl("https://hooks/x"))
                    )
                ),
                ackable = true
            ))
        }
    }

    "a disconnect without reason decodes to Unspecified" in {
        Wire.decode("""{"type":"disconnect"}""").map { d =>
            assert(d == envelope(SlackEnvelope.Disconnect(SlackEnvelope.DisconnectReason.Unspecified), ackable = false))
        }
    }

    "a frame without accepts_response_payload leaves it Absent on the envelope" in {
        val frame = eventsApi("E46", """{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}""")
        Wire.decode(frame).map { d =>
            assert(d == envelope(
                events(
                    SlackId.EnvelopeId("E46"),
                    SlackEvent.Message(SlackId.ChannelId("C1"), SlackId.UserId("U1"), "hi", SlackTs("1.2")),
                    eventId("E46")
                ),
                ackable = true
            ))
        }
    }

    "a frame with no type field is skipped by its size, never its text" in {
        val frame = s"""{"envelope_id":"E1","payload":$secretPayload}"""
        Wire.decode(frame).map { d =>
            assert(d == Wire.Decoded.Skip(s"frame has no type field (${frame.length} characters)"))
            assert(WireTest.secrets.forall(s => !d.toString.contains(s)), "the skip reason holds part of the frame")
        }
    }

    "a frame that is not JSON is skipped by the parse failure's kind and position, never its text" in {
        val frame = s"not json $secretPayload"
        Wire.decode(frame).map { d =>
            assert(d == Wire.Decoded.Skip("frame is not JSON: unparseable input at position 0"))
            assert(WireTest.secrets.forall(s => !d.toString.contains(s)), "the skip reason holds part of the frame")
        }
    }

    // A panic in the decode would end the receive loop, so a malformed or unbounded number in any part of a frame is a skip.
    "a frame holding a malformed or unbounded number is skipped at that number, never a panic" in {
        val frames = Chunk(
            """{"type":"events_api","envelope_id":"E1","payload":{"a":-}}""",
            """{"type":"events_api","envelope_id":"E1","payload":{"a":1e}}""",
            """{"type":"events_api","envelope_id":"E1","payload":{"a":1-2}}""",
            """{"type":"events_api","envelope_id":"E1","payload":{"a":1e99999999999}}""",
            s"""{"type":"events_api","envelope_id":"E1","payload":{"a":${"9" * 1001}}}"""
        )
        Kyo.foreach(frames)(Wire.decode).map { decoded =>
            assert(
                decoded == Chunk(
                    Wire.Decoded.Skip("frame is not JSON: unparseable input at position 56"),
                    Wire.Decoded.Skip("frame is not JSON: unparseable input at position 57"),
                    Wire.Decoded.Skip("frame is not JSON: unparseable input at position 56"),
                    Wire.Decoded.Skip("frame is not JSON: unparseable input at position 55"),
                    Wire.Decoded.Skip("frame is not JSON: unparseable input at position 55")
                ),
                decoded.toString
            )
        }
    }

    "at reads the value a path names in a frame's tree, and Absent where a step names no field" in {
        val tree = valid(Json.decode[Structure.Value]("""{"a":{"b":[1,"x",true,null]}}"""))
        assert(Wire.at(tree, "c") == Absent)
        assert(Wire.at(tree, "a", "b", "c") == Absent)
        assert(Wire.at(tree, "a").map(Json.encode(_)) == Present("""{"b":[1,"x",true,null]}"""))
        assert(Wire.at(tree) == Present(tree))
    }

    "an Unknown keeps its raw payload whole and renders no part of it" in {
        val interactionPayload =
            s"""{"type":"block_actions","response_url":"${WireTest.responseUrl}","token":"${WireTest.tokenSecret}","message":{"text":"${WireTest.link}"}}"""
        val interaction   = s"""{"type":"interactive","envelope_id":"E20","payload":$interactionPayload}"""
        val envelopeFrame = s"""{"type":"workflow_step_execute","payload":$secretPayload}"""
        val eventJson     =
            s"""{"type":"link_shared","links":[{"url":"${WireTest.link}"}],"response_url":"${WireTest.responseUrl}"}"""
        val event =
            s"""{"type":"events_api","envelope_id":"E21","payload":{"type":"event_callback","token":"${WireTest.tokenSecret}","event_id":"EvE21","event":$eventJson}}"""
        for
            i <- Wire.decode(interaction)
            e <- Wire.decode(envelopeFrame)
            v <- Wire.decode(event)
        yield
            assert(i == envelope(
                SlackEnvelope.Interactive(meta("E20"), SlackInteraction.Unknown("block_actions", SlackRawJsonTest.of(interactionPayload))),
                ackable = true
            ))
            assert(e == envelope(SlackEnvelope.UnknownFrame("workflow_step_execute", SlackRawJsonTest.of(envelopeFrame)), ackable = false))
            assert(v == envelope(
                events(meta("E21"), SlackEvent.Unknown("link_shared", SlackRawJsonTest.of(eventJson)), eventId("E21")),
                ackable = true
            ))
            val rendered = Chunk(i, e, v).map(_.toString)
            assert(
                rendered.map(r => WireTest.secrets.exists(r.contains)) == Chunk(false, false, false),
                "a rendered Unknown holds part of its payload (interaction, envelope, event)"
            )
        end for
    }

    private val secretPayload =
        s"""{"response_url":"${WireTest.responseUrl}","token":"${WireTest.tokenSecret}","link":"${WireTest.link}"}"""

    // The tagged Structure.Value enum markers that must never appear in a native ack.
    private val taggedMarkers = Chunk("Record", "Sequence", "elements", "\"_1\"", "\"_2\"", "{\"Str\"", "{\"Integer\"")

    private val id = SlackId.EnvelopeId("E1")

    "a bare ack emits exactly the envelope id" in {
        assert(Wire.encodeAck(id, SlackAck.Ack) == """{"envelope_id":"E1"}""")
    }

    "ViewResponse acks emit the native response_action shapes" in {
        val view  = SlackView(SlackView.Type.Modal, blocks = Chunk(rawOf("""{"type":"section"}""")))
        val wires = Chunk(
            Wire.encodeAck(id, SlackAck.ViewResponse(SlackAck.ViewAction.Clear)),
            Wire.encodeAck(id, SlackAck.ViewResponse(SlackAck.ViewAction.Errors(Map(SlackId.BlockId("b1") -> "bad")))),
            Wire.encodeAck(id, SlackAck.ViewResponse(SlackAck.ViewAction.Update(view))),
            Wire.encodeAck(id, SlackAck.ViewResponse(SlackAck.ViewAction.Push(view)))
        )
        assert(wires == Chunk(
            """{"envelope_id":"E1","payload":{"response_action":"clear"}}""",
            """{"envelope_id":"E1","payload":{"response_action":"errors","errors":{"b1":"bad"}}}""",
            """{"envelope_id":"E1","payload":{"response_action":"update","view":{"type":"modal","blocks":[{"type":"section"}]}}}""",
            """{"envelope_id":"E1","payload":{"response_action":"push","view":{"type":"modal","blocks":[{"type":"section"}]}}}"""
        ))
        assert(wires.filter(w => taggedMarkers.exists(w.contains)) == Chunk.empty)
    }

    "a CommandResponse ack carries its visibility as response_type, its text, and blocks as a native array, and no channel" in {
        import SlackAck.CommandResponse.Visibility
        assert(Chunk(
            Wire.encodeAck(id, SlackAck.CommandResponse(Visibility.Ephemeral, "hi", Chunk(rawOf("""{"type":"section"}""")))),
            Wire.encodeAck(id, SlackAck.CommandResponse(Visibility.InChannel, "hi"))
        ) == Chunk(
            """{"envelope_id":"E1","payload":{"response_type":"ephemeral","text":"hi","blocks":[{"type":"section"}]}}""",
            """{"envelope_id":"E1","payload":{"response_type":"in_channel","text":"hi"}}"""
        ))
    }

end WireTest

/** Secrets for the rendering tests, built so their full text is absent from the test source: a
  * development-mode message quotes the source lines around its construction site.
  */
private[kyo] object WireTest:
    val pathSecret: String     = Seq("PathSecret", "Q7zR").mkString
    val tokenSecret: String    = Seq("TokenSecret", "K3wX").mkString
    val linkSecret: String     = Seq("LinkSecret", "M9vT").mkString
    val responseUrl: String    = s"https://hooks.slack.com/actions/T0/1/$pathSecret"
    val link: String           = s"https://files.example.test/$linkSecret/report"
    val secrets: Chunk[String] = Chunk(pathSecret, tokenSecret, linkSecret)
end WireTest

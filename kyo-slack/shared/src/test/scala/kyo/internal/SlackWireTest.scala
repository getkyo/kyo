package kyo.internal

import kyo.*

class SlackWireTest extends kyo.test.Test[Any]:

    // hello not ackable; typed Hello carries num_connections and connection_info.app_id
    "hello frame decodes to a non-ackable Hello" in {
        val frame =
            """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Hello(n, id, _), ackable: Boolean, responseUrl: Maybe[String]) =>
                    assert(n == 1)
                    assert(id == SlackId.AppId("A1"))
                    assert(!ackable)
                    assert(responseUrl == Absent)
                case other =>
                    assert(false, s"expected Hello envelope, got: $other")
        }
    }

    // hello frame carrying a debug_info object: the host populates Hello.debugInfo
    "hello frame with debug_info populates Hello.debugInfo from the host" in {
        val frame =
            """{"type":"hello","num_connections":1,"connection_info":{"app_id":"A1"},"debug_info":{"host":"applink-7","build_number":42}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Hello(n, id, debugInfo), ackable: Boolean, _) =>
                    assert(n == 1)
                    assert(id == SlackId.AppId("A1"))
                    assert(!ackable)
                    assert(debugInfo == Present("applink-7"), s"expected debugInfo from host, got: $debugInfo")
                case other =>
                    assert(false, s"expected Hello envelope with debug_info, got: $other")
        }
    }

    // member_joined_channel carrying inviter: the inviter populates from the real frame
    "events_api member_joined_channel with inviter populates MemberJoinedChannel.inviter" in {
        val frame =
            """{"type":"events_api","envelope_id":"E10","payload":{"type":"event_callback","event":{"type":"member_joined_channel","user":"U1","channel":"C1","inviter":"U999"}}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.EventsApi(meta, event), ackable: Boolean, _) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E10"))
                    assert(ackable)
                    event match
                        case SlackEvent.MemberJoinedChannel(user, channel, inviter) =>
                            assert(user == SlackId.UserId("U1"))
                            assert(channel == SlackId.ChannelId("C1"))
                            assert(inviter == Present(SlackId.UserId("U999")), s"expected inviter U999, got: $inviter")
                        case other =>
                            assert(false, s"expected MemberJoinedChannel event, got: $other")
                    end match
                case other =>
                    assert(false, s"expected EventsApi envelope, got: $other")
        }
    }

    // events_api double-wrap: envelope_id routes to EventsApi; ackable == true
    "events_api message decodes to an ackable EventsApi carrying a typed Message" in {
        val frame =
            """{"type":"events_api","envelope_id":"E1","payload":{"type":"event_callback","event":{"type":"message","channel":"C1","user":"U1","text":"hi","ts":"1.2"}}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.EventsApi(meta, event), ackable: Boolean, responseUrl: Maybe[String]) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E1"))
                    assert(ackable)
                    assert(responseUrl == Absent)
                    event match
                        case SlackEvent.Message(ch, user, text, ts, _) =>
                            assert(ch == SlackId.ChannelId("C1"))
                            assert(user == SlackId.UserId("U1"))
                            assert(text == "hi")
                            assert(ts == SlackId.Ts("1.2"))
                        case other =>
                            assert(false, s"expected Message event, got: $other")
                    end match
                case other =>
                    assert(false, s"expected EventsApi envelope, got: $other")
        }
    }

    // unmodeled event type yields SlackEvent.Unknown, no data loss; still ackable
    "events_api with an unmodeled inner event yields SlackEvent.Unknown still ackable" in {
        val frame =
            """{"type":"events_api","envelope_id":"E3","payload":{"type":"event_callback","event":{"type":"team_join","user":{"id":"U9"}}}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.EventsApi(meta, event), ackable: Boolean, _) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E3"))
                    assert(ackable)
                    event match
                        case SlackEvent.Unknown(t, json) =>
                            assert(t == "team_join")
                            // eventJson is the INNER payload.event object, not the whole frame:
                            // it carries the event type and user but not the envelope keys.
                            assert(json.contains("team_join"))
                            assert(json.contains("\"id\":\"U9\""), s"inner user preserved, got: $json")
                            assert(!json.contains("envelope_id"), s"inner object only, no envelope keys, got: $json")
                            assert(!json.contains("event_callback"), s"inner object only, no wrapper keys, got: $json")
                        case other =>
                            assert(false, s"expected Unknown event, got: $other")
                    end match
                case other =>
                    assert(false, s"expected EventsApi envelope, got: $other")
        }
    }

    // malformed known-type event yields Unknown without abort
    "events_api with malformed known-type event yields SlackEvent.Unknown no abort" in {
        // message event missing required channel field
        val frame =
            """{"type":"events_api","envelope_id":"E4","payload":{"type":"event_callback","event":{"type":"message","user":"U1","text":"hi","ts":"1.2"}}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.EventsApi(meta, event), ackable: Boolean, _) =>
                    assert(ackable)
                    event match
                        case SlackEvent.Unknown(t, json) =>
                            assert(t == "message")
                            assert(json.contains("message"), s"expected raw json to contain 'message' type, got: $json")
                        case other =>
                            assert(false, s"expected Unknown for malformed message, got: $other")
                    end match
                case other =>
                    assert(false, s"expected EventsApi envelope, got: $other")
        }
    }

    // block_actions: REAL Slack shape (user and channel are JSON objects, trigger_id and
    // response_url at the payload top level). response_url is captured on Decoded, not
    // exposed on the public BlockActions; channel.id decodes into the public channel.
    "interactive block_actions decodes the real object-shaped user/channel and captures response_url" in {
        val frame =
            """{"type":"interactive","envelope_id":"E2","payload":{"type":"block_actions","user":{"id":"U1","username":"bob","team_id":"T1"},"trigger_id":"T1","team":{"id":"T1","domain":"d"},"channel":{"id":"C1","name":"general"},"response_url":"https://hooks.slack.com/x","actions":[{"action_id":"a1","block_id":"b1","value":"v1","type":"button"}]}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(
                        SlackEnvelope.Interactive(meta, interaction),
                        ackable: Boolean,
                        responseUrl: Maybe[String]
                    ) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E2"))
                    assert(ackable)
                    assert(responseUrl == Present("https://hooks.slack.com/x"))
                    interaction match
                        case SlackInteraction.BlockActions(user, triggerId, channel, actions) =>
                            assert(user == SlackId.UserId("U1"))
                            assert(triggerId == SlackId.TriggerId("T1"))
                            assert(channel == Present(SlackId.ChannelId("C1")), s"channel.id from the object, got: $channel")
                            assert(actions.size == 1)
                            assert(actions(0).actionId == "a1")
                            assert(actions(0).blockId == "b1")
                            assert(actions(0).value == Present("v1"))
                        case other =>
                            assert(false, s"expected BlockActions, got: $other")
                    end match
                case other =>
                    assert(false, s"expected Interactive envelope, got: $other")
        }
    }

    // view_submission: REAL Slack shape (user is an object, the view is NESTED with id and
    // a state object). No response_url on the wire. The decoded ViewSubmission carries the
    // real view id and re-emits view.state as a non-empty native JSON object string.
    "interactive view_submission decodes the nested view id and state, no responseUrl" in {
        val frame =
            """{"type":"interactive","envelope_id":"E6","payload":{"type":"view_submission","team":{"id":"T1"},"user":{"id":"U1","username":"bob"},"trigger_id":"T6","view":{"id":"V1","type":"modal","callback_id":"cb1","state":{"values":{"b1":{"a1":{"type":"plain_text_input","value":"hello"}}}},"hash":"h"}}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(
                        SlackEnvelope.Interactive(meta, interaction),
                        ackable: Boolean,
                        responseUrl: Maybe[String]
                    ) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E6"))
                    assert(ackable)
                    assert(responseUrl == Absent)
                    interaction match
                        case SlackInteraction.ViewSubmission(user, viewId, stateJson) =>
                            assert(user == SlackId.UserId("U1"))
                            assert(viewId == SlackId.ViewId("V1"))
                            assert(stateJson.contains("\"value\":\"hello\""), s"state re-emitted as native JSON, got: $stateJson")
                            assert(!stateJson.contains("Record"), s"state must not carry the tagged shape, got: $stateJson")
                        case other =>
                            assert(false, s"expected ViewSubmission, got: $other")
                    end match
                case other =>
                    assert(false, s"expected Interactive envelope, got: $other")
        }
    }

    // view_closed: REAL Slack shape (user object, nested view, is_cleared at the top level).
    "interactive view_closed decodes the nested view id and the top-level is_cleared" in {
        val frame =
            """{"type":"interactive","envelope_id":"E11","payload":{"type":"view_closed","team":{"id":"T1"},"user":{"id":"U1"},"view":{"id":"V1","callback_id":"cb1"},"is_cleared":true}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Interactive(meta, interaction), ackable: Boolean, _) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E11"))
                    assert(ackable)
                    interaction match
                        case SlackInteraction.ViewClosed(user, viewId, isCleared) =>
                            assert(user == SlackId.UserId("U1"))
                            assert(viewId == SlackId.ViewId("V1"))
                            assert(isCleared, s"is_cleared from the top level, got: $isCleared")
                        case other =>
                            assert(false, s"expected ViewClosed, got: $other")
                    end match
                case other =>
                    assert(false, s"expected Interactive envelope, got: $other")
        }
    }

    // shortcut (global): REAL Slack shape (user object, callback_id and trigger_id top-level).
    "interactive shortcut decodes the real object-shaped user with callback_id and trigger_id" in {
        val frame =
            """{"type":"interactive","envelope_id":"E12","payload":{"type":"shortcut","user":{"id":"U1","username":"bob"},"callback_id":"cb1","trigger_id":"T12","team":{"id":"T1"}}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Interactive(meta, interaction), ackable: Boolean, _) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E12"))
                    assert(ackable)
                    interaction match
                        case SlackInteraction.Shortcut(user, triggerId, callbackId) =>
                            assert(user == SlackId.UserId("U1"))
                            assert(triggerId == SlackId.TriggerId("T12"))
                            assert(callbackId == "cb1")
                        case other =>
                            assert(false, s"expected Shortcut, got: $other")
                    end match
                case other =>
                    assert(false, s"expected Interactive envelope, got: $other")
        }
    }

    // message_action: REAL Slack shape (user and channel objects, nested message.ts,
    // callback_id/trigger_id/response_url top-level).
    "interactive message_action decodes the real object-shaped user/channel and nested message ts" in {
        val frame =
            """{"type":"interactive","envelope_id":"E13","payload":{"type":"message_action","callback_id":"cb1","trigger_id":"T13","response_url":"https://hooks.slack.com/y","user":{"id":"U1","name":"bob"},"channel":{"id":"C1","name":"general"},"team":{"id":"T1"},"message":{"ts":"1.2","text":"target"}}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(
                        SlackEnvelope.Interactive(meta, interaction),
                        ackable: Boolean,
                        responseUrl: Maybe[String]
                    ) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E13"))
                    assert(ackable)
                    assert(responseUrl == Present("https://hooks.slack.com/y"))
                    interaction match
                        case SlackInteraction.MessageAction(user, triggerId, callbackId, channel, messageTs) =>
                            assert(user == SlackId.UserId("U1"))
                            assert(triggerId == SlackId.TriggerId("T13"))
                            assert(callbackId == "cb1")
                            assert(channel == SlackId.ChannelId("C1"))
                            assert(messageTs == SlackId.Ts("1.2"))
                        case other =>
                            assert(false, s"expected MessageAction, got: $other")
                    end match
                case other =>
                    assert(false, s"expected Interactive envelope, got: $other")
        }
    }

    // unmodeled interaction kind yields Unknown; user object tolerated by the permissive decoder
    "interactive with unmodeled kind yields SlackInteraction.Unknown" in {
        val frame =
            """{"type":"interactive","envelope_id":"E7","payload":{"type":"workflow_step_edit","user":{"id":"U1"}}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Interactive(meta, interaction), ackable: Boolean, _) =>
                    assert(ackable)
                    interaction match
                        case SlackInteraction.Unknown(t, json) =>
                            assert(t == "workflow_step_edit")
                            assert(json.contains("workflow_step_edit"), s"expected raw json to contain 'workflow_step_edit', got: $json")
                            // payloadJson is the INNER payload object, not the whole frame.
                            assert(json.contains("\"id\":\"U1\""), s"inner user preserved, got: $json")
                            assert(!json.contains("envelope_id"), s"inner payload only, no envelope keys, got: $json")
                        case other =>
                            assert(false, s"expected Unknown interaction, got: $other")
                    end match
                case other =>
                    assert(false, s"expected Interactive envelope, got: $other")
        }
    }

    // slash_commands: envelope_id routes; trigger_id, response_url, channel_id, user_id snake_case in payload
    "slash_commands decodes to a SlashCommand carrying the command payload" in {
        val frame =
            """{"type":"slash_commands","envelope_id":"E8","payload":{"command":"/deploy","text":"prod","channel_id":"C1","user_id":"U1","trigger_id":"T8","response_url":"https://hooks/x"}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.SlashCommand(meta, cmd), ackable: Boolean, _) =>
                    assert(meta.envelopeId == SlackId.EnvelopeId("E8"))
                    assert(ackable)
                    assert(cmd.command == "/deploy")
                    assert(cmd.text == "prod")
                    assert(cmd.channel == SlackId.ChannelId("C1"))
                    assert(cmd.user == SlackId.UserId("U1"))
                    assert(cmd.triggerId == SlackId.TriggerId("T8"))
                    assert(cmd.responseUrl == "https://hooks/x")
                case other =>
                    assert(false, s"expected SlashCommand envelope, got: $other")
        }
    }

    // disconnect warning is non-ackable
    "disconnect warning decodes to a non-ackable Disconnect" in {
        val frame = """{"type":"disconnect","reason":"warning"}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Disconnect(reason), ackable: Boolean, _) =>
                    assert(reason == SlackEnvelope.DisconnectReason.Warning)
                    assert(!ackable)
                case other =>
                    assert(false, s"expected Disconnect envelope, got: $other")
        }
    }

    // forward-safety: link_disabled and an unmodeled disconnect reason
    "disconnect link_disabled and unmodeled reason decode correctly" in {
        val frameDisabled = """{"type":"disconnect","reason":"link_disabled"}"""
        val frameSurprise = """{"type":"disconnect","reason":"surprise"}"""
        for
            d1 <- SlackWire.decode(frameDisabled)
            d2 <- SlackWire.decode(frameSurprise)
        yield
            d1 match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Disconnect(r), _, _) =>
                    assert(r == SlackEnvelope.DisconnectReason.LinkDisabled)
                case other =>
                    assert(false, s"expected LinkDisabled, got: $other")
            end match
            d2 match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Disconnect(r), _, _) =>
                    r match
                        case SlackEnvelope.DisconnectReason.Unknown(raw) =>
                            assert(raw == "surprise")
                        case other =>
                            assert(false, s"expected Unknown reason, got: $other")
                case other =>
                    assert(false, s"expected Disconnect with Unknown reason, got: $other")
            end match
        end for
    }

    // unmodeled envelope type preserves the raw frame; NOT ackable even with an
    // envelope_id, because the engine has no typed id for an Unknown to ack with
    // (envelopeId is Absent for Unknown), so marking it ackable would let emitAck
    // silently drop the ack. decode and emitAck stay aligned: delivered, not acked.
    "unmodeled envelope type yields a non-ackable SlackEnvelope.Unknown carrying the raw frame" in {
        val frame =
            """{"type":"workflow_step_execute","envelope_id":"E9","payload":{}}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Envelope(SlackEnvelope.Unknown(t, raw), ackable: Boolean, _) =>
                    assert(t == "workflow_step_execute")
                    assert(raw == frame)
                    assert(!ackable)
                case other =>
                    assert(false, s"expected Unknown envelope, got: $other")
        }
    }

    // structurally-uncorrelatable: no type field -> Skip with a reason
    "frame with no type field yields Skip" in {
        val frame = """{"envelope_id":"E1"}"""
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Skip(reason: String) =>
                    assert(reason.nonEmpty)
                case other =>
                    assert(false, s"expected Skip, got: $other")
        }
    }

    // structural corruption: non-JSON -> Skip without abort
    "non-JSON frame yields Skip no abort" in {
        val frame = "not json at all"
        SlackWire.decode(frame).map { d =>
            d match
                case SlackWire.Decoded.Skip(reason: String) =>
                    assert(reason.nonEmpty)
                case other =>
                    assert(false, s"expected Skip for non-JSON, got: $other")
        }
    }

    // The tagged Structure.Value enum markers that must NEVER appear in a native ack wire
    // string. Their presence proves the payload serialized to the wrong (tagged) shape.
    val taggedMarkers = List("Record", "Sequence", "elements", "\"_1\"", "\"_2\"", "{\"Str\"", "{\"Integer\"")

    def assertNoTaggedShape(wire: String)(using kyo.test.AssertScope): Unit =
        taggedMarkers.foreach(m => assert(!wire.contains(m), s"ack must not carry tagged Structure.Value marker '$m', got: $wire"))

    // bare ack emits exactly {"envelope_id":"<id>"}, no payload
    "encodeAck(Ack) emits exactly the bare frame with envelope_id and no payload" in {
        Abort.run[SlackException](SlackWire.encodeAck(SlackId.EnvelopeId("E1"), SlackAck.Ack)).map {
            case Result.Success(ack) =>
                assert(ack == """{"envelope_id":"E1"}""", s"bare ack wire mismatch: $ack")
                assertNoTaggedShape(ack)
            case other =>
                assert(false, s"expected a successful ack encode, got: $other")
        }
    }

    // BlockActionsResponse emits a bare ack; the message is NOT in the socket ack
    "encodeAck(BlockActionsResponse) emits exactly the bare frame, message excluded" in {
        val msg = SlackMessage(SlackId.ChannelId("C1"), "updated")
        Abort.run[SlackException](SlackWire.encodeAck(SlackId.EnvelopeId("E1"), SlackAck.BlockActionsResponse(msg))).map {
            case Result.Success(ack) =>
                assert(ack == """{"envelope_id":"E1"}""", s"BlockActionsResponse ack wire mismatch: $ack")
                assert(!ack.contains("updated"), s"message must not appear in bare ack, got: $ack")
                assertNoTaggedShape(ack)
            case other =>
                assert(false, s"expected a successful ack encode, got: $other")
        }
    }

    // ViewResponse(Clear): exact native wire shape {"envelope_id":..,"payload":{"response_action":"clear"}}
    "encodeAck(ViewResponse(Clear)) emits the exact native response_action shape" in {
        Abort.run[SlackException](SlackWire.encodeAck(SlackId.EnvelopeId("E1"), SlackAck.ViewResponse(SlackAck.ViewAction.Clear))).map {
            case Result.Success(ack) =>
                assert(ack == """{"envelope_id":"E1","payload":{"response_action":"clear"}}""", s"Clear ack wire mismatch: $ack")
                assertNoTaggedShape(ack)
            case other =>
                assert(false, s"expected a successful ack encode, got: $other")
        }
    }

    // ViewResponse(Errors): exact native wire shape with errors as a JSON object
    "encodeAck(ViewResponse(Errors)) emits the exact native errors-object shape" in {
        val ack = SlackAck.ViewResponse(SlackAck.ViewAction.Errors(Map("b1" -> "bad")))
        Abort.run[SlackException](SlackWire.encodeAck(SlackId.EnvelopeId("E1"), ack)).map {
            case Result.Success(wire) =>
                assert(
                    wire == """{"envelope_id":"E1","payload":{"response_action":"errors","errors":{"b1":"bad"}}}""",
                    s"Errors ack wire mismatch: $wire"
                )
                assertNoTaggedShape(wire)
            case other =>
                assert(false, s"expected a successful ack encode, got: $other")
        }
    }

    // ViewResponse(Update): response_action update AND view.blocks a NATIVE JSON ARRAY
    "encodeAck(ViewResponse(Update)) carries response_action update and view.blocks as a native array" in {
        val view = SlackView(SlackView.Type.Modal, Absent, """[{"type":"section"}]""", Absent)
        val ack  = SlackAck.ViewResponse(SlackAck.ViewAction.Update(view))
        Abort.run[SlackException](SlackWire.encodeAck(SlackId.EnvelopeId("E1"), ack)).map {
            case Result.Success(wire) =>
                assert(wire.contains("\"envelope_id\":\"E1\""), s"missing envelope_id: $wire")
                assert(wire.contains("\"response_action\":\"update\""), s"missing response_action update: $wire")
                assert(wire.contains("\"blocks\":[{\"type\":\"section\"}]"), s"view blocks must be a native array: $wire")
                assertNoTaggedShape(wire)
            case other =>
                assert(false, s"expected a successful ack encode, got: $other")
        }
    }

    // CommandResponse: native message body with text and blocks as a NATIVE JSON ARRAY
    "encodeAck(CommandResponse) carries the native message body with blocks as a native array" in {
        val msg = SlackMessage(SlackId.ChannelId("C1"), "hi", blocksJson = Present("""[{"type":"section"}]"""))
        val ack = SlackAck.CommandResponse(msg)
        Abort.run[SlackException](SlackWire.encodeAck(SlackId.EnvelopeId("E1"), ack)).map {
            case Result.Success(wire) =>
                assert(wire.contains("\"envelope_id\":\"E1\""), s"missing envelope_id: $wire")
                assert(wire.contains("\"text\":\"hi\""), s"missing message text: $wire")
                assert(wire.contains("\"channel\":\"C1\""), s"missing channel: $wire")
                assert(wire.contains("\"blocks\":[{\"type\":\"section\"}]"), s"message blocks must be a native array: $wire")
                assertNoTaggedShape(wire)
            case other =>
                assert(false, s"expected a successful ack encode, got: $other")
        }
    }

    // malformed Block Kit blocks in a CommandResponse surfaces a typed SlackDecodeException
    "encodeAck(CommandResponse) with malformed blocks aborts with SlackDecodeException" in {
        val msg = SlackMessage(SlackId.ChannelId("C1"), "hi", blocksJson = Present("not json"))
        Abort.run[SlackException](SlackWire.encodeAck(SlackId.EnvelopeId("E1"), SlackAck.CommandResponse(msg))).map {
            case Result.Failure(_: SlackException.SlackDecodeException) => assert(true)
            case other => assert(false, s"expected SlackDecodeException for malformed blocks, got: $other")
        }
    }

    // connections.open happy path: ok:true returns the wss url
    "decodeConnectionsOpen returns the wss url on ok:true" in {
        val resp = """{"ok":true,"url":"wss://wss.slack.com/link/x"}"""
        SlackWire.decodeConnectionsOpen(resp) match
            case Result.Success(url) =>
                assert(url == "wss://wss.slack.com/link/x")
            case other =>
                assert(false, s"expected success with url, got: $other")
        end match
    }

    // connections.open failure: ok:false yields a typed SlackHandshakeException
    "decodeConnectionsOpen fails typed on ok:false" in {
        val resp   = """{"ok":false,"error":"invalid_auth"}"""
        val result = SlackWire.decodeConnectionsOpen(resp)
        result match
            case Result.Failure(ex) =>
                assert(ex.isInstanceOf[SlackException.SlackHandshakeException])
                assert(ex.getMessage.contains("invalid_auth"))
            case other =>
                assert(false, s"expected typed Failure, got: $other")
        end match
    }

end SlackWireTest

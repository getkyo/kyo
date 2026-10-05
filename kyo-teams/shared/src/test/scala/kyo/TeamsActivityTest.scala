package kyo

class TeamsActivityTest extends kyo.test.Test[Any]:

    import Teams.*
    import Teams.Activity.*

    private def decode(json: String)(using Frame): Result[DecodeException, Activity[Answer]] = Json.decode[Activity[Answer]](json)

    private def id[A](init: String => Result[TeamsInvalidIdException, A], text: String): A = init(text).getOrThrow

    private def raw(json: String): RawJson = RawJson(Json.decode[Structure.Value](json).getOrThrow)

    private def ok(activity: Activity[Answer]): Result[DecodeException, Activity[Answer]] = Result.succeed(activity)

    private def roundTrips(activity: Activity[Answer])(using Frame): Boolean =
        decode(Json.encode(activity)) == ok(activity)

    // Microsoft's "Channel created" example, conversation events page.
    private val channelCreated = """{
        "type": "conversationUpdate",
        "timestamp": "2017-02-23T19:34:07.478Z",
        "localTimestamp": "2017-02-23T12:34:07.478-07:00",
        "id": "f:dd6ec311",
        "channelId": "msteams",
        "serviceUrl": "https://smba.trafficmanager.net/amer-client-ss.msg/",
        "from": {"id": "29:1wR7IdIRIoerMIWbewMi75JA3scaMuxvFon9eRQW2Nix5loMDo0362st2IaRVRirPZBv1WdXT8TIFWWmlQCizZQ"},
        "conversation": {"isGroup": true, "conversationType": "channel", "id": "19:efa9296d959346209fea44151c742e73@thread.skype"},
        "recipient": {"id": "28:f5d48856-5b42-41a0-8c3a-c5f944b679b0", "name": "SongsuggesterBot"},
        "channelData": {
            "channel": {"id": "19:6d97d816470f481dbcda38244b98689a@thread.skype", "name": "FunDiscussions"},
            "team": {"id": "19:efa9296d959346209fea44151c742e73@thread.skype"},
            "eventType": "channelCreated",
            "tenant": {"id": "72f988bf-86f1-41af-91ab-2d7cd011db47"}
        }
    }"""

    private val channelCommon = Common(
        id = id(ActivityId.init, "f:dd6ec311"),
        timestamp = Present(Instant.parse("2017-02-23T19:34:07.478Z").getOrThrow),
        serviceUrl = ServiceUrl.init("https://smba.trafficmanager.net/amer-client-ss.msg/").getOrThrow,
        channel = BotChannel.MsTeams,
        sender = Account(id(UserId.init, "29:1wR7IdIRIoerMIWbewMi75JA3scaMuxvFon9eRQW2Nix5loMDo0362st2IaRVRirPZBv1WdXT8TIFWWmlQCizZQ")),
        conversation = ConversationAccount(
            id = id(ConversationId.init, "19:efa9296d959346209fea44151c742e73@thread.skype"),
            isGroup = Present(true),
            conversationType = Present(ConversationAccount.Kind.Channel)
        ),
        recipient = Account(id(UserId.init, "28:f5d48856-5b42-41a0-8c3a-c5f944b679b0"), name = Present("SongsuggesterBot")),
        channelData = Present(ChannelData(
            tenant = Present(ChannelData.Tenant(id(TenantId.init, "72f988bf-86f1-41af-91ab-2d7cd011db47"))),
            team = Present(ChannelData.Team(id(TeamId.init, "19:efa9296d959346209fea44151c742e73@thread.skype"))),
            channel = Present(ChannelData.Channel(
                id(ChannelId.init, "19:6d97d816470f481dbcda38244b98689a@thread.skype"),
                Present("FunDiscussions")
            )),
            eventType = Present(ChannelData.Event.ChannelCreated)
        ))
    )

    "a channel created event decodes to a conversation update with every documented field" in {
        assert(decode(channelCreated) == ok(ConversationUpdate(channelCommon)))
    }

    "a conversation update's event reads each documented eventType, the lower-case teamrestored, and any other as Other" in {
        def event(name: String): Maybe[ChannelData.Event] =
            decode(channelCreated.replace("\"channelCreated\"", s"\"$name\"")).toMaybe.flatMap {
                case u: ConversationUpdate => u.common.channelData.flatMap(_.eventType)
                case _                     => Absent
            }
        import ChannelData.Event.*
        assert(Chunk(
            "channelRenamed",
            "channelDeleted",
            "channelRestored",
            "teamMemberAdded",
            "teamMemberRemoved",
            "teamRenamed",
            "teamDeleted",
            "teamArchived",
            "teamUnarchived",
            "teamRestored",
            "teamrestored",
            "teamTransferred"
        ).map(event) == Chunk(
            Present(ChannelRenamed),
            Present(ChannelDeleted),
            Present(ChannelRestored),
            Present(TeamMemberAdded),
            Present(TeamMemberRemoved),
            Present(TeamRenamed),
            Present(TeamDeleted),
            Present(TeamArchived),
            Present(TeamUnarchived),
            Present(TeamRestored),
            Present(TeamRestored),
            Present(Other("teamTransferred"))
        ))
    }

    "the bot added to a team carries the members added, the team and the channel the user selected" in {
        val json = """{
            "type": "conversationUpdate",
            "membersAdded": [{"id": "28:608cacfd-1cea-40c9-b678-4b93e69bb72b"}],
            "timestamp": "2021-12-07T22:34:56.534Z",
            "id": "f:0b9079f4-d4d3-3d8e-b883-798298053c7e",
            "channelId": "msteams",
            "serviceUrl": "https://smba.trafficmanager.net/amer/",
            "from": {"id": "29:1ljv6N86roXr5pjPrCJVIz6xHh5QxjI....", "aadObjectId": "eddfa9d4-346e-4cce-a18f-fa6261ad776b"},
            "conversation": {
                "isGroup": true,
                "conversationType": "channel",
                "tenantId": "b28fdbfd-2b78-4f93-b0f8-8881793f0f8f",
                "id": "19:0b7f32667e064dd9b25d7969801541f4@thread.tacv2",
                "name": "2021 Test Channel"
            },
            "recipient": {"id": "28:608cacfd-1cea-40c9-b678-4b93e69bb72b", "name": "Test Agent"},
            "channelData": {
                "settings": {"selectedChannel": {"id": "19:0b7f32667e064dd9b25d7969801541f4@thread.tacv2"}},
                "team": {
                    "aadGroupId": "f3ec8cd2-e704-4344-8c47-9a3a21d683c0",
                    "name": "TestTeam2022",
                    "id": "19:zFLSDFWsesfzcmKArqKJ-65aOXJz@sgf462H2wz41@thread.tacv2"
                },
                "eventType": "teamMemberAdded",
                "tenant": {"id": "b28fdbfd-2b78-4f93-b0f8-8881793f0f8f"}
            }
        }"""
        decode(json) match
            case Result.Success(u: ConversationUpdate) =>
                val data = u.common.channelData
                assert(u.membersAdded.map(_.id.value) == Chunk("28:608cacfd-1cea-40c9-b678-4b93e69bb72b"))
                assert(u.membersRemoved.isEmpty)
                assert(u.common.sender.aadObjectId.map(_.value) == Present("eddfa9d4-346e-4cce-a18f-fa6261ad776b"))
                assert(u.common.conversation.tenantId.map(_.value) == Present("b28fdbfd-2b78-4f93-b0f8-8881793f0f8f"))
                assert(data.flatMap(_.settings).flatMap(_.selectedChannel).map(_.id.value) ==
                    Present("19:0b7f32667e064dd9b25d7969801541f4@thread.tacv2"))
                assert(data.flatMap(_.team).map(t => (t.name, t.aadGroupId)) ==
                    Present((Present("TestTeam2022"), Present("f3ec8cd2-e704-4344-8c47-9a3a21d683c0"))))
                assert(roundTrips(u))
            case other => fail(s"expected a conversation update, got $other")
        end match
    }

    "a reaction added names the reaction and the bot's message, and ignores keys the module does not read" in {
        val json = """{
            "reactionsAdded": [{"type": "like"}, {"type": "heart"}, {"type": "confused"}],
            "type": "messageReaction",
            "timestamp": "2017-10-16T18:45:41.943Z",
            "id": "f:9f78d1f3",
            "channelId": "msteams",
            "serviceUrl": "https://smba.trafficmanager.net/amer-client-ss.msg/",
            "from": {"id": "29:1I9Is_Sx0O-Iy2rQ7Xz1lcaPKlO9eqmBRTBuW6XzkFtcjqxTjPaCMij8BVMdBcL9L_RwWNJyAHFQb0TRzXgyQvA"},
            "conversation": {"isGroup": true, "conversationType": "channel", "id": "19:3629591d4b774aa08cb0887902eee7c1@thread.skype"},
            "recipient": {"id": "28:f5d48856-5b42-41a0-8c3a-c5f944b679b0", "name": "SongsuggesterLocal"},
            "channelData": {"tenant": {"id": "72f988bf-86f1-41af-91ab-2d7cd011db47"}},
            "replyToId": "1575667808184",
            "legacy": {"replyToId": "1:19uJ8TZA1cZcms7-2HLOW3pWRF4nSWEoVnRqc0DPa_kY"}
        }"""
        decode(json) match
            case Result.Success(r: MessageReaction) =>
                assert(r.reactionsAdded.map(_.kind) ==
                    Chunk(Reaction.Kind.Like, Reaction.Kind.Heart, Reaction.Kind.Other("confused")))
                assert(r.reactionsRemoved.isEmpty)
                assert(r.replyToId.map(_.value) == Present("1575667808184"))
                assert(roundTrips(r))
            case other => fail(s"expected a message reaction, got $other")
        end match
    }

    "an installation update names its action, and an entity of an unmodelled type keeps its JSON" in {
        // Microsoft's example on the conversation events page opens with a stray brace; this is the object inside it.
        val json = """{
            "type": "installationUpdate",
            "id": "f:816eb23d-bfa1-afa3-dfeb-d2aa338e9541",
            "timestamp": "2021-11-09T04:47:30.91Z",
            "serviceUrl": "https://smba.trafficmanager.net/amer/",
            "channelId": "msteams",
            "from": {"id": "29:1ljv6N86roXr5pjPrCJVIz6xHh5QxjI....", "aadObjectId": "eddfa9d4-346e-4cce-a18f-fa6261ad776b"},
            "recipient": {"id": "28:608cacfd-1cea-40c9-b678-4b93e69bb72b", "name": "Test Agent"},
            "locale": "en-US",
            "entities": [{"type": "clientInfo", "locale": "en-US"}],
            "conversation": {"isGroup": true, "id": "19:0b7f32667e064dd9b25d7969801541f4@thread.tacv2", "conversationType": "channel"},
            "channelData": {"tenant": {"id": "b28fdbfd-2b78-4f93-b0f8-8881793f0f8f"}, "source": {"name": "message"}},
            "action": "add"
        }"""
        decode(json) match
            case Result.Success(i: InstallationUpdate) =>
                assert(i.action == InstallAction.Add)
                assert(i.common.locale == Present("en-US"))
                assert(i.common.entities == Chunk(Entity.Other("clientInfo", raw("""{"type":"clientInfo","locale":"en-US"}"""))))
                assert(roundTrips(i))
            case other => fail(s"expected an installation update, got $other")
        end match
        val actions = Chunk("remove", "add-upgrade", "remove-upgrade", "reinstall").map(a =>
            decode(json.replace("\"action\": \"add\"", s"\"action\": \"$a\"")).toMaybe.flatMap {
                case i: InstallationUpdate => Present(i.action)
                case _                     => Absent
            }
        )
        assert(actions == Chunk(
            Present(InstallAction.Remove),
            Present(InstallAction.AddUpgrade),
            Present(InstallAction.RemoveUpgrade),
            Present(InstallAction.Other("reinstall"))
        ))
    }

    private val messageJson =
        """{
        "type": "message",
        "id": "1485983408511",
        "timestamp": "2017-02-01T21:10:07.437Z",
        "serviceUrl": "https://smba.trafficmanager.net/amer/",
        "channelId": "msteams",
        "from": {"id": "29:1XJKJMvc5GBtc2JwZq0oj8tHZmzrQgFmB39ATiQWA85gQtHieVkKilBZ9XHoq9j7Zaqt7CZ-NJWi7me2kHTL3Bw", "name": "Megan Bowen"},
        "conversation": {"id": "19:abc@thread.skype", "conversationType": "channel"},
        "recipient": {"id": "28:c9e8c047-2a74-40a2-b28a-b162d5f5327c", "name": "Teams TestBot"},
        "text": "<at>Teams TestBot</at> order 42",
        "textFormat": "plain",
        "entities": [{"type": "mention", "mentioned": {"id": "28:c9e8c047-2a74-40a2-b28a-b162d5f5327c", "name": "Teams TestBot"}, "text": "<at>Teams TestBot</at>"}],
        "attachments": [
            {"contentType": "text/html", "content": "<div>order 42</div>"},
            {"contentType": "application/vnd.microsoft.card.adaptive", "content": {"type": "AdaptiveCard", "version": "1.4", "body": [{"type": "TextBlock", "text": "hi"}]}}
        ],
        "value": {"answer": "yes"},
        "channelData": {"tenant": {"id": "72f988bf-86f1-41af-91ab-2d7cd011db47"}}
    }"""

    "a message carries its text, its format, its mentions, its attachments and a submitted card's value" in {
        decode(messageJson) match
            case Result.Success(m: Message) =>
                assert(m.text == Present("<at>Teams TestBot</at> order 42"))
                assert(m.textFormat == Present(TextFormat.Plain))
                assert(m.common.entities == Chunk(Entity.Mention(
                    Account(id(UserId.init, "28:c9e8c047-2a74-40a2-b28a-b162d5f5327c"), name = Present("Teams TestBot")),
                    Present("<at>Teams TestBot</at>")
                )))
                assert(m.attachments == Chunk(
                    Attachment.Other("text/html", raw("""{"contentType":"text/html","content":"<div>order 42</div>"}""")),
                    Attachment.AdaptiveCard(Card(Card.Version.init("1.4").getOrThrow, body = Chunk(Card.Element.TextBlock("hi"))))
                ))
                assert(m.value == Present(raw("""{"answer":"yes"}""")))
                assert(roundTrips(m))
            case other => fail(s"expected a message, got $other")
        end match
    }

    "a message's reference answers its conversation: its service URL, the bot, the user and the Activity" in {
        decode(messageJson) match
            case Result.Success(m: Message) =>
                assert(m.reference == ConversationReference(
                    serviceUrl = m.common.serviceUrl,
                    conversation = m.common.conversation,
                    bot = Present(m.common.recipient),
                    user = Present(m.common.sender),
                    activityId = Present(id(ActivityId.init, "1485983408511")),
                    channel = BotChannel.MsTeams
                ))
            case other => fail(s"expected a message, got $other")
    }

    "an edit, a deletion and typing decode to their cases" in {
        def as(kind: String): Result[DecodeException, Activity[Answer]] =
            decode(channelCreated.replace("\"conversationUpdate\"", s"\"$kind\""))
        assert(as("messageUpdate") == ok(MessageUpdate(channelCommon)))
        assert(as("messageDelete") == ok(MessageDelete(channelCommon)))
        assert(as("typing") == ok(Typing(channelCommon)))
    }

    "an Activity type the module does not model is Unknown with its whole JSON, and encodes back to it" in {
        val json    = """{"type":"event","id":"f:1","name":"application/vnd.microsoft.meetingStart","value":{"Id":"m1"}}"""
        val decoded = decode(json)
        assert(decoded == ok(Unknown("event", raw(json))))
        assert(TeamsJsonTree(Json.encode(decoded.getOrThrow)) == TeamsJsonTree(json))
    }

    "an Adaptive Card's Action.Execute arrives as an invoke carrying the action, its data and its trigger" in {
        val json = channelCreated
            .replace("\"conversationUpdate\"", "\"invoke\"")
            .replace(
                "\"channelData\"",
                """"name": "adaptiveCard/action",
                   "value": {"action": {"type": "Action.Execute", "id": "abc", "verb": "approve", "data": {"order": 42}}, "trigger": "manual"},
                   "channelData""""
            )
        val expected = AdaptiveCardAction(
            channelCommon,
            AdaptiveCardAction.Request(
                Card.Action.Execute(verb = Present("approve"), id = Present("abc"), data = Present(raw("""{"order":42}"""))),
                Trigger.Manual
            )
        )
        assert(decode(json) == ok(expected))
        assert(roundTrips(expected))
    }

    "another invoke is OtherInvoke with its name and its JSON, which encodes back with the type once" in {
        val json    = """{"type":"invoke","name":"composeExtension/query","id":"f:2","value":{"commandId":"search"}}"""
        val decoded = decode(json)
        assert(decoded == ok(OtherInvoke(
            "composeExtension/query",
            raw("""{"name":"composeExtension/query","id":"f:2","value":{"commandId":"search"}}""")
        )))
        assert(TeamsJsonTree(Json.encode(decoded.getOrThrow)) == TeamsJsonTree(json))
    }

    "a handler answers each case with the type its index names" in {
        def answer[A](activity: Activity[A]): A =
            activity match
                case _: AdaptiveCardAction => Card.ActionResponse.ShowMessage("done")
                case _: OtherInvoke        => InvokeResponse(HttpStatus(200))
                case _: Plain              => ()
        assert(answer(Typing(channelCommon)) == ())
        assert(answer(OtherInvoke("x", raw("{}"))) == InvokeResponse(HttpStatus(200)))
    }

    "an invoke response whose status the schema refuses fails with the decode call's Frame" in {
        val valid = Json.encode(InvokeResponse(HttpStatus(200)))
        assert(valid.contains("200"), valid)
        val decodeSite = summon[Frame]
        Json.decode[InvokeResponse](valid.replace("200", "99"))(using summon[Json], summon[Schema[InvokeResponse]], decodeSite) match
            case Result.Failure(e: ConstructorRejectedException) =>
                assert(String.valueOf(e.rejection) == "status 99 is outside 100 to 599")
                assert(e.frame == decodeSite, s"refused at ${e.frame}, decoded at $decodeSite")
            case other => fail(s"expected a ConstructorRejectedException, got $other")
        end match
    }

    "a body that is not an Activity fails the decode" in {
        assert(decode("""{"type":"message"}""").isFailure)
        assert(decode(channelCreated.replace("2017-02-23T19:34:07.478Z", "yesterday")).isFailure)
        assert(decode(channelCreated.replace("\"msteams\"", "42")).isFailure)
        assert(decode("[]").isFailure)
    }

    "an Activity from another channel keeps the channel's name" in {
        decode(channelCreated.replace("\"msteams\"", "\"webchat\"")) match
            case Result.Success(u: ConversationUpdate) => assert(u.common.channel == BotChannel.Other("webchat"))
            case other                                 => fail(s"expected a conversation update, got $other")
    }

end TeamsActivityTest

package kyo

import Telegram.Message
import Telegram.Message.Content
import Telegram.MessageId
import Telegram.Update
import Telegram.UpdateId
import TelegramTest.Fixtures.*

class TelegramUpdateTest extends kyo.test.Test[Any]:

    private val plain = Message(MessageId(43), privateChat, at(1735689601), Content.Text("plain", Chunk.empty))

    "message" in {
        val json  = s"""{"update_id":900000001,"message":{"message_id":43,"chat":$privateChatJson,"date":1735689601,"text":"plain"}}"""
        val value = Update.Message(UpdateId(900000001L), plain)
        assert(Json.decode[Update](json) == Result.succeed(value))
        assert(wire(value: Update) == wire(json))
    }

    "edited_message, channel_post and edited_channel_post carry a Message under their own key" in {
        val post = s""""sender_chat":$channelJson,"chat":$channelJson,"date":1735689630"""
        val json = Chunk(
            s"""{"update_id":900000002,"edited_message":{"message_id":43,"chat":$aliceChatJson,"date":1735689601,"edit_date":1735689660,"text":"plain, edited"}}""",
            s"""{"update_id":900000003,"channel_post":{"message_id":900,$post,"text":"v1.0 is out"}}""",
            s"""{"update_id":900000004,"edited_channel_post":{"message_id":900,$post,"edit_date":1735689700,"text":"v1.0.1 is out"}}"""
        )
        val values = Chunk[Update](
            Update.EditedMessage(
                UpdateId(900000002L),
                Message(
                    MessageId(43),
                    aliceChat,
                    at(1735689601),
                    Content.Text("plain, edited", Chunk.empty),
                    editDate = Present(at(1735689660))
                )
            ),
            Update.ChannelPost(
                UpdateId(900000003L),
                Message(MessageId(900), channel, at(1735689630), Content.Text("v1.0 is out", Chunk.empty), senderChat = Present(channel))
            ),
            Update.EditedChannelPost(
                UpdateId(900000004L),
                Message(
                    MessageId(900),
                    channel,
                    at(1735689630),
                    Content.Text("v1.0.1 is out", Chunk.empty),
                    senderChat = Present(channel),
                    editDate = Present(at(1735689700))
                )
            )
        )
        assert(json.map(Json.decode[Update](_)) == values.map(Result.succeed(_)))
        assert(values.map(wire(_)) == json.map(wire(_)))
    }

    "callback_query" in {
        val json = s"""{"update_id":900000005,"callback_query":{"id":"4382bfdwdsb323b2d9","from":$aliceUserJson,""" +
            """"chat_instance":"-8204785648543254131","data":"vote:yes","inline_message_id":"AgAAAJ4"}}"""
        val value = Update.CallbackQuery(
            UpdateId(900000005L),
            Telegram.CallbackQuery(
                Telegram.CallbackQueryId("4382bfdwdsb323b2d9"),
                aliceUser,
                "-8204785648543254131",
                inlineMessageId = Present("AgAAAJ4"),
                data = Present("vote:yes")
            )
        )
        assert(Json.decode[Update](json) == Result.succeed(value))
        assert(wire(value: Update) == wire(json))
    }

    "my_chat_member and chat_member are their own cases over one ChatMemberUpdate" in {
        import Telegram.ChatMember.Status
        val member = s""""chat":$forumJson,"from":$aliceUserJson,"date":1735689700,""" +
            s""""old_chat_member":{"status":"left","user":$botJson},"new_chat_member":{"status":"administrator","user":$botJson}"""
        val json = Chunk(
            s"""{"update_id":900000006,"my_chat_member":{$member}}""",
            s"""{"update_id":900000007,"chat_member":{$member}}"""
        )
        val update = Telegram.ChatMemberUpdate(
            forum,
            aliceUser,
            at(1735689700),
            Telegram.ChatMember(Status.Left, bot),
            Telegram.ChatMember(Status.Administrator, bot)
        )
        val values = Chunk[Update](Update.MyChatMember(UpdateId(900000006L), update), Update.ChatMember(UpdateId(900000007L), update))
        assert(json.map(Json.decode[Update](_)) == values.map(Result.succeed(_)))
        assert(values.map(wire(_)) == json.map(wire(_)))
    }

    "message_reaction" in {
        val json = s"""{"update_id":900000008,"message_reaction":{"chat":$forumJson,"message_id":61,"user":$aliceUserJson,""" +
            """"date":1735689900,"old_reaction":[],"new_reaction":[{"type":"emoji","emoji":"👍"}]}}"""
        val value = Update.MessageReaction(
            UpdateId(900000008L),
            Telegram.ReactionUpdate(
                forum,
                MessageId(61),
                at(1735689900),
                Chunk.empty,
                Chunk(Telegram.Reaction.Emoji("👍")),
                user = Present(aliceUser)
            )
        )
        assert(Json.decode[Update](json) == Result.succeed(value))
        assert(wire(value: Update) == wire(json))
    }

    "a documented kind the module does not model (poll) is Unknown with its type and the whole update" in {
        val json = """{"update_id":900000009,"poll":{"id":"5332012345678901234","question":"Ship it?",""" +
            """"options":[{"text":"yes","voter_count":3},{"text":"no","voter_count":1}],"total_voter_count":4,"is_closed":true,""" +
            """"is_anonymous":true,"type":"regular","allows_multiple_answers":false}}"""
        val value = Update.Unknown(UpdateId(900000009L), Present("poll"), raw(json))
        assert(Json.decode[Update](json) == Result.succeed(value))
        assert(wire(value: Update) == wire(json))
    }

    "a known kind whose payload does not decode is Unknown with that kind's type" in {
        val json = """{"update_id":900000011,"message":{"message_id":"not a number"}}"""
        assert(Json.decode[Update](json) == Result.succeed(Update.Unknown(UpdateId(900000011L), Present("message"), raw(json))))
    }

    "an update with only update_id is Unknown with no type" in {
        val json = """{"update_id":900000010}"""
        assert(Json.decode[Update](json) == Result.succeed(Update.Unknown(UpdateId(900000010L), Absent, raw(json))))
    }

    "a value that is not an update, or one without an integer update_id, fails the decode" in {
        assert(Chunk("""[1]""", """{"message":{}}""", """{"update_id":"x"}""").map(Json.decode[Update](_).isFailure) ==
            Chunk(true, true, true))
    }

    "update_id survives above 2^31" in {
        val json =
            """{"update_id":3000000001,"message":{"message_id":1,"chat":{"id":1,"type":"private","first_name":"A"},"date":1,"text":"x"}}"""
        val chat  = Telegram.Chat(Telegram.ChatId(1L), Telegram.Chat.Type.Private, firstName = Present("A"))
        val value = Update.Message(UpdateId(3000000001L), Message(MessageId(1), chat, at(1), Content.Text("x", Chunk.empty)))
        assert(Json.decode[Update](json) == Result.succeed(value))
        assert(wire(value: Update) == wire(json))
    }

    "each update type is its allowed_updates name, and a name the module does not model is Other" in {
        val types = Chunk(
            Update.Type.Message,
            Update.Type.EditedMessage,
            Update.Type.ChannelPost,
            Update.Type.EditedChannelPost,
            Update.Type.CallbackQuery,
            Update.Type.MyChatMember,
            Update.Type.ChatMember,
            Update.Type.MessageReaction,
            Update.Type.Other("inline_query")
        )
        val names = Chunk(
            "message",
            "edited_message",
            "channel_post",
            "edited_channel_post",
            "callback_query",
            "my_chat_member",
            "chat_member",
            "message_reaction",
            "inline_query"
        )
        assert(types.map(Json.encode(_)) == names.map(n => s"\"$n\""))
        assert(names.map(n => Json.decode[Telegram.Update.Type](s"\"$n\"")) == types.map(Result.succeed(_)))
    }

end TelegramUpdateTest

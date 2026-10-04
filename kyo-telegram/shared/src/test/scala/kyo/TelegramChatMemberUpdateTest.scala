package kyo

import TelegramTest.Fixtures.*

class TelegramChatMemberUpdateTest extends kyo.test.Test[Any]:

    private val chat = Telegram.Chat(Telegram.ChatId(-100L), Telegram.Chat.Type.Group, title = Present("Team"))
    private val ann  = Telegram.User(Telegram.UserId(5L), false, "Ann")
    private val bot  = Telegram.User(Telegram.UserId(7L), true, "Kyo")

    "a ChatMemberUpdated is Telegram's object: chat, from, date in Unix seconds, and the member before and after" in {
        val json =
            """{"chat":{"id":-100,"type":"group","title":"Team"},"from":{"id":5,"is_bot":false,"first_name":"Ann"},"date":1700000000,""" +
                """"old_chat_member":{"status":"left","user":{"id":7,"is_bot":true,"first_name":"Kyo"}},""" +
                """"new_chat_member":{"status":"member","user":{"id":7,"is_bot":true,"first_name":"Kyo"}}}"""
        val update = Telegram.ChatMemberUpdate(
            chat,
            ann,
            Instant.of(1700000000L.seconds, Duration.Zero),
            Telegram.ChatMember(Telegram.ChatMember.Status.Left, bot),
            Telegram.ChatMember(Telegram.ChatMember.Status.Member, bot)
        )
        assert(Json.decode[Telegram.ChatMemberUpdate](json) == Result.succeed(update))
        assert(wire(update) == wire(json))
    }

    "a member's fields the model does not read are skipped" in {
        val json =
            """{"status":"administrator","user":{"id":7,"is_bot":true,"first_name":"Kyo"},"can_be_edited":false,"is_anonymous":false}"""
        assert(Json.decode[Telegram.ChatMember](json) == Result.succeed(Telegram.ChatMember(Telegram.ChatMember.Status.Administrator, bot)))
    }

    "each of the six documented statuses reads with its privilege fields dropped" in {
        import Telegram.ChatMember.Status
        def user(id: Long, name: String)     = Telegram.User(Telegram.UserId(id), false, name)
        def userJson(id: Long, name: String) = s"""{"id":$id,"is_bot":false,"first_name":"$name"}"""
        val json                             = Chunk(
            s"""{"status":"creator","user":${userJson(111111111, "Alice")},"is_anonymous":false,"custom_title":"founder"}""",
            s"""{"status":"administrator","user":$botJson,"can_be_edited":false,"is_anonymous":false,"can_manage_chat":true,""" +
                """"can_delete_messages":true,"can_restrict_members":true,"can_invite_users":true,"can_pin_messages":true}""",
            s"""{"status":"member","user":${userJson(222222222, "Bob")},"until_date":1767225600}""",
            s"""{"status":"restricted","user":${userJson(333333333, "Eve")},"is_member":true,"can_send_messages":false,"until_date":0}""",
            s"""{"status":"left","user":${userJson(444444444, "Mallory")}}""",
            s"""{"status":"kicked","user":${userJson(555555555, "Trudy")},"until_date":0}"""
        )
        val members = Chunk(
            Telegram.ChatMember(Status.Creator, user(111111111, "Alice")),
            Telegram.ChatMember(Status.Administrator, TelegramTest.Fixtures.bot),
            Telegram.ChatMember(Status.Member, user(222222222, "Bob")),
            Telegram.ChatMember(Status.Restricted, user(333333333, "Eve")),
            Telegram.ChatMember(Status.Left, user(444444444, "Mallory")),
            Telegram.ChatMember(Status.Kicked, user(555555555, "Trudy"))
        )
        assert(json.map(Json.decode[Telegram.ChatMember](_)) == members.map(Result.succeed(_)))
        assert(members.map(wire(_)) == members.map(m => wire(s"""{"status":${Json.encode(m.status)},"user":${Json.encode(m.user)}}""")))
    }

    "a status outside the six is Other" in {
        val json   = """{"status":"guest","user":{"id":666666666,"is_bot":false,"first_name":"Grace"}}"""
        val member =
            Telegram.ChatMember(Telegram.ChatMember.Status.Other("guest"), Telegram.User(Telegram.UserId(666666666L), false, "Grace"))
        assert(Json.decode[Telegram.ChatMember](json) == Result.succeed(member))
        assert(wire(member) == wire(json))
    }

    "the bot added to a group as administrator, and a member banned with fields the module does not model" in {
        import Telegram.ChatMember.Status
        val kyoBot    = TelegramTest.Fixtures.bot
        val trudy     = """{"id":555555555,"is_bot":false,"first_name":"Trudy"}"""
        val addedHead = s""""chat":$forumJson,"from":$aliceUserJson,"date":1735689700,"old_chat_member":{"status":"left","user":$botJson}"""
        val added     =
            s"""{$addedHead,"new_chat_member":{"status":"administrator","user":$botJson,"can_be_edited":false,"can_manage_chat":true}}"""
        val bannedHead =
            s""""chat":$forumJson,"from":$aliceUserJson,"date":1735689800,"old_chat_member":{"status":"member","user":$trudy}"""
        val banned =
            s"""{$bannedHead,"new_chat_member":{"status":"kicked","user":$trudy,"until_date":0},"via_join_request":false}"""
        val trudyUser = Telegram.User(Telegram.UserId(555555555L), false, "Trudy")
        val updates   = Chunk(
            Telegram.ChatMemberUpdate(
                forum,
                aliceUser,
                at(1735689700),
                Telegram.ChatMember(Status.Left, kyoBot),
                Telegram.ChatMember(Status.Administrator, kyoBot)
            ),
            Telegram.ChatMemberUpdate(
                forum,
                aliceUser,
                at(1735689800),
                Telegram.ChatMember(Status.Member, trudyUser),
                Telegram.ChatMember(Status.Kicked, trudyUser)
            )
        )
        assert(Chunk(added, banned).map(Json.decode[Telegram.ChatMemberUpdate](_)) == updates.map(Result.succeed(_)))
        assert(updates.map(wire(_)) == Chunk(
            wire(s"""{$addedHead,"new_chat_member":{"status":"administrator","user":$botJson}}"""),
            wire(s"""{$bannedHead,"new_chat_member":{"status":"kicked","user":$trudy}}""")
        ))
    }

    "each status is Telegram's name, and a status Telegram adds later is Other with that name" in {
        import Telegram.ChatMember.Status.*
        val statuses = Chunk(Creator, Administrator, Member, Restricted, Left, Kicked, Other("observer"))
        assert(statuses.map(Json.encode(_)) ==
            Chunk("\"creator\"", "\"administrator\"", "\"member\"", "\"restricted\"", "\"left\"", "\"kicked\"", "\"observer\""))
        assert(statuses.map(s => Json.decode[Telegram.ChatMember.Status](Json.encode(s))) == statuses.map(Result.succeed(_)))
    }

end TelegramChatMemberUpdateTest

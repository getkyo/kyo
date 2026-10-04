package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramCommandTest extends kyo.test.Test[Any]:

    "a name of 1 and of 32 characters from a-z 0-9 _ with a description of 1 and of 256 is accepted" in {
        val short = Telegram.Command.init("a", "d")
        val long  = Telegram.Command.init("start_2" + "x" * 25, "d" * 256)
        assert(short.map(c => (c.name, c.description)) == Result.succeed(("a", "d")))
        assert(long.map(c => (c.name, c.description)) == Result.succeed(("start_2" + "x" * 25, "d" * 256)))
    }

    "init fails with what Telegram would refuse" in {
        import TelegramInvalidCommandException.Problem
        val found = Chunk(
            Telegram.Command.init("", "d"),
            Telegram.Command.init("a" * 33, "d"),
            Telegram.Command.init("Start", "d"),
            Telegram.Command.init("go-on", "d"),
            Telegram.Command.init("go", ""),
            Telegram.Command.init("go", "d" * 257)
        )
        assert(found == Chunk(
            Problem.NameLength(0),
            Problem.NameLength(33),
            Problem.NameCharacter(0),
            Problem.NameCharacter(2),
            Problem.DescriptionLength(0),
            Problem.DescriptionLength(257)
        ).map(p => Result.fail(TelegramInvalidCommandException(p))))
    }

    "a command is Telegram's BotCommand, and one Telegram would refuse fails the decode" in {
        val command = Telegram.Command.init("standup", "Start today's standup").getOrThrow
        val json    = """{"command":"standup","description":"Start today's standup"}"""
        assert(wire(command) == wire(json))
        assert(Json.decode[Telegram.Command](json) == Result.succeed(command))
        assert(Json.decode[Telegram.Command]("""{"command":"Stand Up","description":"x"}""").isFailure)
    }

    "is_ephemeral, which the module does not model, is dropped" in {
        val command = Telegram.Command.init("peek", "Show only to me").getOrThrow
        assert(Json.decode[Telegram.Command]("""{"command":"peek","description":"Show only to me","is_ephemeral":true}""") ==
            Result.succeed(command))
        assert(wire(command) == wire("""{"command":"peek","description":"Show only to me"}"""))
    }

    "a menu holds up to 100 commands, and Menu.init refuses more" in {
        val commands = Chunk.tabulate(101)(i => Telegram.Command.init(s"c$i", "x").getOrThrow)
        assert(Telegram.Command.Menu.init(commands.take(100)*).map(_.commands.size) == Result.succeed(100))
        assert(Telegram.Command.Menu.init().map(_.commands) == Result.succeed(Chunk.empty))
        assert(Telegram.Command.Menu.init(commands*) ==
            Result.fail(TelegramInvalidCommandException(TelegramInvalidCommandException.Problem.TooMany(101))))
    }

    "each scope is Telegram's BotCommandScope, tagged by type, with chat_id and user_id" in {
        import Telegram.Command.Scope.*
        val chat   = Telegram.Chat.Target.Id(Telegram.ChatId(-100L))
        val scopes = Chunk[Telegram.Command.Scope](
            Default,
            AllPrivateChats,
            AllGroupChats,
            AllChatAdministrators,
            Chat(chat),
            ChatAdministrators(Telegram.Chat.Target.Username("team")),
            ChatMember(chat, Telegram.UserId(5L))
        )
        val json = Chunk(
            """{"type":"default"}""",
            """{"type":"all_private_chats"}""",
            """{"type":"all_group_chats"}""",
            """{"type":"all_chat_administrators"}""",
            """{"type":"chat","chat_id":-100}""",
            """{"type":"chat_administrators","chat_id":"@team"}""",
            """{"type":"chat_member","chat_id":-100,"user_id":5}"""
        )
        assert(scopes.map(wire(_)) == json.map(wire))
        assert(json.map(Json.decode[Telegram.Command.Scope](_)) == scopes.map(Result.succeed(_)))
    }

end TelegramCommandTest

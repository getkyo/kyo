package kyo

class DiscordCommandTest extends kyo.test.Test[Any]:

    import Discord.*
    import DiscordInvalidCommandException.Problem

    "a registered command decodes from Discord's answer, with nested options and choices" in {
        val json =
            """{"id":"1150000000000000001","application_id":"1100000000000000000","version":"1150000000000000002","default_member_permissions":null,
              |"type":1,"name":"roll","description":"Roll dice","options":[
              |{"type":4,"name":"sides","description":"How many sides","required":true,"min_value":2,"max_value":100},
              |{"type":3,"name":"mode","description":"How to roll","choices":[{"name":"Fair","value":"fair"},{"name":"Loaded","value":"loaded"}]}
              |],"nsfw":false}""".stripMargin
        assert(Json.decode[Command](json).map(c =>
            (c.name, c.`type`, c.options.map(o => (o.name, o.required, o.minValue, o.choices.map(_.value))))
        ) ==
            Result.succeed((
                "roll",
                Command.Type.ChatInput,
                Chunk(
                    ("sides", true, Present(Command.Value.Integer(2L)), Chunk.empty),
                    ("mode", false, Absent, Chunk(Command.Value.Text("fair"), Command.Value.Text("loaded")))
                )
            )))
    }

    "an option value is the bare JSON value of its kind" in {
        val values =
            Chunk(Command.Value.Text("a"), Command.Value.Integer(9007199254740991L), Command.Value.Number(2.5), Command.Value.Bool(true))
        assert(values.map(v => Json.encode(v)) == Chunk("\"a\"", "9007199254740991", "2.5", "true"))
        assert(values.map(v => Json.decode[Command.Value](Json.encode(v))) == values.map(Result.succeed(_)))
        assert(Json.decode[Command.Value]("[1]").failure.exists(_.isInstanceOf[NoVariantMatchException]))
        // a number with a fraction is a Number even though Integer is tried first
        assert(Json.decode[Command.Value]("1.5") == Result.succeed(Command.Value.Number(1.5)))
    }

    "the data of an invoked command decodes, with a subcommand's options, the focused option and a context menu's target" in {
        val chatInput = Json.decode[Command.Data](
            """{"id":"1","name":"dice","type":1,"options":[{"name":"roll","type":1,"options":[{"name":"sides","type":4,"value":6,"focused":true}]}]}"""
        )
        assert(chatInput.map(_.options) == Result.succeed(Chunk(Command.Data.Option(
            "roll",
            Command.Option.Type.Subcommand,
            options = Chunk(Command.Data.Option("sides", Command.Option.Type.Integer, Present(Command.Value.Integer(6L)), focused = true))
        ))))
        val contextMenu = Json.decode[Command.Data]("""{"id":"2","name":"Report","type":3,"target_id":"1209876543210987654"}""")
        assert(contextMenu.map(d => (d.`type`, d.targetId.map(MessageId(_)))) ==
            Result.succeed((Command.Type.Message, Present(MessageId(1209876543210987654L)))))
    }

    "Command.Create writes Discord's JSON and round-trips" in {
        val sides = Command.Option.init(
            Command.Option.Type.Integer,
            "sides",
            "How many sides",
            required = true,
            minValue = Present(Command.Value.Integer(2L))
        ).getOrThrow
        val create = Command.Create.init("roll", "Roll dice", options = Chunk(sides)).getOrThrow
        assert(Json.encode(create) ==
            """{"name":"roll","description":"Roll dice","type":1,"options":[{"type":4,"name":"sides","description":"How many sides",""" +
            """"required":true,"min_value":2,"autocomplete":false}],"nsfw":false}""")
        assert(Json.decode[Command.Create](Json.encode(create)) == Result.succeed(create))
    }

    "a user or message command has no description and no options" in {
        assert(Command.Create.init("Report Message", `type` = Command.Type.Message).map(c => Json.encode(c)) ==
            Result.succeed("""{"name":"Report Message","description":"","type":3,"nsfw":false}"""))
        assert(Command.Create.init("Report", "why", `type` = Command.Type.User).failure.map(_.problem) == Present(Problem.NotChatInput))
    }

    "init refuses what Discord would" in {
        val optional = Command.Option.init(Command.Option.Type.String, "b", "optional").getOrThrow
        val required = Command.Option.init(Command.Option.Type.String, "c", "required", required = true).getOrThrow
        val choice   = Command.Choice.init("A", Command.Value.Text("a")).getOrThrow
        assert(Chunk(
            Command.Create.init("", "d"),
            Command.Create.init("n" * 33, "d"),
            Command.Create.init("Roll", "d"),
            Command.Create.init("roll dice", "d"),
            Command.Create.init("roll", ""),
            Command.Create.init("roll", "d" * 101),
            Command.Create.init("roll", "d", options = Chunk.fill(26)(optional)),
            Command.Create.init("roll", "d", options = Chunk(optional, required))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.NameLength(0)),
            Present(Problem.NameLength(33)),
            Present(Problem.NameCharacter(0)),
            Present(Problem.NameCharacter(4)),
            Present(Problem.DescriptionLength(0, 100)),
            Present(Problem.DescriptionLength(101, 100)),
            Present(Problem.OptionCount(26)),
            Present(Problem.RequiredOrder("c"))
        ))
        assert(Chunk(
            Command.Option.init(Command.Option.Type.String, "s", "d", choices = Chunk.fill(26)(choice)),
            Command.Option.init(Command.Option.Type.String, "s", "d", choices = Chunk(choice), autocomplete = true),
            Command.Option.init(Command.Option.Type.String, "s", "d", minLength = Present(10), maxLength = Present(5)),
            Command.Option.init(Command.Option.Type.String, "s", "d", maxLength = Present(6001))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.ChoiceCount(26)),
            Present(Problem.ChoicesWithAutocomplete("s")),
            Present(Problem.StringLength(10, 5)),
            Present(Problem.StringLength(0, 6001))
        ))
        assert(Chunk(
            Command.Choice.init("", Command.Value.Text("a")),
            Command.Choice.init("A", Command.Value.Text("v" * 101))
        ).map(_.failure.map(_.problem)) == Chunk(Present(Problem.ChoiceName(0)), Present(Problem.ChoiceValue(101))))
        assert(Command.Create.init("Roll", "d").failure.exists(
            _.getMessage.contains(
                "Discord command is not usable: a name's character at position 0 is not a lowercase letter, a digit, - _ or '."
            )
        ))
    }

    "a name in another script is accepted as Discord's localized names are" in {
        assert(Command.Create.init("дайс", "d").isSuccess && Command.Create.init("it's-a_go", "d").isSuccess)
    }

end DiscordCommandTest

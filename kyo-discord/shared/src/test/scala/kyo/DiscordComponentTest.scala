package kyo

class DiscordComponentTest extends kyo.test.Test[Any]:

    import Discord.*
    import Discord.Component.*
    import DiscordInvalidComponentException.Problem

    private val rowJson =
        """{"type":1,"components":[""" +
            """{"type":2,"style":5,"label":"Docs","url":"https://discord.com/developers"},""" +
            """{"type":2,"style":4,"label":"Delete","custom_id":"del","emoji":{"id":null,"name":"🗑️"},"disabled":true}]}"""

    "each component kind decodes by its integer type" in {
        val decoded = Json.decode[Chunk[Component]](
            "[" + rowJson + "," +
                """{"type":3,"custom_id":"pick","options":[{"label":"A","value":"a","default":true}],"min_values":1,"max_values":1},""" +
                """{"type":4,"custom_id":"note","style":2,"label":"Note","required":false,"value":"typed"},""" +
                """{"type":5,"custom_id":"who"},""" +
                """{"type":6,"custom_id":"role"},""" +
                """{"type":7,"custom_id":"any"},""" +
                """{"type":8,"custom_id":"where","channel_types":[0,5]}""" +
                "]"
        )
        assert(decoded == Result.succeed(Chunk(
            ActionRow(Chunk(
                Button(Button.Style.Link, Present("Docs"), url = Present("https://discord.com/developers")),
                Button(Button.Style.Danger, Present("Delete"), Present(Emoji.unicode("🗑️")), Present("del"), disabled = true)
            )),
            StringSelect("pick", Chunk(SelectOption("A", "a", default = true)), minValues = Present(1), maxValues = Present(1)),
            TextInput("note", TextInput.Style.Paragraph, Present("Note"), required = false, value = Present("typed")),
            UserSelect("who"),
            RoleSelect("role"),
            MentionableSelect("any"),
            ChannelSelect("where", channelTypes = Chunk(Channel.Type.GuildText, Channel.Type.GuildAnnouncement))
        )))
    }

    "a component encodes with its integer type first and decodes back to itself" in {
        val components: Chunk[Component] = Json.decode[Chunk[Component]]("[" + rowJson + "]").getOrThrow
        val selects: Chunk[Component]    =
            Chunk(
                UserSelect("u"),
                RoleSelect("r"),
                MentionableSelect("m"),
                ChannelSelect("c", channelTypes = Chunk(Channel.Type.GuildText))
            )
        assert(selects.map(Json.encode(_)) == Chunk(
            """{"type":5,"custom_id":"u","disabled":false}""",
            """{"type":6,"custom_id":"r","disabled":false}""",
            """{"type":7,"custom_id":"m","disabled":false}""",
            """{"type":8,"custom_id":"c","disabled":false,"channel_types":[0]}"""
        ))
        assert(Json.decode[Chunk[Component]](Json.encode(components)) == Result.succeed(components))
        assert(Json.decode[Chunk[Component]](Json.encode(selects)) == Result.succeed(selects))
    }

    "a kind the model does not declare is kept whole as Other" in {
        val json  = """{"type":10,"id":3,"content":"# A Components V2 text display"}"""
        val other = Json.decode[Component](json).getOrThrow
        assert(other match
            case Other(10, payload) => payload.value == json
            case _                  => false)
        assert(Json.encode(other) == json)
    }

    "a value with no type, or a string type, is a decode failure" in {
        assert(Json.decode[Component]("""{"custom_id":"x"}""").failure.exists {
            case e: MissingFieldException => e.fieldName == "type"
            case _                        => false
        })
        assert(Json.decode[Component]("""{"type":"2","custom_id":"x"}""").failure.exists(_.isInstanceOf[TypeMismatchException]))
    }

    "Button.init" - {
        "a link button needs a URL and no custom id; any other button a custom id and no URL" in {
            assert(Chunk(
                Button.init(Button.Style.Link, customId = Present("x")),
                Button.init(Button.Style.Link),
                Button.init(Button.Style.Primary, url = Present("https://x")),
                Button.init(Button.Style.Primary)
            ).map(_.failure.map(_.problem)) == Chunk.fill(4)(Present(Problem.ButtonTarget)))
            assert(Button.init(Button.Style.Link, url = Present("https://x")).isSuccess)
        }

        "a custom id of 1 to 100 characters, a URL of up to 512, a label of up to 80" in {
            assert(Chunk(
                Button.init(Button.Style.Primary, customId = Present("")),
                Button.init(Button.Style.Primary, customId = Present("i" * 101)),
                Button.init(Button.Style.Link, url = Present("u" * 513)),
                Button.init(Button.Style.Primary, customId = Present("i"), label = Present("l" * 81))
            ).map(_.failure.map(_.problem)) == Chunk(
                Present(Problem.CustomId(0)),
                Present(Problem.CustomId(101)),
                Present(Problem.Url(513)),
                Present(Problem.Label(81, 80))
            ))
            assert(Button.init(Button.Style.Primary, customId = Present("i")).failure.isEmpty)
        }
    }

    "ActionRow.init holds 1 to 5 buttons or one other component" in {
        val button = Button.init(Button.Style.Primary, customId = Present("b")).getOrThrow
        val select = UserSelect.init("u").getOrThrow
        assert(Chunk(
            ActionRow.init(Chunk.empty),
            ActionRow.init(Chunk.fill(6)(button)),
            ActionRow.init(Chunk(button, select))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.RowContents(0)),
            Present(Problem.RowContents(6)),
            Present(Problem.RowContents(2))
        ))
        assert(ActionRow.init(Chunk.fill(5)(button)).isSuccess && ActionRow.init(Chunk(select)).isSuccess)
        assert(ActionRow.init(Chunk.empty).failure.exists(
            _.getMessage.contains("an action row of 0 components must hold 1 to 5 buttons or one other component")
        ))
    }

    "StringSelect.init and SelectOption.init" in {
        val option = SelectOption.init("A", "a").getOrThrow
        assert(Chunk(
            StringSelect.init("s", Chunk.empty),
            StringSelect.init("s", Chunk.fill(26)(option)),
            StringSelect.init("s", Chunk(option), placeholder = Present("p" * 151)),
            StringSelect.init("s", Chunk(option, option), minValues = Present(2), maxValues = Present(1)),
            StringSelect.init("s", Chunk(option), maxValues = Present(2))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.OptionCount(0)),
            Present(Problem.OptionCount(26)),
            Present(Problem.Placeholder(151, 150)),
            Present(Problem.Values(2, 1)),
            Present(Problem.Values(1, 2))
        ))
        assert(Chunk(
            SelectOption.init("", "a"),
            SelectOption.init("A", "v" * 101),
            SelectOption.init("A", "a", description = Present("d" * 101))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.OptionText("label", 0)),
            Present(Problem.OptionText("value", 101)),
            Present(Problem.OptionText("description", 101))
        ))
    }

    "each entity select's init refuses a custom id, placeholder or value bound outside Discord's" in {
        assert(Chunk(
            UserSelect.init(""),
            RoleSelect.init("r", placeholder = Present("p" * 151)),
            MentionableSelect.init("m", minValues = Present(3), maxValues = Present(2)),
            ChannelSelect.init("c", maxValues = Present(26))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.CustomId(0)),
            Present(Problem.Placeholder(151, 150)),
            Present(Problem.Values(3, 2)),
            Present(Problem.Values(1, 26))
        ))
        assert(ChannelSelect.init("c", channelTypes = Chunk(Channel.Type.GuildText)).map(_.channelTypes) ==
            Result.succeed(Chunk(Channel.Type.GuildText)))
    }

    "TextInput.init" in {
        assert(Chunk(
            TextInput.init("t", TextInput.Style.Short, label = Present("l" * 46)),
            TextInput.init("t", TextInput.Style.Short, minLength = Present(10), maxLength = Present(5)),
            TextInput.init("t", TextInput.Style.Short, maxLength = Present(4001)),
            TextInput.init("t", TextInput.Style.Short, value = Present("v" * 4001)),
            TextInput.init("t", TextInput.Style.Short, placeholder = Present("p" * 101))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.Label(46, 45)),
            Present(Problem.TextLength(10, 5)),
            Present(Problem.TextLength(0, 4001)),
            Present(Problem.Value(4001)),
            Present(Problem.Placeholder(101, 100))
        ))
    }

end DiscordComponentTest

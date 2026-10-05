package kyo

class DiscordInteractionResponseTest extends kyo.test.Test[Any]:

    import Discord.*
    import DiscordInvalidInteractionResponseException.Problem

    private val text  = Message.Create.init(content = Present("pong")).getOrThrow
    private val input = Component.TextInput.init("note", Component.TextInput.Style.Short, label = Present("Note")).getOrThrow
    private val row   = Component.ActionRow.init(Chunk(input)).getOrThrow

    "each answer writes Discord's {type, data}" in {
        val choice                              = Command.Choice.init("Six", Command.Value.Integer(6L)).getOrThrow
        val answers: Chunk[InteractionResponse] = Chunk(
            InteractionResponse.Message(text),
            InteractionResponse.DeferredMessage(),
            InteractionResponse.DeferredMessage(ephemeral = true),
            InteractionResponse.DeferredUpdate,
            InteractionResponse.UpdateMessage(Message.Edit.init(content = Present(Patch.Clear)).getOrThrow),
            InteractionResponse.Autocomplete.init(Chunk(choice)).getOrThrow,
            InteractionResponse.Modal.init("form", "Feedback", Chunk(row)).getOrThrow
        )
        assert(answers.map(a => Json.encode(a)) == Chunk(
            """{"type":4,"data":{"content":"pong","tts":false}}""",
            """{"type":5}""",
            """{"type":5,"data":{"flags":64}}""",
            """{"type":6}""",
            """{"type":7,"data":{"content":null}}""",
            """{"type":8,"data":{"choices":[{"name":"Six","value":6}]}}""",
            """{"type":9,"data":{"custom_id":"form","title":"Feedback","components":[{"type":1,"components":[""" +
                """{"type":4,"custom_id":"note","style":1,"label":"Note","required":true}]}]}}"""
        ))
        // An edit is only ever written: reading cannot tell a cleared field's null from an absent one, so the round trip uses a set.
        val readable =
            answers.updated(4, InteractionResponse.UpdateMessage(Message.Edit.init(content = Present(Patch.Set("new"))).getOrThrow))
        assert(readable.map(a => Json.decode[InteractionResponse](Json.encode(a))) == readable.map(Result.succeed(_)))
    }

    "an answer without data reads from Discord's form with no data key, and ephemeral from any flags holding bit 64" in {
        assert(Chunk("""{"type":5}""", """{"type":6}""", """{"type":5,"data":{"flags":4160}}""", """{"type":5,"data":null}""")
            .map(Json.decode[InteractionResponse](_)) == Chunk(
            Result.succeed(InteractionResponse.DeferredMessage()),
            Result.succeed(InteractionResponse.DeferredUpdate),
            Result.succeed(InteractionResponse.DeferredMessage(ephemeral = true)),
            Result.succeed(InteractionResponse.DeferredMessage())
        ))
    }

    "a callback type the model does not declare is an unknown variant" in {
        assert(Json.decode[InteractionResponse]("""{"type":12,"data":{}}""").failure.exists {
            case e: UnknownVariantException => e.variantName == "12"
            case _                          => false
        })
    }

    "Autocomplete holds at most 25 choices" in {
        val choice = Command.Choice.init("A", Command.Value.Text("a")).getOrThrow
        assert(InteractionResponse.Autocomplete.init(Chunk.fill(26)(choice)).failure.map(_.problem) == Present(Problem.ChoiceCount(26)))
        assert(InteractionResponse.Autocomplete.init(Chunk.fill(25)(choice)).isSuccess)
    }

    "Modal.init refuses a custom id outside 1 to 100, a title outside 1 to 45, and other than 1 to 5 components" in {
        assert(Chunk(
            InteractionResponse.Modal.init("", "t", Chunk(row)),
            InteractionResponse.Modal.init("f", "t" * 46, Chunk(row)),
            InteractionResponse.Modal.init("f", "t", Chunk.empty),
            InteractionResponse.Modal.init("f", "t", Chunk.fill(6)(row))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.CustomId(0)),
            Present(Problem.Title(46)),
            Present(Problem.ComponentCount(0)),
            Present(Problem.ComponentCount(6))
        ))
        assert(InteractionResponse.Modal.init("f", "t" * 46, Chunk(row)).failure.exists(
            _.getMessage.contains("Discord interaction answer is not usable: a modal title has 46 characters, outside 1 to 45.")
        ))
    }

    "an answer the interaction's kind does not admit does not compile" - {

        "a modal submission answered with another modal" in {
            typeCheckFailure(
                """def answer(modal: kyo.Discord.InteractionResponse.Modal): kyo.Discord.InteractionResponse.ToModalSubmit = modal"""
            )("kyo.Discord.InteractionResponse.ToModalSubmit")
        }

        "an autocomplete answered with a message" in {
            typeCheckFailure(
                """def answer(message: kyo.Discord.InteractionResponse.Message): kyo.Discord.InteractionResponse.ToAutocomplete = message"""
            )("kyo.Discord.InteractionResponse.ToAutocomplete")
        }

        "a command answered with an update of a component's message" in {
            typeCheckFailure(
                """def answer(update: kyo.Discord.InteractionResponse.UpdateMessage): kyo.Discord.InteractionResponse.ToCommand = update"""
            )("kyo.Discord.InteractionResponse.ToCommand")
        }
    }

    "a modal's submitted fields are found by custom id whether Discord nests them in action rows or labels" in {
        val data = Json.decode[Interaction.ModalData](
            """{"custom_id":"form","components":[
              |{"type":1,"components":[{"type":4,"id":1,"custom_id":"name","value":"Ada"}]},
              |{"type":18,"id":2,"component":{"type":3,"id":3,"custom_id":"color","values":["red","blue"]}}
              |]}""".stripMargin
        ).getOrThrow
        assert((data.text("name"), data.selected("color"), data.text("missing"), data.selected("name")) ==
            (Present("Ada"), Chunk("red", "blue"), Absent, Chunk.empty))
    }

end DiscordInteractionResponseTest

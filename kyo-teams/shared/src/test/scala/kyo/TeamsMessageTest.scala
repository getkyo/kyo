package kyo

class TeamsMessageTest extends kyo.test.Test[Any]:

    private def tree(json: String): Structure.Value = TeamsJsonTree(json)

    "a message encodes as a message Activity with every absent field left out" in {
        assert(tree(Json.encode(Teams.Message.Create.text("Order 42 shipped"))) == tree("""{"text":"Order 42 shipped","type":"message"}"""))
    }

    "every field a message sends round-trips" in {
        val bot     = Teams.Account(Teams.UserId.init("28:bot").getOrThrow, Present("Orders"))
        val message = Teams.Message.Create(
            text = Present("<at>Orders</at> done"),
            textFormat = Present(Teams.TextFormat.Markdown),
            entities = Chunk(Teams.Entity.Mention(bot, Present("<at>Orders</at>"))),
            summary = Present("done"),
            importance = Present(Teams.Importance.High)
        )
        assert(tree(Json.encode(message)) == tree(
            """{"text":"<at>Orders</at> done","textFormat":"markdown","entities":[{"type":"mention","mentioned":{"id":"28:bot","name":"Orders"},"text":"<at>Orders</at>"}],"summary":"done","importance":"high","type":"message"}"""
        ))
        assert(Json.decode[Teams.Message.Create](Json.encode(message)) == Result.succeed(message))
    }

end TeamsMessageTest

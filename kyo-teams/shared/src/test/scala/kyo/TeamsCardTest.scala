package kyo

class TeamsCardTest extends kyo.test.Test[Any]:

    import Teams.Card
    import Teams.Card.Action
    import Teams.Card.Element

    private val v14 = Card.Version.init("1.4").getOrThrow

    private def raw(json: String): Teams.RawJson = Teams.RawJson(Json.decode[Structure.Value](json).getOrThrow)

    private def tree(json: String): Structure.Value = TeamsJsonTree(json)

    "a card encodes as the Adaptive Card JSON, each absent field left out" in {
        val card = Card(
            v14,
            body = Chunk(
                Element.TextBlock("Order 42", wrap = Present(true), weight = Present(Card.Style.Weight.Bolder)),
                Element.InputText("note", placeholder = Present("Why?")),
                Element.ActionSet(Chunk(Action.Execute(title = Present("Approve"), verb = Present("approve"))))
            ),
            actions = Chunk(Action.OpenUrl("https://example.com/orders/42", Present("Open")))
        )
        assert(tree(Json.encode(card)) == tree("""{
            "version": "1.4",
            "body": [
                {"type": "TextBlock", "text": "Order 42", "wrap": true, "weight": "bolder"},
                {"type": "Input.Text", "id": "note", "placeholder": "Why?"},
                {"type": "ActionSet", "actions": [{"type": "Action.Execute", "title": "Approve", "verb": "approve"}]}
            ],
            "actions": [{"type": "Action.OpenUrl", "url": "https://example.com/orders/42", "title": "Open"}],
            "type": "AdaptiveCard"
        }"""))
        assert(Json.decode[Card](Json.encode(card)) == Result.succeed(card))
    }

    "the Universal Action Model's example card decodes, keys the module does not read ignored" in {
        val json =
            """{
            "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
            "type": "AdaptiveCard",
            "originator": "c9b4352b-a76b-43b9-88ff-80edddaa243b",
            "version": "1.4",
            "refresh": {"action": {"type": "Action.Execute", "title": "Submit", "verb": "personalDetailsCardRefresh"}, "userIds": []},
            "body": [
                {"type": "TextBlock", "text": "Present a form and submit it back to the originator"},
                {"type": "Input.Text", "id": "firstName", "placeholder": "What is your first name?"},
                {"type": "ActionSet", "actions": [{"type": "Action.Execute", "title": "Submit", "verb": "personalDetailsFormSubmit", "fallback": "Action.Submit"}]}
            ]
        }"""
        val refresh =
            Card.Refresh.init(Action.Execute(title = Present("Submit"), verb = Present("personalDetailsCardRefresh")), Chunk.empty)
        assert(Json.decode[Card](json) == Result.succeed(Card(
            v14,
            body = Chunk(
                Element.TextBlock("Present a form and submit it back to the originator"),
                Element.InputText("firstName", placeholder = Present("What is your first name?")),
                Element.ActionSet(Chunk(Action.Execute(title = Present("Submit"), verb = Present("personalDetailsFormSubmit"))))
            ),
            refresh = Present(refresh.getOrThrow)
        )))
    }

    "every modelled element and action round-trips" in {
        val card = Card(
            Card.Version.init("1.5").getOrThrow,
            body = Chunk(
                Element.Image("https://example.com/a.png", Present("a")),
                Element.Container(Chunk(Element.TextBlock(
                    "in",
                    size = Present(Card.Style.Size.Large),
                    color = Present(Card.Style.Color.Good)
                ))),
                Element.ColumnSet(Chunk(Card.Column(Chunk(Element.TextBlock("c")), Present("auto")))),
                Element.FactSet(Chunk(Card.Fact("Status", "open"))),
                Element.InputNumber("qty", min = Present(1.0), max = Present(9.0), value = Present(2.0)),
                Element.InputDate("due", value = Present("2026-10-02")),
                Element.InputToggle("urgent", "Urgent", valueOn = Present("y"), valueOff = Present("n")),
                Element.InputChoiceSet("size", Chunk(Card.Choice("Small", "s"), Card.Choice("Large", "l")), isMultiSelect = Present(false))
            ),
            actions = Chunk(
                Action.Submit(Present("Send"), Present(raw("""{"k":1}"""))),
                Action.ShowCard(Card(v14, body = Chunk(Element.TextBlock("more"))), Present("More")),
                Action.ToggleVisibility(Chunk("details"), Present("Details"))
            )
        )
        assert(Json.decode[Card](Json.encode(card)) == Result.succeed(card))
    }

    "an element or action of a type the module does not model keeps its JSON and encodes back to it" in {
        val json =
            """{"type":"AdaptiveCard","version":"1.4","body":[{"type":"RichTextBlock","inlines":[]}],"actions":[{"type":"Action.Popover","title":"p"}]}"""
        val card = Json.decode[Card](json)
        assert(card == Result.succeed(Card(
            v14,
            body = Chunk(Element.Other("RichTextBlock", raw("""{"type":"RichTextBlock","inlines":[]}"""))),
            actions = Chunk(Action.Other("Action.Popover", raw("""{"type":"Action.Popover","title":"p"}""")))
        )))
        assert(tree(Json.encode(card.getOrThrow)) == tree(json))
    }

    "a version is major.minor with each part 0 to 99" in {
        assert(Chunk("1.4", "1.5", "0.0", "99.99").map(t => Card.Version.init(t).map(_.value)) ==
            Chunk("1.4", "1.5", "0.0", "99.99").map(Result.succeed))
        assert(Chunk("1", "1.4.1", "", "1.", ".4", "a.b", "100.0", "1.-1").map(t => Card.Version.init(t).failure.map(_.problem)) ==
            Chunk.fill(8)(Present(TeamsInvalidCardException.Problem.Version)))
        assert(Json.decode[Card]("""{"type":"AdaptiveCard","version":"latest"}""").isFailure)
    }

    "a refresh lists at most 60 users, at init and at decode" in {
        val execute = Action.Execute(verb = Present("refresh"))
        val users   = Chunk.from(1 to 61).map(i => Teams.UserId.init(s"29:u$i").getOrThrow)
        assert(Card.Refresh.init(execute, users.take(60)).map(_.userIds.size) == Result.succeed(60))
        assert(Card.Refresh.init(execute, users).failure.map(_.problem) ==
            Present(TeamsInvalidCardException.Problem.RefreshUsers(61, 60)))
        val tooMany =
            s"""{"action":{"type":"Action.Execute","verb":"refresh"},"userIds":[${users.map(u => s"\"${u.value}\"").mkString(",")}]}"""
        assert(Json.decode[Card.Refresh](tooMany).isFailure)
    }

    "a version or refresh the schema refuses fails with the decode call's Frame" in {
        val decodeSite = summon[Frame]

        def refusedAt[A](json: String)(using schema: Schema[A]): Maybe[(Frame, TeamsInvalidCardException.Problem)] =
            Json.decode[A](json)(using summon[Json], schema, decodeSite) match
                case Result.Failure(e: ConstructorRejectedException) =>
                    e.rejection match
                        case leaf: TeamsInvalidCardException => Present((leaf.frame, leaf.problem))
                        case _                               => Absent
                case _ => Absent
        assert(refusedAt[Card.Version]("\"latest\"") == Present((decodeSite, TeamsInvalidCardException.Problem.Version)))
        val users   = Chunk.from(1 to 61).map(i => s"\"29:u$i\"").mkString(",")
        val tooMany = s"""{"action":{"type":"Action.Execute","verb":"refresh"},"userIds":[$users]}"""
        assert(refusedAt[Card.Refresh](tooMany) == Present((decodeSite, TeamsInvalidCardException.Problem.RefreshUsers(61, 60))))
    }

    "each action response encodes with the status code and type the Universal Action Model gives it" in {
        import Card.ActionResponse.*
        val failure                                              = Failure("NotFound", "No such order")
        def json(response: Card.ActionResponse): Structure.Value = tree(Json.encode[Card.ActionResponse](response))
        assert(json(ShowMessage("Approved")) ==
            tree("""{"type":"application/vnd.microsoft.activity.message","value":"Approved","statusCode":200}"""))
        assert(json(ShowCard(Card(v14))) ==
            tree("""{"type":"application/vnd.microsoft.card.adaptive","value":{"version":"1.4","type":"AdaptiveCard"},"statusCode":200}"""))
        assert(json(BadRequest(failure)) ==
            tree("""{"type":"application/vnd.microsoft.error","value":{"code":"NotFound","message":"No such order"},"statusCode":400}"""))
        assert(json(PreconditionFailed(failure)) == tree(
            """{"type":"application/vnd.microsoft.error.preconditionFailed","value":{"code":"NotFound","message":"No such order"},"statusCode":412}"""
        ))
        assert(json(LoginRequest(raw("""{"text":"sign in"}"""))) ==
            tree("""{"type":"application/vnd.microsoft.activity.loginRequest","value":{"text":"sign in"},"statusCode":401}"""))
        Chunk[Card.ActionResponse](ShowMessage("a"), BadRequest(failure)).foreach { r =>
            assert(Json.decode[Card.ActionResponse](Json.encode(r)) == Result.succeed(r))
        }
    }

    "a card in a message is an Adaptive Card attachment" in {
        val message = Teams.Message.Create.card(Card(v14))
        assert(tree(Json.encode(message)) == tree(
            """{"attachments":[{"contentType":"application/vnd.microsoft.card.adaptive","content":{"version":"1.4","type":"AdaptiveCard"}}],"type":"message"}"""
        ))
    }

end TeamsCardTest

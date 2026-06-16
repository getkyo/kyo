package kyo

import kyo.internal.SlackRawJson

class SlackBlockTest extends kyo.test.Test[Any]:

    private def render(blocks: SlackBlock*)(using Frame): String < Abort[SlackException] =
        SlackBlock.encode(Chunk.from(blocks)).map(Json.encode(_))

    "section with a markdown body and a button accessory renders the Block Kit shape" in {
        render(
            SlackBlock.Section(
                SlackBlock.Text.Markdown("*hello*"),
                accessory =
                    Present(SlackBlock.Element.Button("Go", SlackId.ActionId("go"), value = Present("v"), style = Present("primary")))
            )
        ).map { json =>
            assert(json.contains("\"type\":\"section\""), json)
            assert(json.contains("\"type\":\"mrkdwn\""), json)
            assert(json.contains("\"text\":\"*hello*\""), json)
            assert(json.contains("\"accessory\":{"), json)
            assert(json.contains("\"type\":\"button\""), json)
            assert(json.contains("\"action_id\":\"go\""), json)
            assert(json.contains("\"value\":\"v\""), json)
            assert(json.contains("\"style\":\"primary\""), json)
        }
    }

    "absent optional fields are omitted (a bare button has no value/style/url)" in {
        render(SlackBlock.Actions(Chunk(SlackBlock.Element.Button("Click", SlackId.ActionId("a"))))).map { json =>
            assert(json.contains("\"type\":\"actions\""), json)
            assert(json.contains("\"type\":\"button\""), json)
            assert(!json.contains("\"value\""), s"value should be omitted: $json")
            assert(!json.contains("\"style\""), s"style should be omitted: $json")
            assert(!json.contains("\"url\""), s"url should be omitted: $json")
        }
    }

    "header, divider, and context render their wire types" in {
        render(
            SlackBlock.Header("Title"),
            SlackBlock.Divider(),
            SlackBlock.Context(Chunk(SlackBlock.Text.Markdown("ctx")))
        ).map { json =>
            assert(json.contains("\"type\":\"header\""), json)
            assert(json.contains("\"type\":\"divider\""), json)
            assert(json.contains("\"type\":\"context\""), json)
            assert(json.startsWith("["), json)
            assert(json.endsWith("]"), json)
        }
    }

    "input block carries a plain_text_input element and a label" in {
        render(
            SlackBlock.Input(
                "Your name",
                SlackBlock.Element.TextInput(SlackId.ActionId("name"), multiline = true, placeholder = Present("type here")),
                blockId = Present(SlackId.BlockId("b1"))
            )
        ).map { json =>
            assert(json.contains("\"type\":\"input\""), json)
            assert(json.contains("\"type\":\"plain_text_input\""), json)
            assert(json.contains("\"action_id\":\"name\""), json)
            assert(json.contains("\"multiline\":true"), json)
            assert(json.contains("\"block_id\":\"b1\""), json)
            assert(json.contains("\"placeholder\":{"), json)
        }
    }

    "static select renders its options" in {
        render(
            SlackBlock.Actions(Chunk(
                SlackBlock.Element.Select(
                    SlackId.ActionId("pick"),
                    "choose",
                    Chunk(SlackBlock.Element.Option("One", "1"), SlackBlock.Element.Option("Two", "2"))
                )
            ))
        ).map { json =>
            assert(json.contains("\"type\":\"static_select\""), json)
            assert(json.contains("\"options\":["), json)
            assert(json.contains("\"value\":\"1\""), json)
            assert(json.contains("\"value\":\"2\""), json)
        }
    }

    "a Raw block splices its JSON, and a malformed Raw fails with SlackDecodeException" in {
        render(SlackBlock.Raw("""{"type":"video","title":"v"}""")).map { json =>
            assert(json.contains("\"type\":\"video\""), json)
        }.andThen {
            Abort.run[SlackException](SlackBlock.encode(Chunk(SlackBlock.Raw("not json")))).map { result =>
                result match
                    case Result.Failure(_: SlackDecodeException) => assert(true)
                    case other => assert(false, s"malformed Raw should fail SlackDecodeException, got: $other")
            }
        }
    }

    "the dsl builds blocks equal to the case-class form" in {
        import SlackBlock.dsl.*
        val built = blocks(
            section("*hi*", button("Go", "go")),
            divider,
            actions(button("X", "x"), select("pick", "choose", option("One", "1"))),
            input("Name", textInput("name", multiline = true))
        )
        val manual = Chunk[SlackBlock](
            SlackBlock.Section(
                SlackBlock.Text.Markdown("*hi*"),
                accessory = Present(SlackBlock.Element.Button("Go", SlackId.ActionId("go")))
            ),
            SlackBlock.Divider(),
            SlackBlock.Actions(Chunk(
                SlackBlock.Element.Button("X", SlackId.ActionId("x")),
                SlackBlock.Element.Select(SlackId.ActionId("pick"), "choose", Chunk(SlackBlock.Element.Option("One", "1")))
            )),
            SlackBlock.Input("Name", SlackBlock.Element.TextInput(SlackId.ActionId("name"), multiline = true))
        )
        assert(built == manual, s"dsl should equal the case-class form; got $built")
    }

    "the rendered JSON is valid and round-trips through the raw parser" in {
        render(SlackBlock.Header("Hi"), SlackBlock.Divider()).map { json =>
            Abort.run[SlackException](SlackRawJson.parse(json, "blocks")).map(r =>
                assert(r.isSuccess, s"rendered blocks should be valid JSON: $json")
            )
        }
    }

end SlackBlockTest

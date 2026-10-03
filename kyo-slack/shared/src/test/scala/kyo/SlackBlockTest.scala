package kyo

import SlackDecodeException.Failure
import SlackInvalidRawBlockException.Problem
import kyo.SlackLiterals.*

class SlackBlockTest extends kyo.test.Test[Any]:

    private def render(blocks: SlackBlock*)(using Frame): String =
        Json.encode(SlackBlock.encode(Chunk.from(blocks)))

    "section with a markdown body and a button accessory renders the Block Kit shape" in {
        val json = render(
            SlackBlock.Section(
                SlackBlock.Text.Markdown("*hello*"),
                accessory =
                    Present(SlackBlock.Element.Button(
                        "Go",
                        SlackId.ActionId("go"),
                        value = Present("v"),
                        style = Present(SlackBlock.Element.ButtonStyle.Primary)
                    ))
            )
        )
        assert(
            json ==
                """[{"type":"section","text":{"type":"mrkdwn","text":"*hello*"},"accessory":{"type":"button","text":{"type":"plain_text","text":"Go","emoji":true},"action_id":"go","value":"v","style":"primary"}}]"""
        )
    }

    "a button's style is one of Slack's two, rendered as its wire word" in {
        import SlackBlock.Element.ButtonStyle
        val styles = Chunk(ButtonStyle.Primary, ButtonStyle.Danger)
        assert(styles.map(s =>
            render(SlackBlock.Actions(Chunk(SlackBlock.Element.Button("B", SlackId.ActionId("b"), style = Present(s)))))
        ) ==
            Chunk("primary", "danger").map(w =>
                s"""[{"type":"actions","elements":[{"type":"button","text":{"type":"plain_text","text":"B","emoji":true},"action_id":"b","style":"$w"}]}]"""
            ))
    }

    "absent optional fields are omitted (a bare button has no value/style/url)" in {
        val json = render(SlackBlock.Actions(Chunk(SlackBlock.Element.Button("Click", SlackId.ActionId("a")))))
        assert(
            json ==
                """[{"type":"actions","elements":[{"type":"button","text":{"type":"plain_text","text":"Click","emoji":true},"action_id":"a"}]}]"""
        )
    }

    "header, divider, and context render their wire types" in {
        val json = render(
            SlackBlock.Header("Title"),
            SlackBlock.Divider(),
            SlackBlock.Context(Chunk(SlackBlock.Text.Markdown("ctx")))
        )
        assert(
            json ==
                """[{"type":"header","text":{"type":"plain_text","text":"Title","emoji":true}},{"type":"divider"},{"type":"context","elements":[{"type":"mrkdwn","text":"ctx"}]}]"""
        )
    }

    "input block carries a plain_text_input element and a label" in {
        val json = render(
            SlackBlock.Input(
                "Your name",
                SlackBlock.Element.TextInput(SlackId.ActionId("name"), multiline = true, placeholder = Present("type here")),
                blockId = Present(SlackId.BlockId("b1"))
            )
        )
        assert(
            json ==
                """[{"type":"input","label":{"type":"plain_text","text":"Your name","emoji":true},"element":{"type":"plain_text_input","action_id":"name","multiline":true,"placeholder":{"type":"plain_text","text":"type here","emoji":true}},"block_id":"b1","optional":false}]"""
        )
    }

    "static select renders its options" in {
        val json = render(
            SlackBlock.Actions(Chunk(
                SlackBlock.Element.Select(
                    SlackId.ActionId("pick"),
                    "choose",
                    Chunk(SlackBlock.Element.SelectOption("One", "1"), SlackBlock.Element.SelectOption("Two", "2"))
                )
            ))
        )
        assert(
            json ==
                """[{"type":"actions","elements":[{"type":"static_select","action_id":"pick","placeholder":{"type":"plain_text","text":"choose","emoji":true},"options":[{"text":{"type":"plain_text","text":"One","emoji":true},"value":"1"},{"text":{"type":"plain_text","text":"Two","emoji":true},"value":"2"}]}]}]"""
        )
    }

    "an image block renders its url, alt text and title" in {
        val json = render(SlackBlock.Image(urlOf("https://example.com/a.png"), "alt", title = Present("t")))
        assert(
            json ==
                """[{"type":"image","image_url":"https://example.com/a.png","alt_text":"alt","title":{"type":"plain_text","text":"t","emoji":true}}]"""
        )
    }

    "a Raw block splices its JSON natively, not as a quoted string" in {
        val json = render(SlackBlock.Header("h"), rawOf("""{"type":"video","title":"v"}"""))
        assert(json == """[{"type":"header","text":{"type":"plain_text","text":"h","emoji":true}},{"type":"video","title":"v"}]""")
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
                SlackBlock.Element.Select(SlackId.ActionId("pick"), "choose", Chunk(SlackBlock.Element.SelectOption("One", "1")))
            )),
            SlackBlock.Input("Name", SlackBlock.Element.TextInput(SlackId.ActionId("name"), multiline = true))
        )
        assert(built == manual, s"dsl should equal the case-class form; got $built")
    }

    "the rendered JSON is valid and round-trips through kyo-schema-json's reader" in {
        val json = render(SlackBlock.Header("Hi"), SlackBlock.Divider())
        assert(Json.decode[Structure.Value](json).map(Json.encode(_)) == Result.succeed(json))
    }

    private def notJson(position: Int)(using Frame) =
        Result.fail(SlackInvalidRawBlockException(Problem.NotJson(Failure.Parse, Present(position))))

    "a Raw that is not JSON fails init, before anything is sent" in {
        assert(SlackBlock.Raw.init("not json") == notJson(0))
    }

    "a Raw rejection names kyo-schema-json's reading: its failure kind and the position its reader stopped at" in {
        val truncated = Result.fail(SlackInvalidRawBlockException(Problem.NotJson(Failure.TruncatedInput, Absent)))
        val cases     = List(
            ""                   -> truncated,
            "<p>"                -> notJson(0),
            """{"a":1"""         -> truncated,
            """{"a" 1}"""        -> notJson(5),
            """{"a":1 "b":2}"""  -> notJson(7),
            """["a" 1]"""        -> notJson(5),
            "{\"a\":\"x"         -> truncated,
            """{"a":"\q"}"""     -> notJson(7),
            """{"a":"\uZZZZ"}""" -> notJson(8),
            """{"a":tru}"""      -> notJson(5),
            """{"a":-}"""        -> notJson(6),
            """{"a":1} x"""      -> notJson(8)
        )
        val results = cases.map { case (json, _) => SlackBlock.Raw.init(json) }
        assert(results == cases.map(_._2), results.toString)
    }

    "a Raw nested past kyo-schema-json's depth bound is rejected as that limit, with no position" in {
        assert(SlackBlock.Raw.init("[" * 600 + "]" * 600) == Result.fail(
            SlackInvalidRawBlockException(Problem.NotJson(Failure.LimitExceeded, Absent))
        ))
    }

    // RFC 8259 section 6: `int = zero / ( digit1-9 *DIGIT )`, so a leading zero is not a number.
    "a Raw number with a leading zero is rejected" in {
        val results = List("""{"a":01}""", """{"a":-01}""", """{"a":00.5}""").map(json => SlackBlock.Raw.init(json))
        assert(results == List(notJson(6), notJson(7), notJson(6)), results.toString)
    }

    // RFC 8259 section 7: `unescaped = %x20-21 / %x23-5B / %x5D-10FFFF`, so U+0000 to U+001F must be escaped.
    "a Raw string holding an unescaped control character is rejected" in {
        val results = List('\u0000', '\t', '\n', '\u001f').map(c => SlackBlock.Raw.init(s"""{"a":"x${c}y"}"""))
        assert(results == List.fill(4)(notJson(7)), results.toString)
    }

    "a Raw whose top-level value is not an object is rejected: a block is an object" in {
        val results = List("1", "[]", " \"s\"", "null").map(json => SlackBlock.Raw.init(json))
        assert(results == List.fill(4)(Result.fail(SlackInvalidRawBlockException(Problem.NotAnObject))), results.toString)
    }

    // RFC 8259 section 7 allows any code point in a `\u` escape; kyo-schema-json refuses a lone surrogate, which no UTF-8 text
    // can carry, and the module follows its reader.
    "a Raw string with a lone surrogate escape is rejected at the escape that leaves it unpaired" in {
        assert(SlackBlock.Raw.init("""{"a":"\uD800"}""") == notJson(12))
    }

    "a Raw number is re-emitted with its exact value, including one no Double holds" in {
        val json = render(rawOf("""{"big":1e999,"precise":0.1000000000000000000001,"small":1.5E-7,"int":-0}"""))
        assert(json == """[{"big":1E+999,"precise":0.1000000000000000000001,"small":1.5E-7,"int":0}]""", json)
    }

    // RFC 8259 section 9 lets a reader limit the range of numbers; kyo-schema-json holds 1000 significand digits and an exponent
    // magnitude of 999999999.
    "a Raw number beyond the reader's bound is rejected with the typed leaf at the number, not an untyped throw" in {
        val results = List(
            """{"a":1e99999999999}""",
            """{"a":1e1000000000}""",
            """{"a":-1.5e-1000000000}""",
            s"""{"a":${"1" * 1001}}""",
            s"""{"a":0.${"1" * 1000}}"""
        ).map(json => SlackBlock.Raw.init(json))
        assert(results == List.fill(5)(notJson(5)), results.toString)
    }

    "a Raw number at the reader's bound is kept exactly" in {
        val json = render(rawOf(s"""{"a":${"1" * 1000},"b":1e999999999,"c":1e-00000999999999}"""))
        assert(json == s"""[{"a":${"1" * 1000},"b":1E+999999999,"c":1E-999999999}]""", json)
    }

end SlackBlockTest

package kyo

import kyo.internal.slack.RawJson

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
        val json = render(SlackBlock.Image(HttpUrl.parse("https://example.com/a.png").getOrThrow, "alt", title = Present("t")))
        assert(
            json ==
                """[{"type":"image","image_url":"https://example.com/a.png","alt_text":"alt","title":{"type":"plain_text","text":"t","emoji":true}}]"""
        )
    }

    "a Raw block splices its JSON natively, not as a quoted string" in {
        val json = render(SlackBlock.Header("h"), SlackBlock.Raw("""{"type":"video","title":"v"}"""))
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

    "the rendered JSON is valid and round-trips through the raw parser" in {
        val json = render(SlackBlock.Header("Hi"), SlackBlock.Divider())
        assert(RawJson.parse(json).map(Json.encode(_)) == Result.succeed(json))
    }

    "a Raw that is not JSON is rejected when it is built, not when it is sent" in {
        assert(Result(SlackBlock.Raw("not json")) == Result.panic(
            SlackInvalidRawBlockException(0, SlackInvalidRawBlockException.Problem.InvalidLiteral)
        ))
    }

    "a Raw rejection names the position and what the reader found" in {
        import SlackInvalidRawBlockException.Problem
        val cases = List(
            ""                      -> SlackInvalidRawBlockException(0, Problem.UnexpectedEnd),
            "<p>"                   -> SlackInvalidRawBlockException(0, Problem.UnexpectedCharacter('<')),
            """{"a":1"""            -> SlackInvalidRawBlockException(6, Problem.UnexpectedEnd),
            """{"a" 1}"""           -> SlackInvalidRawBlockException(5, Problem.Expected(Chunk(':'), '1')),
            """{"a":1 "b":2}"""     -> SlackInvalidRawBlockException(7, Problem.Expected(Chunk(',', '}'), '"')),
            """["a" 1]"""           -> SlackInvalidRawBlockException(5, Problem.Expected(Chunk(',', ']'), '1')),
            "{\"a\":\"x"            -> SlackInvalidRawBlockException(7, Problem.UnterminatedString),
            """{"a":"\q"}"""        -> SlackInvalidRawBlockException(8, Problem.InvalidEscape('q')),
            """{"a":"\uZZZZ"}"""    -> SlackInvalidRawBlockException(8, Problem.InvalidUnicodeEscape),
            """{"a":tru}"""         -> SlackInvalidRawBlockException(5, Problem.InvalidLiteral),
            """{"a":-}"""           -> SlackInvalidRawBlockException(6, Problem.InvalidNumber),
            """{"a":1} x"""         -> SlackInvalidRawBlockException(8, Problem.TrailingContent),
            ("[" * 600 + "]" * 600) -> SlackInvalidRawBlockException(512, Problem.TooDeep(512))
        )
        val results = cases.map { case (json, _) => Result(SlackBlock.Raw(json)) }
        assert(results == cases.map { case (_, ex) => Result.panic(ex) })
    }

    // RFC 8259 section 6: `int = zero / ( digit1-9 *DIGIT )`, so a leading zero is not a number.
    "a Raw number with a leading zero is rejected" in {
        import SlackInvalidRawBlockException.Problem
        val results = List("""{"a":01}""", """{"a":-01}""", """{"a":00.5}""").map(json => Result(SlackBlock.Raw(json)))
        assert(
            results == List(
                Result.panic(SlackInvalidRawBlockException(6, Problem.InvalidNumber)),
                Result.panic(SlackInvalidRawBlockException(7, Problem.InvalidNumber)),
                Result.panic(SlackInvalidRawBlockException(6, Problem.InvalidNumber))
            ),
            results.toString
        )
    }

    // RFC 8259 section 7: `unescaped = %x20-21 / %x23-5B / %x5D-10FFFF`, so U+0000 to U+001F must be escaped.
    "a Raw string holding an unescaped control character is rejected" in {
        import SlackInvalidRawBlockException.Problem
        val results = List('\u0000', '\t', '\n', '\u001f').map(c => Result(SlackBlock.Raw(s"""{"a":"x${c}y"}""")))
        assert(
            results == List('\u0000', '\t', '\n', '\u001f').map(c =>
                Result.panic(SlackInvalidRawBlockException(7, Problem.UnescapedControlCharacter(c)))
            ),
            results.toString
        )
    }

    "a Raw whose top-level value is not an object is rejected: a block is an object" in {
        import SlackInvalidRawBlockException.Problem
        val results = List("1", "[]", " \"s\"", "null").map(json => Result(SlackBlock.Raw(json)))
        assert(
            results == List(
                Result.panic(SlackInvalidRawBlockException(0, Problem.NotAnObject)),
                Result.panic(SlackInvalidRawBlockException(0, Problem.NotAnObject)),
                Result.panic(SlackInvalidRawBlockException(1, Problem.NotAnObject)),
                Result.panic(SlackInvalidRawBlockException(0, Problem.NotAnObject))
            ),
            results.toString
        )
    }

    // RFC 8259 section 7 allows any code point in a `\u` escape, including a lone surrogate.
    "a Raw string with a lone surrogate escape is JSON and is kept as written" in {
        assert(render(SlackBlock.Raw("""{"a":"\uD800"}""")) == """[{"a":""" + Json.encode("\uD800") + "}]")
    }

    "a Raw number is re-emitted with its exact value, including one no Double holds" in {
        val json = render(SlackBlock.Raw("""{"big":1e999,"precise":0.1000000000000000000001,"small":1.5E-7,"int":-0}"""))
        assert(json == """[{"big":1E+999,"precise":0.1000000000000000000001,"small":1.5E-7,"int":0}]""", json)
    }

    // RFC 8259 section 9 lets a reader limit the range of numbers. Past 1000 digits, re-emitting a
    // number costs time that grows with the square of its length, and an exponent past 999999999
    // leaves the range a BigDecimal holds on at least one platform.
    "a Raw number beyond the reader's bound is rejected with the typed leaf, not an untyped throw" in {
        import SlackInvalidRawBlockException.Problem
        val outOfRange = Problem.NumberOutOfRange(1000, 999999999)
        val results    = List(
            """{"a":1e99999999999}""",
            """{"a":1e1000000000}""",
            """{"a":-1.5e-1000000000}""",
            s"""{"a":${"1" * 1001}}""",
            s"""{"a":0.${"1" * 1000}}"""
        ).map(json => Result(SlackBlock.Raw(json)))
        assert(results == List.fill(5)(Result.panic(SlackInvalidRawBlockException(5, outOfRange))), results.toString)
    }

    "a Raw number at the reader's bound is kept exactly" in {
        val json = render(SlackBlock.Raw(s"""{"a":${"1" * 1000},"b":1e999999999,"c":1e-00000999999999}"""))
        assert(json == s"""[{"a":${"1" * 1000},"b":1E+999999999,"c":1E-999999999}]""", json)
    }

end SlackBlockTest

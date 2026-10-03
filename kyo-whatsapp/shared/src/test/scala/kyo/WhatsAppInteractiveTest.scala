package kyo

/** The interactive object against Meta's documented shapes: each decodes to the value and encodes back to the same JSON. The list,
  * reply-buttons and cta_url examples are round-tripped as whole sends in `WhatsAppMessageTest`.
  */
class WhatsAppInteractiveTest extends BaseWhatsAppTest:

    def roundTrips(json: String, expected: WhatsAppInteractive)(using Frame, kyo.test.AssertScope): Unit =
        assert(Json.decode[WhatsAppInteractive](json) == Result.succeed(expected))
        assert(unordered(Json.encode[WhatsAppInteractive](expected)) == unordered(json))

    "a flow by id that navigates to a screen is Meta's flow example, which leaves mode out" in {
        roundTrips(
            """{"type":"flow","header":{"type":"text","text":"Flow message header"},"body":{"text":"Flow message body"},
              |"footer":{"text":"Flow message footer"},"action":{"name":"flow","parameters":{"flow_message_version":"3",
              |"flow_token":"AQAAAAACS5FpgQ_cAAAAAD0QI3s.","flow_id":"123456","flow_cta":"Book!","flow_action":"navigate",
              |"flow_action_payload":{"screen":"<SCREEN_NAME>",
              |"data":"{\"product_name\":\"name\",\"product_description\":\"description\",\"product_price\":100}"}}}}""".stripMargin,
            WhatsAppInteractive.Flow(
                "AQAAAAACS5FpgQ_cAAAAAD0QI3s.",
                WhatsAppInteractive.Flow.Ref.ById("123456"),
                "Book!",
                WhatsAppInteractive.Flow.Start.Navigate(
                    "<SCREEN_NAME>",
                    Present(Structure.Value.Str("""{"product_name":"name","product_description":"description","product_price":100}"""))
                ),
                body = Present("Flow message body"),
                header = Present(WhatsAppInteractive.Header.Text("Flow message header")),
                footer = Present("Flow message footer")
            )
        )
    }

    "a draft flow by name that lets its endpoint choose the screen writes flow_name, data_exchange, mode and no payload" in {
        roundTrips(
            """{"type":"flow","action":{"name":"flow","parameters":{"flow_message_version":"3","flow_token":"tok2",
              |"flow_name":"feedback_survey","flow_cta":"Start","flow_action":"data_exchange","mode":"draft"}}}""".stripMargin,
            WhatsAppInteractive.Flow(
                "tok2",
                WhatsAppInteractive.Flow.Ref.ByName("feedback_survey"),
                "Start",
                WhatsAppInteractive.Flow.Start.DataExchange,
                WhatsAppInteractive.Flow.Mode.Draft
            )
        )
    }

    "a single product holds its catalog and retailer id, with no header" in {
        roundTrips(
            """{"type":"product","body":{"text":"optional body text"},"footer":{"text":"optional footer text"},
              |"action":{"catalog_id":"CATALOG_ID","product_retailer_id":"ID_TEST_ITEM_1"}}""".stripMargin,
            WhatsAppInteractive.Product("CATALOG_ID", "ID_TEST_ITEM_1", Present("optional body text"), Present("optional footer text"))
        )
    }

    "a product list holds its required text header, body, and sections of product items" in {
        roundTrips(
            """{"type":"product_list","header":{"type":"text","text":"Our top picks for you"},"body":{"text":"Check out these items"},
              |"footer":{"text":"Sale ends Sunday"},"action":{"catalog_id":"CATALOG_ID","sections":[
              |{"title":"Succulents","product_items":[{"product_retailer_id":"SKU_1001"},{"product_retailer_id":"SKU_1002"}]},
              |{"title":"Planters","product_items":[{"product_retailer_id":"SKU_2001"}]}]}}""".stripMargin,
            WhatsAppInteractive.ProductList(
                "CATALOG_ID",
                "Our top picks for you",
                "Check out these items",
                Chunk(
                    WhatsAppInteractive.ProductSection.of("Succulents", Chunk("SKU_1001", "SKU_1002")),
                    WhatsAppInteractive.ProductSection.of("Planters", Chunk("SKU_2001"))
                ),
                Present("Sale ends Sunday")
            )
        )
    }

    "a header holds a video or a document under its type" in {
        roundTrips(
            """{"type":"button","header":{"type":"video","video":{"link":"https://example.com/v.mp4"}},
              |"action":{"buttons":[{"type":"reply","reply":{"id":"a","title":"A"}}]}}""".stripMargin,
            WhatsAppInteractive.Buttons(
                Chunk(WhatsAppInteractive.ReplyButton("a", "A")),
                header = Present(WhatsAppInteractive.Header.Video(WhatsAppMedia.Source.ByLink(url("https://example.com/v.mp4"))))
            )
        )
        roundTrips(
            """{"type":"button","header":{"type":"document","document":{"id":"DOC","filename":"menu.pdf"}},
              |"action":{"buttons":[{"type":"reply","reply":{"id":"a","title":"A"}}]}}""".stripMargin,
            WhatsAppInteractive.Buttons(
                Chunk(WhatsAppInteractive.ReplyButton("a", "A")),
                header =
                    Present(WhatsAppInteractive.Header.Document(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("DOC")), Present("menu.pdf")))
            )
        )
    }

    "a cta_url whose url does not parse is refused at its path" in {
        Json.decode[WhatsAppInteractive](
            """{"type":"cta_url","action":{"name":"cta_url","parameters":{"display_text":"Go","url":"not a url"}}}"""
        ) match
            case Result.Failure(e: ConstructorRejectedException) => assert(Chunk.from(e.path).lastMaybe == Present("url"), e.path.toString)
            case other                                           => fail(s"expected a ConstructorRejectedException at url, got $other")
    }

    "a media source with neither an id nor a link does not decode" in {
        assert(Json.decode[WhatsAppMedia.Link]("""{"caption":"x"}""").isFailure)
    }

end WhatsAppInteractiveTest

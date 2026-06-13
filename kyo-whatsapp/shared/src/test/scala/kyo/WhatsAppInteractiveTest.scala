package kyo

import kyo.WhatsAppId.*
import kyo.WhatsAppInteractive.*

class WhatsAppInteractiveTest extends BaseWhatsAppTest:

    "every WhatsAppInteractive variant exposes body/header/footer" in {
        val listMenu = ListMenu(
            "Choose",
            Chunk(Section("S", Chunk(Row("r", "Row")))),
            Present("body"),
            Present(Header.Text("h")),
            Present("footer")
        )
        val buttons  = Buttons(Chunk(ReplyButton("b", "B")), Present("body"), Absent, Absent)
        val ctaUrl   = CtaUrl("Go", "https://ex.com", Present("body"), Absent, Absent)
        val flow     = Flow("tok", Flow.Ref.ById("F"), "Start")
        val product  = Product("CAT", "SKU1")
        val prodList = ProductList("CAT", "Top picks", "Check these", Chunk(ProductSection("Sec", Chunk("SKU1"))))
        assert(listMenu.body == Present("body"))
        assert(listMenu.header == Present(Header.Text("h")))
        assert(listMenu.footer == Present("footer"))
        assert(buttons.body == Present("body"))
        assert(buttons.header == Absent)
        assert(buttons.footer == Absent)
        assert(ctaUrl.body == Present("body"))
        assert(ctaUrl.header == Absent)
        assert(ctaUrl.footer == Absent)
        assert(flow.body == Absent)
        assert(flow.header == Absent)
        assert(flow.footer == Absent)
        assert(product.header == Absent)
        assert(product.body == Absent)
        assert(product.footer == Absent)
        assert(prodList.header == Present(Header.Text("Top picks")))
        assert(prodList.body == Present("Check these"))
        assert(prodList.footer == Absent)
    }

    "Header is a sealed union of text/media/document" in {
        val th: Header = Header.Text("h")
        val mh: Header = Header.Media(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("m")), Header.MediaKind.Image)
        val dh: Header = Header.Document(WhatsAppMedia.Source.ByLink("l"), Present("menu.pdf"))
        th match
            case Header.Text(t) => assert(t == "h")
            case _              => assert(false)
        mh match
            case Header.Media(_, kind) => assert(kind == Header.MediaKind.Image)
            case _                     => assert(false)
        dh match
            case Header.Document(_, fn) => assert(fn == Present("menu.pdf"))
            case _                      => assert(false)
    }

    "ListMenu carries button + sections + optionals" in {
        val lm = ListMenu(
            "Shipping",
            Chunk(Section("ASAP", Chunk(Row("priority_express", "Priority Mail Express", Present("Next Day to 2 Days"))))),
            Present("Which option?"),
            Present(Header.Text("Choose")),
            Present("footer")
        )
        assert(lm.button == "Shipping")
        assert(lm.sections.head.rows.head.id == "priority_express")
        assert(lm.sections.head.rows.head.title == "Priority Mail Express")
        assert(lm.sections.head.rows.head.description == Present("Next Day to 2 Days"))
        assert(lm.body == Present("Which option?"))
    }

    "Buttons carries up to 3 reply buttons" in {
        val btns = Buttons(Chunk(ReplyButton("track", "Track shipment"), ReplyButton("support", "Contact support")))
        assert(btns.buttons.size == 2)
        assert(btns.buttons.head.id == "track")
        assert(btns.buttons.head.title == "Track shipment")
    }

    "Flow.Ref is flow_id-XOR-flow_name exclusive" in {
        val byId: Flow.Ref   = Flow.Ref.ById("FLOW1")
        val byName: Flow.Ref = Flow.Ref.ByName("survey")
        byId match
            case Flow.Ref.ById(id) => assert(id == "FLOW1")
            case _                 => assert(false)
        byName match
            case Flow.Ref.ByName(n) => assert(n == "survey")
            case _                  => assert(false)
        typeCheckFailure("""WhatsAppInteractive.Flow.Ref.ById(flowId = "F", flowName = "N")""")
    }

    "Flow carries token/ref/cta/action/mode with documented defaults" in {
        val f = Flow("TOKEN", Flow.Ref.ById("F"), "Start survey")
        assert(f.action.isInstanceOf[Flow.Action.Navigate])
        assert(f.mode == Flow.Mode.Published)
    }

    "Product has no header; ProductList requires headerText+bodyText" in {
        val prod     = Product("CAT", "SKU_1")
        val prodList = ProductList("CAT", "Top picks", "Check these", Chunk(ProductSection("Succulents", Chunk("SKU_1001"))))
        assert(prod.header == Absent)
        assert(prodList.headerText == "Top picks")
        assert(prodList.bodyText == "Check these")
        assert(prodList.body == Present("Check these"))
        assert(prodList.header == Present(Header.Text("Top picks")))
    }

    "list encodes to the expected list JSON shape" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val listMenu = WhatsAppInteractive.ListMenu(
            "Shipping Options",
            Chunk(
                WhatsAppInteractive.Section(
                    "I want it ASAP!",
                    Chunk(
                        WhatsAppInteractive.Row("priority_express", "Priority Mail Express", Present("Next Day to 2 Days")),
                        WhatsAppInteractive.Row("priority_mail", "Priority Mail", Present("1-3 Days"))
                    )
                ),
                WhatsAppInteractive.Section(
                    "I can wait a bit",
                    Chunk(
                        WhatsAppInteractive.Row("usps_ground_advantage", "USPS Ground Advantage", Present("2-5 Days"))
                    )
                )
            ),
            Present("Which shipping option do you prefer?"),
            Present(WhatsAppInteractive.Header.Text("Choose Shipping Option")),
            Present("Lucky Shrub: Your gateway to succulents!")
        )
        val msg  = WhatsAppMessage.OfInteractive(listMenu)
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"type\":\"list\""))
        assert(json.contains("\"type\":\"text\",\"text\":\"Choose Shipping Option\""))
        assert(json.contains("\"button\":\"Shipping Options\""))
        assert(json.contains("\"id\":\"priority_express\""))
        assert(json.contains("\"description\":\"Next Day to 2 Days\""))
        assert(json.contains("\"id\":\"usps_ground_advantage\""))
    }

    "buttons encodes action.buttons[].reply with type:reply" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val buttons = WhatsAppInteractive.Buttons(
            Chunk(
                WhatsAppInteractive.ReplyButton("track_shipment", "Track shipment"),
                WhatsAppInteractive.ReplyButton("contact_support", "Contact support")
            ),
            Present("Would you like to track your shipment?"),
            Present(WhatsAppInteractive.Header.Text("Your order is confirmed")),
            Present("Lucky Shrub")
        )
        val msg  = WhatsAppMessage.OfInteractive(buttons)
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"type\":\"button\""))
        assert(json.contains("{\"type\":\"reply\",\"reply\":{\"id\":\"track_shipment\",\"title\":\"Track shipment\"}}"))
        assert(json.contains("{\"type\":\"reply\",\"reply\":{\"id\":\"contact_support\",\"title\":\"Contact support\"}}"))
    }

    "cta_url encodes action.name=cta_url + parameters with display_text and url" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val cta = WhatsAppInteractive.CtaUrl(
            "See Docs",
            "https://developers.facebook.com/docs/whatsapp",
            Present("See our developer documentation to learn how to build with WhatsApp."),
            Present(WhatsAppInteractive.Header.Text("Read our docs")),
            Present("Meta for Developers")
        )
        val msg  = WhatsAppMessage.OfInteractive(cta)
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"name\":\"cta_url\""))
        assert(json.contains("\"display_text\":\"See Docs\""))
        assert(json.contains("\"url\":\"https://developers.facebook.com/docs/whatsapp\""))
    }

    "product encodes action.catalog_id+product_retailer_id with no header" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val product =
            WhatsAppInteractive.Product("CATALOG_ID", "ID_TEST_ITEM_1", Present("optional body text"), Present("optional footer text"))
        val msg  = WhatsAppMessage.OfInteractive(product)
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"type\":\"product\""))
        assert(json.contains("\"catalog_id\":\"CATALOG_ID\""))
        assert(json.contains("\"product_retailer_id\":\"ID_TEST_ITEM_1\""))
        assert(!json.contains("\"header\""))
    }

    "product_list encodes required text header and sections with product_items" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val pl = WhatsAppInteractive.ProductList(
            "CATALOG_ID",
            "Our top picks for you",
            "Check out these items",
            Chunk(
                WhatsAppInteractive.ProductSection("Succulents", Chunk("SKU_1001", "SKU_1002")),
                WhatsAppInteractive.ProductSection("Planters", Chunk("SKU_2001"))
            ),
            Present("Sale ends Sunday")
        )
        val msg  = WhatsAppMessage.OfInteractive(pl)
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"type\":\"product_list\""))
        assert(json.contains("\"type\":\"text\",\"text\":\"Our top picks for you\""))
        assert(json.contains("\"product_retailer_id\":\"SKU_1001\""))
        assert(json.contains("\"product_retailer_id\":\"SKU_1002\""))
        assert(json.contains("\"product_retailer_id\":\"SKU_2001\""))
    }

    "flow encodes flow_id branch with flow_message_version 3" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val flow = WhatsAppInteractive.Flow(
            "RANDOM_FLOW_TOKEN",
            WhatsAppInteractive.Flow.Ref.ById("YOUR_FLOW_ID"),
            "Start survey",
            WhatsAppInteractive.Flow.Action.Navigate("SURVEY_START", Present("{}")),
            WhatsAppInteractive.Flow.Mode.Published,
            Present("Please fill the form"),
            Present(WhatsAppInteractive.Header.Text("Feedback")),
            Present("Thank you!")
        )
        val msg  = WhatsAppMessage.OfInteractive(flow)
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"flow_message_version\":\"3\""))
        assert(json.contains("\"flow_token\":\"RANDOM_FLOW_TOKEN\""))
        assert(json.contains("\"flow_id\":\"YOUR_FLOW_ID\""))
        assert(!json.contains("\"flow_name\""))
        assert(json.contains("\"flow_cta\":\"Start survey\""))
        assert(json.contains("\"flow_action\":\"navigate\""))
        assert(json.contains("\"screen\":\"SURVEY_START\""))
        assert(json.contains("\"mode\":\"published\""))
    }

    "flow encodes flow_name branch with data_exchange and no payload" in {
        val to = WhatsAppId.WaId("PHONE_NUMBER")
        val flow = WhatsAppInteractive.Flow(
            "tok2",
            WhatsAppInteractive.Flow.Ref.ByName("feedback_survey"),
            "Start",
            WhatsAppInteractive.Flow.Action.DataExchange,
            WhatsAppInteractive.Flow.Mode.Draft
        )
        val msg  = WhatsAppMessage.OfInteractive(flow)
        val json = new String(kyo.internal.whatsapp.Codec.encodeSend(to, msg, Absent).toArray, "UTF-8")
        assert(json.contains("\"flow_name\":\"feedback_survey\""))
        assert(!json.contains("\"flow_id\""))
        assert(json.contains("\"flow_action\":\"data_exchange\""))
        assert(!json.contains("\"flow_action_payload\""))
        assert(json.contains("\"mode\":\"draft\""))
    }

end WhatsAppInteractiveTest

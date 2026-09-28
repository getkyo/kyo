package kyo

import kyo.WhatsAppId.*
import kyo.WhatsAppInteractive.*

class WhatsAppInteractiveTest extends BaseWhatsAppTest:

    "list encodes to the expected list JSON shape" in {
        val to       = WhatsAppId.WaId("PHONE_NUMBER")
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
        val to      = WhatsAppId.WaId("PHONE_NUMBER")
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
        val to  = WhatsAppId.WaId("PHONE_NUMBER")
        val cta = WhatsAppInteractive.CtaUrl(
            "See Docs",
            url("https://developers.facebook.com/docs/whatsapp"),
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
        val to      = WhatsAppId.WaId("PHONE_NUMBER")
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
        val to   = WhatsAppId.WaId("PHONE_NUMBER")
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
        val to   = WhatsAppId.WaId("PHONE_NUMBER")
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

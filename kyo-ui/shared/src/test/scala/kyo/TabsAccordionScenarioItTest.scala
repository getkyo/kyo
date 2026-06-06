package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class TabsAccordionScenarioItTest extends UITest:

    "click tab switches content" in {
        val app: UI < Async =
            for activeTab <- Signal.initRef("general")
            yield UI.div(
                UI.button("General").id("tab-general").onClick(activeTab.set("general")),
                UI.button("Profile").id("tab-profile").onClick(activeTab.set("profile")),
                activeTab.map {
                    case "general" => UI.span("General content").id("content")
                    case "profile" => UI.span("Profile content").id("content")
                    case _         => UI.span("?")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("content"), "General content")
                _ <- Browser.click(Selector.id("tab-profile"))
                _ <- Browser.assertText(Selector.id("content"), "Profile content")
                _ <- Browser.click(Selector.id("tab-general"))
                _ <- Browser.assertText(Selector.id("content"), "General content")
            yield ()
        }
    }

    "fill field in tab switch back values preserved" in {
        val app: UI < Async =
            for
                activeTab <- Signal.initRef("a")
                valA      <- Signal.initRef("")
                valB      <- Signal.initRef("")
            yield UI.div(
                UI.button("Tab A").id("ta").onClick(activeTab.set("a")),
                UI.button("Tab B").id("tb").onClick(activeTab.set("b")),
                activeTab.map {
                    case "a" => UI.input.id("ia").value(valA)
                    case _   => UI.input.id("ib").value(valB)
                },
                valA.map(v => UI.span(s"a:$v").id("va")),
                valB.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("ia"), "hello")
                _ <- Browser.assertText(Selector.id("va"), "a:hello")
                _ <- Browser.click(Selector.id("tb"))
                _ <- Browser.fill(Selector.id("ib"), "world")
                _ <- Browser.assertText(Selector.id("vb"), "b:world")
                _ <- Browser.click(Selector.id("ta"))
                _ <- Browser.assertText(Selector.id("va"), "a:hello")
            yield ()
        }
    }

    "submit from any tab captures all" in {
        val app: UI < Async =
            for
                activeTab <- Signal.initRef("a")
                valA      <- Signal.initRef("")
                valB      <- Signal.initRef("")
                result    <- Signal.initRef("")
            yield UI.div(
                UI.button("Tab A").id("ta").onClick(activeTab.set("a")),
                UI.button("Tab B").id("tb").onClick(activeTab.set("b")),
                activeTab.map {
                    case "a" => UI.input.id("ia").value(valA)
                    case _   => UI.input.id("ib").value(valB)
                },
                UI.button("Submit").id("sub").onClick {
                    for
                        a <- valA.get
                        b <- valB.get
                        _ <- result.set(s"$a|$b")
                    yield ()
                },
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("ia"), "aaa")
                _ <- Browser.click(Selector.id("tb"))
                _ <- Browser.fill(Selector.id("ib"), "bbb")
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "result:aaa|bbb")
            yield ()
        }
    }

    "4 tabs each with different content" in {
        val app: UI < Async =
            for tab <- Signal.initRef(1)
            yield UI.div(
                UI.button("1").id("t1").onClick(tab.set(1)),
                UI.button("2").id("t2").onClick(tab.set(2)),
                UI.button("3").id("t3").onClick(tab.set(3)),
                UI.button("4").id("t4").onClick(tab.set(4)),
                tab.map(t => UI.span(s"Tab $t content").id("content"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("content"), "Tab 1 content")
                _ <- Browser.click(Selector.id("t3"))
                _ <- Browser.assertText(Selector.id("content"), "Tab 3 content")
                _ <- Browser.click(Selector.id("t4"))
                _ <- Browser.assertText(Selector.id("content"), "Tab 4 content")
                _ <- Browser.click(Selector.id("t1"))
                _ <- Browser.assertText(Selector.id("content"), "Tab 1 content")
            yield ()
        }
    }

    "Shift Tab from content back to header" in {
        val app: UI < Async =
            for active <- Signal.initRef("a")
            yield UI.div(
                UI.button("Header").id("h-a").tabIndex(1).focusGroup("tabs"),
                UI.input.id("content-input").tabIndex(2)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("content-input"))
                _ <- Browser.assertFocused(Selector.id("content-input"))
                _ <- Browser.press(Selector.id("content-input"), Key.Tab, KeyModifiers(shift = true))
                _ <- Browser.assertFocused(Selector.id("h-a"))
            yield ()
        }
    }

    "ArrowRight Left between tab headers" in {
        val app: UI < Async =
            for active <- Signal.initRef("a")
            yield UI.div(
                UI.button("A").id("h-a").focusGroup("tabs").onClick(active.set("a")),
                UI.button("B").id("h-b").focusGroup("tabs").onClick(active.set("b")),
                active.map(v => UI.span(v).id("content"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("h-a"))
                _ <- Browser.assertFocused(Selector.id("h-a"))
                _ <- Browser.press(Selector.id("h-a"), Key.ArrowRight)
                _ <- Browser.assertFocused(Selector.id("h-b"))
                _ <- Browser.press(Selector.id("h-b"), Key.ArrowLeft)
                _ <- Browser.assertFocused(Selector.id("h-a"))
            yield ()
        }
    }

    "ArrowRight wraps from last to first tab" in {
        val app: UI < Async =
            for active <- Signal.initRef("a")
            yield UI.div(
                UI.button("A").id("h-a").focusGroup("tabs").onClick(active.set("a")),
                UI.button("B").id("h-b").focusGroup("tabs").onClick(active.set("b")),
                UI.button("C").id("h-c").focusGroup("tabs").onClick(active.set("c")),
                active.map(v => UI.span(v).id("content"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("h-c"))
                _ <- Browser.assertFocused(Selector.id("h-c"))
                _ <- Browser.press(Selector.id("h-c"), Key.ArrowRight)
                _ <- Browser.assertFocused(Selector.id("h-a"))
            yield ()
        }
    }

    "Enter on tab header activates content" in {
        // HTML <button> elements natively fire click on Enter when focused.
        // No HtmlRenderer shim needed; native behaviour drives the onClick handler.
        val app: UI < Async =
            for activeTab <- Signal.initRef("general")
            yield UI.div(
                UI.button("General").id("tab-general").onClick(activeTab.set("general")),
                UI.button("Profile").id("tab-profile").onClick(activeTab.set("profile")),
                activeTab.map {
                    case "general" => UI.span("General content").id("content")
                    case _         => UI.span("Profile content").id("content")
                }
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("tab-profile"))
                _ <- Browser.assertText(Selector.id("content"), "Profile content")
                _ <- Browser.press(Selector.id("tab-general"), Key.Enter)
                _ <- Browser.assertText(Selector.id("content"), "General content")
            yield ()
        }
    }

end TabsAccordionScenarioItTest

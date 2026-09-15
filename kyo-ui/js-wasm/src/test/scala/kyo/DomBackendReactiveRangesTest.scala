package kyo

import kyo.UI.foreach
import kyo.UI.foreachKeyed
import kyo.internal.DomBackend
import org.scalajs.dom
import scala.scalajs.js as scalajs

class DomBackendReactiveRangesTest extends kyo.test.Test[Any]:

    DomTestEnv.install

    override def config = super.config.sequential

    private def click(id: String)(using Frame): Unit < Sync =
        Sync.defer {
            val event = scalajs.Dynamic.newInstance(dom.window.asInstanceOf[scalajs.Dynamic].MouseEvent)(
                "click",
                scalajs.Dynamic.literal(bubbles = true)
            )
            discard(dom.document.getElementById(id).asInstanceOf[scalajs.Dynamic].dispatchEvent(event))
        }

    "local mount preserves direct table row topology through events and repeated updates" in {
        for
            rows <- Signal.initRef(Chunk("A"))
            ui = UI.div(
                UI.table(
                    UI.tr(UI.td("before").id("before")),
                    rows.foreach(value => UI.tr(UI.td(value).id(s"row-$value"))),
                    UI.tr(UI.td("after").id("after"))
                ).id("table"),
                UI.button("add").id("add").onClick(rows.getAndUpdate(_ :+ "B").unit),
                UI.button("clear").id("clear").onClick(rows.set(Chunk.empty))
            )
            ready = new DomTestEnv.MountReady
            fiber   <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _       <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("row-A") != null))
            initial <- Sync.defer(topology)
            _       <- click("add")
            _       <- assertEventually(Sync.defer(dom.document.getElementById("row-B") != null))
            updated <- Sync.defer(topology)
            _       <- click("clear")
            _       <- assertEventually(Sync.defer(dom.document.getElementById("row-A") == null))
            cleared <- Sync.defer(topology)
            _       <- fiber.interrupt
            _       <- fiber.getResult
        yield
            assert(initial.rows == 1)
            assert(initial.groups == 3)
            assert(initial.spans == 0)
            assert(initial.anchorParent == "TBODY")
            assert(!initial.staticCaptured)
            assert(updated.rows == 2)
            assert(updated.groups == 3)
            assert(updated.spans == 0)
            assert(updated.anchorParent == "TBODY")
            assert(!updated.staticCaptured)
            assert(cleared.rows == 0)
            assert(cleared.groups == 2)
            assert(cleared.spans == 0)
            assert(cleared.anchorParent == "TABLE")
            assert(!cleared.staticCaptured)
        end for
    }

    "local nested table ranges leave the row host to the content boundary" in {
        for
            outer     <- Signal.initRef(true)
            sectioned <- Signal.initRef(false)
            content = outer.map(_ =>
                (sectioned.map { authored =>
                    if authored then UI.tbody(UI.tr(UI.td("authored"))).id("local-authored").data("state", "kept"): UI
                    else UI.tr(UI.td("row").id("local-row")): UI
                }: UI)
            )
            ui = UI.div(
                UI.table(content).id("local-nested-table"),
                UI.button("section").id("local-section").onClick(sectioned.set(true)),
                UI.button("row").id("local-return-row").onClick(sectioned.set(false))
            )
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("local-row") != null))
            _     <- click("local-section")
            _     <- assertEventually(Sync.defer(dom.document.getElementById("local-authored") != null))
            authored <- Sync.defer {
                val section = dom.document.getElementById("local-authored")
                (section.parentNode.asInstanceOf[dom.Element].id, section.getAttribute("data-state"))
            }
            _ <- click("local-return-row")
            _ <- assertEventually(Sync.defer(dom.document.getElementById("local-row") != null))
            rowParent <- Sync.defer(
                dom.document.getElementById("local-row").closest("tbody").parentNode.asInstanceOf[dom.Element].id
            )
            _ <- fiber.interrupt
            _ <- fiber.getResult
        yield
            assert(authored == ("local-nested-table", "kept"))
            assert(rowParent == "local-nested-table")
    }

    "local mixed authored sections and transparent rows retain table topology across updates" in {
        for
            outer <- Signal.initRef(0)
            rows  <- Signal.initRef(Chunk("A"))
            ui = UI.div(
                UI.table(
                    outer.map(_ =>
                        UI.fragment(
                            UI.tbody(UI.tr(UI.td("before"))).id("local-mixed-before"),
                            rows.foreach(value => UI.tr(UI.td(value).id(s"local-mixed-$value"))),
                            UI.tbody(UI.tr(UI.td("after"))).id("local-mixed-after")
                        )
                    )
                ).id("local-mixed-table")
            )
            ready = new DomTestEnv.MountReady
            fiber    <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _        <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("local-mixed-A") != null))
            initial  <- Sync.defer(mixedTableTopology("local-mixed-table"))
            _        <- rows.set(Chunk("A", "B"))
            _        <- assertEventually(Sync.defer(dom.document.getElementById("local-mixed-B") != null))
            inner    <- Sync.defer(mixedTableTopology("local-mixed-table"))
            _        <- outer.getAndUpdate(_ + 1)
            _        <- assertEventually(Sync.defer(dom.document.getElementById("local-mixed-B") != null))
            repeated <- Sync.defer(mixedTableTopology("local-mixed-table"))
            _        <- fiber.interrupt
            _        <- fiber.getResult
        yield
            assert(initial == (3, "local-mixed-before", 1, "local-mixed-after", 0))
            assert(inner == (3, "local-mixed-before", 2, "local-mixed-after", 0))
            assert(repeated == inner)
    }

    "local mount preserves every exposed restricted parent keyed identity and nested JS properties" in {
        for
            rows       <- Signal.initRef(Chunk("A"))
            nestedProp <- Signal.initRef(false)
            ui = UI.div(
                UI.table(UI.tbody(rows.foreach(value => UI.tr(UI.td(value).id(s"local-grouped-$value"))))).id("local-grouped"),
                UI.table(UI.tbody(UI.tr(rows.foreach(value => UI.td(value).id(s"local-td-$value"))))).id("local-td-table"),
                UI.table(UI.tbody(UI.tr(rows.foreach(value => UI.th(value).id(s"local-th-$value"))))).id("local-th-table"),
                UI.ul(rows.foreach(value => UI.li(value).id(s"local-ul-$value"))).id("local-ul"),
                UI.ol(rows.foreach(value => UI.li(value).id(s"local-ol-$value"))).id("local-ol"),
                UI.select(rows.foreach(value => UI.option(value).value(value).id(s"local-option-$value"))).id("local-select"),
                UI.div(rows.foreachKeyed(identity)(value => UI.button(value).id(s"local-keyed-$value"))).id("local-keyed"),
                nestedProp.map(value => UI.div(UI.checkbox.id("local-nested-property").indeterminate(value)))
            )
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("local-option-A") != null))
            _     <- rows.set(Chunk("A", "B"))
            _     <- nestedProp.set(true)
            // Each region above subscribes to the signal on its own, so one region having applied the update
            // says nothing about the other seven. Waiting on a single representative and then reading the
            // whole topology races the rest, which is why a loaded runner sees a count of 1 where 2 is
            // expected. Settle on the entire tuple instead. This keeps the assertion's full strength: a
            // region that never updates still fails, by exhausting the retry budget rather than by racing.
            readTopology = Sync.defer {
                def count(selector: String) = dom.document.querySelectorAll(selector).length
                val property                = dom.document.getElementById("local-nested-property")
                (
                    count("#local-grouped > tbody > tr"),
                    count("#local-td-table > tbody > tr > td"),
                    count("#local-th-table > tbody > tr > th"),
                    count("#local-ul > li"),
                    count("#local-ol > li"),
                    count("#local-select > option"),
                    count("#local-keyed > button"),
                    property.asInstanceOf[scalajs.Dynamic].indeterminate.asInstanceOf[Boolean],
                    property.getAttribute("data-kyo-prop-indeterminate"),
                    count("span[data-kyo-reactive]")
                )
            }
            expected: (Int, Int, Int, Int, Int, Int, Int, Boolean, String, Int) = (2, 2, 2, 2, 2, 2, 2, true, null, 0)
            _        <- assertEventually(readTopology.map(_ == expected))
            topology <- readTopology
            _        <- fiber.interrupt
            _        <- fiber.getResult
        yield assert(topology == expected)
    }

    "local mount updates directly nested logical ranges" in {
        for
            outer  <- Signal.initRef(true)
            middle <- Signal.initRef(true)
            value  <- Signal.initRef("one")
            ui = UI.div(
                outer.map(_ => (middle.map(_ => (value.map(v => UI.span(v).id("value")): UI)): UI)),
                UI.button("update").id("update").onClick(value.set("two"))
            )
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _ <- assertEventually(Sync.defer {
                val element = dom.document.getElementById("value")
                ready.installed && element != null && element.textContent == "one"
            })
            _ <- click("update")
            _ <- assertEventually(Sync.defer {
                val element = dom.document.getElementById("value")
                element != null && element.textContent == "two"
            })
            markerIds <- Sync.defer {
                val walker = dom.document.createTreeWalker(dom.document.body, 128, null, false)
                Iterator.continually(walker.nextNode()).takeWhile(_ != null).map(_.nodeValue).filter(_.startsWith("kyo-rs:")).toSeq
            }
            _ <- fiber.interrupt
            _ <- fiber.getResult
        yield
            assert(markerIds.size == 3)
            assert(markerIds.distinct.size == 3)
    }

    "direct signal-bound element inside another reactive updates independently" in {
        for
            outer <- Signal.initRef(true)
            value <- Signal.initRef("initial")
            ui = UI.div(
                outer.map(_ => (UI.input.id("field").value(value): UI)),
                UI.button("external").id("external").onClick(value.set("updated"))
            )
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("field") != null))
            _     <- click("external")
            _ <- assertEventually(Sync.defer {
                val field = dom.document.getElementById("field")
                field != null && field.getAttribute("value") == "updated"
            })
            _ <- fiber.interrupt
            _ <- fiber.getResult
        yield ()
    }

    "local own-bound text email and number inputs morph in place while focused" in {
        for
            text   <- Signal.initRef("one")
            email  <- Signal.initRef("one@example.com")
            number <- Signal.initRef("1")
            ui = UI.div(
                UI.input.id("morph-text").value(text),
                UI.emailInput.id("morph-email").value(email),
                UI.numberInput.id("morph-number").value(number)
            )
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("morph-number") != null))
            original <- Sync.defer(
                (
                    dom.document.getElementById("morph-text"),
                    dom.document.getElementById("morph-email"),
                    dom.document.getElementById("morph-number")
                )
            )
            _ <- Sync.defer {
                val field = original._1.asInstanceOf[scalajs.Dynamic]
                discard(field.focus())
                discard(field.setSelectionRange(1, 1))
            }
            _ <- text.set("two")
            _ <- assertEventually(Sync.defer(dom.document.getElementById("morph-text").getAttribute("value") == "two"))
            textState <- Sync.defer(
                (
                    dom.document.getElementById("morph-text") eq original._1,
                    dom.document.activeElement eq original._1,
                    original._1.asInstanceOf[scalajs.Dynamic].selectionStart.asInstanceOf[Int]
                )
            )
            _ <- Sync.defer(discard(original._2.asInstanceOf[scalajs.Dynamic].focus()))
            _ <- email.set("two@example.com")
            _ <- assertEventually(Sync.defer(dom.document.getElementById("morph-email").getAttribute("value") == "two@example.com"))
            emailState <- Sync.defer(
                (dom.document.getElementById("morph-email") eq original._2, dom.document.activeElement eq original._2)
            )
            _ <- Sync.defer(discard(original._3.asInstanceOf[scalajs.Dynamic].focus()))
            _ <- number.set("2")
            _ <- assertEventually(Sync.defer(dom.document.getElementById("morph-number").getAttribute("value") == "2"))
            numberState <- Sync.defer(
                (dom.document.getElementById("morph-number") eq original._3, dom.document.activeElement eq original._3)
            )
            _ <- fiber.interrupt
            _ <- fiber.getResult
        yield
            assert(textState == (true, true, 1))
            assert(emailState == (true, true))
            assert(numberState == (true, true))
    }

    "bound Dropdown updates repeatedly inside its initial logical range" in {
        for
            selected <- Signal.initRef("a")
            ui    = UI.div(UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("local-dropdown").value(selected))
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("local-dropdown-trigger") != null))
            _     <- selected.set("b")
            _ <- assertEventually(Sync.defer {
                val trigger = dom.document.getElementById("local-dropdown-trigger")
                trigger != null && trigger.textContent == "Beta ▾"
            })
            _ <- selected.set("a")
            _ <- assertEventually(Sync.defer {
                val trigger = dom.document.getElementById("local-dropdown-trigger")
                trigger != null && trigger.textContent == "Alpha ▾"
            })
            _ <- fiber.interrupt
            _ <- fiber.getResult
        yield ()
    }

    "local SVG replacement retains SVG boundaries for nested empty regions" in {
        for
            outer <- Signal.initRef(true)
            show  <- Signal.initRef(false)
            innerSignal = show.map(value => if value then Svg.circle.id("circle"): UI else UI.fragment(): UI)
            inner       = UI.Ast.Reactive[Svg.Circle](innerSignal)
            outerSignal = outer.map(_ => UI.Ast.Fragment[UI](Chunk(inner)): UI)
            outerNode   = UI.Ast.Reactive[Svg.G](outerSignal)
            svg         = Svg.Root(children = Chunk(outerNode))
            ui          = UI.div(svg, UI.button("show").id("show").onClick(show.set(true)))
            ready       = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.querySelector("svg g[data-kyo-reactive]") != null))
            _     <- click("show")
            _     <- assertEventually(Sync.defer(dom.document.getElementById("circle") != null))
            comments <- Sync.defer {
                val walker = dom.document.createTreeWalker(dom.document.querySelector("svg"), 128, null, false)
                Iterator.continually(walker.nextNode()).takeWhile(_ != null).map(_.nodeValue).filter(_.startsWith("kyo-rs:")).toSeq
            }
            _ <- fiber.interrupt
            _ <- fiber.getResult
        yield assert(comments.isEmpty)
    }

    "range replacement restores focus and caret for raw HTML without a path" in {
        for
            value <- Signal.initRef("one")
            ui    = UI.div(value.map(v => UI.rawHtml(s"<input id='raw' value='$v'>")))
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("raw") != null))
            _ <- Sync.defer {
                val raw = dom.document.getElementById("raw").asInstanceOf[scalajs.Dynamic]
                discard(raw.focus())
                discard(raw.setSelectionRange(1, 1))
            }
            _ <- value.set("two")
            _ <- assertEventually(Sync.defer {
                val raw = dom.document.getElementById("raw")
                raw != null && raw.getAttribute("value") == "two"
            })
            focused <- Sync.defer(dom.document.activeElement.id)
            caret   <- Sync.defer(dom.document.activeElement.asInstanceOf[scalajs.Dynamic].selectionStart.asInstanceOf[Int])
            _       <- fiber.interrupt
            _       <- fiber.getResult
        yield
            assert(focused == "raw")
            assert(caret == 1)
    }

    "table range replacement restores raw focus relative to the reactive rows" in {
        for
            value <- Signal.initRef("one")
            ui = UI.table(
                value.map(v => UI.tr(UI.td(UI.rawHtml(s"<input id='raw-table' value='$v'>"))))
            )
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("raw-table") != null))
            _ <- Sync.defer {
                val raw = dom.document.getElementById("raw-table").asInstanceOf[scalajs.Dynamic]
                discard(raw.focus())
                discard(raw.setSelectionRange(1, 1))
            }
            _ <- value.set("two")
            _ <- assertEventually(Sync.defer {
                val raw = dom.document.getElementById("raw-table")
                raw != null && raw.getAttribute("value") == "two"
            })
            focused <- Sync.defer(dom.document.activeElement.id)
            caret   <- Sync.defer(dom.document.activeElement.asInstanceOf[scalajs.Dynamic].selectionStart.asInstanceOf[Int])
            _       <- fiber.interrupt
            _       <- fiber.getResult
        yield
            assert(focused == "raw-table")
            assert(caret == 1)
    }

    "range focus restoration stays within its selector-targeted mount" in {
        for
            left  <- Signal.initRef("left")
            right <- Signal.initRef("right")
            _ <- Sync.defer {
                dom.document.body.innerHTML = "<div id='left-mount'></div><div id='right-mount'></div>"
            }
            leftReady  = new DomTestEnv.MountReady
            rightReady = new DomTestEnv.MountReady
            leftFiber <- Fiber.initUnscoped(
                Scope.run(DomBackend.mount(left.map(v => UI.input.id("left-field").value(v)), "#left-mount", leftReady))
            )
            rightFiber <- Fiber.initUnscoped(
                Scope.run(DomBackend.mount(right.map(v => UI.input.id("right-field").value(v)), "#right-mount", rightReady))
            )
            _ <- assertEventually(Sync.defer(
                leftReady.installed && rightReady.installed && dom.document.getElementById("right-field") != null
            ))
            _       <- Sync.defer(discard(dom.document.getElementById("right-field").asInstanceOf[scalajs.Dynamic].focus()))
            _       <- right.set("updated")
            _       <- assertEventually(Sync.defer(dom.document.getElementById("right-field").getAttribute("value") == "updated"))
            focused <- Sync.defer(dom.document.activeElement.id)
            _       <- rightFiber.interrupt
            _       <- rightFiber.getResult
            _       <- leftFiber.interrupt
            _       <- leftFiber.getResult
        yield assert(focused == "right-field")
    }

    "range replacement applies JS properties on a top-level element" in {
        for
            state <- Signal.initRef(false)
            ui = UI.div(
                UI.checkbox.id("property-checkbox").indeterminate(state),
                UI.button("indeterminate").id("set-property").onClick(state.set(true))
            )
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("property-checkbox") != null))
            _     <- click("set-property")
            _ <- assertEventually(Sync.defer {
                val checkbox = dom.document.getElementById("property-checkbox")
                checkbox != null && checkbox.asInstanceOf[scalajs.Dynamic].indeterminate.asInstanceOf[Boolean]
            })
            sourceAttribute <- Sync.defer(dom.document.getElementById("property-checkbox").getAttribute("data-kyo-prop-indeterminate"))
            _               <- fiber.interrupt
            _               <- fiber.getResult
        yield assert(sourceAttribute == null)
    }

    "local synthetic host transition treats an authored tbody as the semantic lifecycle root" in {
        for
            section <- Signal.initRef(false)
            content = section.map { authored =>
                if authored then
                    UI.tbody(UI.tr(UI.td("section")))
                        .id("local-semantic-host")
                        .tabIndex(-1)
                        .focusAuto(true)
                        .enterTransition("local-semantic-enter")
                        .leaveTransition("local-semantic-leave")
                        .jsProp("rangeprobe", "applied"): UI
                else UI.tr(UI.td("row").id("local-semantic-row")): UI
            }
            ui = UI.div(
                UI.table(content).id("local-semantic-table"),
                UI.button("section").id("local-show-section").onClick(section.set(true)),
                UI.button("row").id("local-show-row").onClick(section.set(false))
            )
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui, ready)))
            _     <- assertEventually(Sync.defer(ready.installed && dom.document.getElementById("local-semantic-row") != null))
            _     <- click("local-show-section")
            _     <- assertEventually(Sync.defer(dom.document.getElementById("local-semantic-host") != null))
            authoredState <- Sync.defer {
                val authored = dom.document.getElementById("local-semantic-host")
                (
                    authored.asInstanceOf[scalajs.Dynamic].rangeprobe.asInstanceOf[String],
                    authored.getAttribute("data-kyo-prop-rangeprobe"),
                    dom.document.activeElement.id,
                    authored.hasAttribute("data-kyo-range-host")
                )
            }
            _          <- click("local-show-row")
            _          <- assertEventually(Sync.defer(dom.document.getElementById("local-semantic-row") != null))
            leaveGhost <- Sync.defer(dom.document.querySelector("[data-kyo-ghost]") != null)
            _          <- fiber.interrupt
            _          <- fiber.getResult
        yield
            assert(authoredState == ("applied", null, "local-semantic-host", false))
            assert(leaveGhost)
    }

    private def topology: (rows: Int, groups: Int, spans: Int, anchorParent: String, staticCaptured: Boolean) =
        val table  = dom.document.getElementById("table")
        val groups = table.querySelectorAll(":scope > tbody")
        val walker = dom.document.createTreeWalker(table, 128, null, false)
        val anchor = Iterator
            .continually(walker.nextNode())
            .takeWhile(_ != null)
            .find(_.nodeValue.startsWith("kyo-rs:"))
            .get
        val parent         = anchor.parentNode.asInstanceOf[dom.Element]
        var current        = anchor.nextSibling
        var staticCaptured = false
        while current != null && !(current.nodeType == 8 && current.nodeValue.startsWith("kyo-re:")) do
            if current.nodeType == 1 then
                val element = current.asInstanceOf[dom.Element]
                staticCaptured ||= element.id == "before" || element.id == "after" || element.querySelector("#before,#after") != null
            current = current.nextSibling
        end while
        (
            rows = table.querySelectorAll("[id^='row-']").length,
            groups = groups.length,
            spans = table.querySelectorAll("span[data-kyo-reactive]").length,
            anchorParent = parent.tagName,
            staticCaptured = staticCaptured
        )
    end topology

    private def mixedTableTopology(id: String): (Int, String, Int, String, Int) =
        val table  = dom.document.getElementById(id)
        val bodies = table.querySelectorAll(":scope > tbody")
        (
            bodies.length,
            bodies(0).asInstanceOf[dom.Element].id,
            bodies(1).asInstanceOf[dom.Element].children.length,
            bodies(2).asInstanceOf[dom.Element].id,
            table.querySelectorAll(":scope > tbody > tbody").length
        )
    end mixedTableTopology

end DomBackendReactiveRangesTest

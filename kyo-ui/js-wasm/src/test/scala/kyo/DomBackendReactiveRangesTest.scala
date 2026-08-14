package kyo

import kyo.UI.foreach
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
            fiber   <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui)))
            _       <- assertEventually(Sync.defer(dom.document.getElementById("row-A") != null))
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
            assert(initial == (1, 0, false))
            assert(updated == (2, 0, false))
            assert(cleared == (0, 0, false))
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
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui)))
            _     <- assertEventually(Sync.defer(dom.document.getElementById("local-row") != null))
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

    "local mount updates directly nested logical ranges" in {
        for
            outer  <- Signal.initRef(true)
            middle <- Signal.initRef(true)
            value  <- Signal.initRef("one")
            ui = UI.div(
                outer.map(_ => (middle.map(_ => (value.map(v => UI.span(v).id("value")): UI)): UI)),
                UI.button("update").id("update").onClick(value.set("two"))
            )
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui)))
            _ <- assertEventually(Sync.defer {
                val element = dom.document.getElementById("value")
                element != null && element.textContent == "one"
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
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui)))
            _     <- assertEventually(Sync.defer(dom.document.getElementById("field") != null))
            _     <- click("external")
            _ <- assertEventually(Sync.defer {
                val field = dom.document.getElementById("field")
                field != null && field.getAttribute("value") == "updated"
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
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui)))
            _     <- assertEventually(Sync.defer(dom.document.querySelector("svg g[data-kyo-reactive]") != null))
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
            ui = UI.div(value.map(v => UI.rawHtml(s"<input id='raw' value='$v'>")))
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui)))
            _     <- assertEventually(Sync.defer(dom.document.getElementById("raw") != null))
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
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui)))
            _     <- assertEventually(Sync.defer(dom.document.getElementById("raw-table") != null))
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
            leftFiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(left.map(v => UI.input.id("left-field").value(v)), "#left-mount")))
            rightFiber <- Fiber.initUnscoped(
                Scope.run(DomBackend.mount(right.map(v => UI.input.id("right-field").value(v)), "#right-mount"))
            )
            _       <- assertEventually(Sync.defer(dom.document.getElementById("right-field") != null))
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
            fiber <- Fiber.initUnscoped(Scope.run(DomBackend.mount(ui)))
            _     <- assertEventually(Sync.defer(dom.document.getElementById("property-checkbox") != null))
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

    private def topology: (Int, Int, Boolean) =
        val table  = dom.document.getElementById("table")
        val groups = table.querySelectorAll(":scope > tbody")
        val reactiveGroup = (0 until groups.length).iterator
            .map(groups(_).asInstanceOf[dom.Element])
            .find { group =>
                (0 until group.childNodes.length).exists { i =>
                    val child = group.childNodes(i)
                    child.nodeType == 8 && child.nodeValue.startsWith("kyo-rs:")
                }
            }.get
        (
            reactiveGroup.children.length,
            table.querySelectorAll("span[data-kyo-reactive]").length,
            reactiveGroup.querySelector("#before,#after") != null
        )
    end topology

end DomBackendReactiveRangesTest

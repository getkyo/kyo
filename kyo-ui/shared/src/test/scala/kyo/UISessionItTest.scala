package kyo

import kyo.Browser.*
import kyo.UI.foreach
import kyo.UI.foreachKeyed

class UISessionItTest extends UITest:

    override def timeout = 120.seconds

    // ---- LongRunningSession ----

    "100 signal updates no unbounded growth" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Inc").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 100)(_ => Browser.click(Selector.id("inc")))
                _ <- Browser.assertText(Selector.id("v"), "100")
            yield ()
        }
    }

    "foreach add remove 50 times clean state" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.empty[Int])
            yield UI.div(
                UI.button("Add").id("add").onClick(items.getAndUpdate(is => is.appended(is.size)).unit),
                UI.button("Remove").id("rm").onClick(items.getAndUpdate(is => if is.nonEmpty then is.dropRight(1) else is).unit),
                items.map(is => UI.span(s"size:${is.size}").id("v"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 50)(_ => Browser.click(Selector.id("add")))
                _ <- Browser.assertText(Selector.id("v"), "size:50")
                _ <- Kyo.foreachDiscard(0 until 50)(_ => Browser.click(Selector.id("rm")))
                _ <- Browser.assertText(Selector.id("v"), "size:0")
            yield ()
        }
    }

    "conditional toggle 50 times consistent" in {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.button("Toggle").id("tog").onClick(show.getAndUpdate(!_).unit),
                UI.when(show)(UI.span("visible").id("c")),
                show.map(v => UI.span(s"show:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 50)(_ => Browser.click(Selector.id("tog")))
                _ <- Browser.assertText(Selector.id("v"), "show:true")
            yield ()
        }
    }

    "100 fill operations on same input last wins" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 100)(i => Browser.fill(Selector.id("i"), s"val$i"))
                _ <- Browser.assertText(Selector.id("v"), "sig:val99")
            yield ()
        }
    }

    "100 click operations on same button counter correct" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 100)(_ => Browser.click(Selector.id("b")))
                _ <- Browser.assertText(Selector.id("v"), "100")
            yield ()
        }
    }

    "signal subscription fibers for removed items dont accumulate" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.empty[Int])
                sum   <- Signal.initRef(0)
            yield UI.div(
                UI.button("Add").id("add").onClick(items.getAndUpdate(is => is.appended(is.size)).unit),
                UI.button("Clear").id("clear").onClick(items.set(Chunk.empty).unit),
                UI.button("Sum").id("sum").onClick {
                    items.get.map(is => sum.set(is.size))
                },
                sum.map(v => UI.span(s"sum:$v").id("v")),
                items.foreach(i => UI.span(s"item$i"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 20)(_ => Browser.click(Selector.id("add")))
                _ <- Browser.click(Selector.id("sum"))
                _ <- Browser.assertText(Selector.id("v"), "sum:20")
                _ <- Browser.click(Selector.id("clear"))
                _ <- Browser.click(Selector.id("sum"))
                _ <- Browser.assertText(Selector.id("v"), "sum:0")
                _ <- Kyo.foreachDiscard(0 until 10)(_ => Browser.click(Selector.id("add")))
                _ <- Browser.click(Selector.id("sum"))
                _ <- Browser.assertText(Selector.id("v"), "sum:10")
            yield ()
        }
    }

    // ---- StatePollution ----

    "fill input hide via conditional show again signal preserves" in {
        val app: UI < Async =
            for
                show  <- Signal.initRef(true)
                value <- Signal.initRef("")
            yield UI.div(
                UI.button("Toggle").id("tog").onClick(show.getAndUpdate(!_).unit),
                UI.when(show)(UI.input.id("i").value(value)),
                value.map(v => UI.span(s"val:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "val:hello")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertText(Selector.id("v"), "val:hello")
                _ <- Browser.assertAttribute(Selector.id("i"), "value", "hello")
            yield ()
        }
    }

    "two inputs same ID different conditional branches no leak" in {
        val app: UI < Async =
            for
                mode <- Signal.initRef("a")
                valA <- Signal.initRef("")
                valB <- Signal.initRef("")
            yield UI.div(
                UI.button("Switch").id("sw").onClick(mode.getAndUpdate(m => if m == "a" then "b" else "a").unit),
                mode.map {
                    case "a" => UI.input.id("field").value(valA)
                    case _   => UI.input.id("field").value(valB)
                },
                valA.map(v => UI.span(s"a:$v").id("va")),
                valB.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("field"), "data-a")
                _ <- Browser.assertText(Selector.id("va"), "a:data-a")
                _ <- Browser.click(Selector.id("sw"))
                _ <- Browser.fill(Selector.id("field"), "data-b")
                _ <- Browser.assertText(Selector.id("vb"), "b:data-b")
                _ <- Browser.assertText(Selector.id("va"), "a:data-a")
            yield ()
        }
    }

    "signal shared between two components updates both" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"echo1:$v").id("e1")),
                ref.map(v => UI.span(s"echo2:$v").id("e2"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "shared")
                _ <- Browser.assertText(Selector.id("e1"), "echo1:shared")
                _ <- Browser.assertText(Selector.id("e2"), "echo2:shared")
            yield ()
        }
    }

    "foreach keyed items swap order values follow keys" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("a", "b", "c")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("Reverse").id("rev").onClick(items.getAndUpdate(_.reverse).unit),
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-a"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:a")
                _ <- Browser.click(Selector.id("rev"))
                _ <- Browser.click(Selector.id("btn-c"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:c")
                _ <- Browser.click(Selector.id("btn-a"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:a")
            yield ()
        }
    }

    "foreach focused item reorder focus follows" in {
        // Reversal is triggered by pressing Escape while input is focused.
        // Keyboard events do not move focus, so document.activeElement stays on the input
        // when the SSE Replace fires; this is the scenario our focus-restoration fix targets.
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("a", "b", "c")))
            yield UI.div(
                items.foreachKeyed(identity) { s =>
                    UI.input.id(s"i-$s").placeholder(s)
                        .onKeyDown(ev => if ev.key == UI.Keyboard.Escape then items.getAndUpdate(_.reverse).unit else Kyo.unit)
                },
                items.map(is => UI.span(is.head).id("head"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i-b"))
                _ <- Browser.assertFocused(Selector.id("i-b"))
                _ <- Browser.press(Selector.id("i-b"), Key.Escape)
                // Confirm the SSE Replace happened (list reversed: "c" is now first)
                _ <- Browser.assertText(Selector.id("head"), "c")
                _ <- Browser.assertFocused(Selector.id("i-b"))
            yield ()
        }
    }

    "input buffer isolated per test run" in {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "fresh")
                _ <- Browser.assertText(Selector.id("v"), "sig:fresh")
            yield ()
        }
    }

end UISessionItTest

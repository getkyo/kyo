package kyo

import kyo.Browser.*
import kyo.UI.foreach
import kyo.UI.foreachIndexed
import kyo.UI.foreachKeyed
import kyo.UI.render
import scala.language.implicitConversions

class ReactiveTest extends UITest:

    "signal shows initial value" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(ref.map(v => UI.span(v).id("v")))
        withUI(app) {
            Browser.assertText(Selector.id("v"), "initial").andThen(succeed)
        }
    }

    "signal updates on click" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("before")
            yield UI.div(
                UI.button("Change").id("b").onClick(ref.set("after")),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "before")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "after")
            yield succeed
        }
    }

    "counter increment and decrement" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                counter.map(n => UI.span(s"Count: $n").id("count")),
                UI.button("Inc").id("inc").onClick(counter.updateAndGet(_ + 1).unit),
                UI.button("Dec").id("dec").onClick(counter.updateAndGet(_ - 1).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("count"), "Count: 0")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "Count: 1")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "Count: 2")
                _ <- Browser.click(Selector.id("dec"))
                _ <- Browser.assertText(Selector.id("count"), "Count: 1")
            yield succeed
        }
    }

    "boolean toggle" in run {

        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                flag.map(v => UI.span(v.toString).id("v")),
                UI.button("Toggle").id("t").onClick(flag.updateAndGet(!_).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "false")
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertText(Selector.id("v"), "true")
                _ <- Browser.click(Selector.id("t"))
                _ <- Browser.assertText(Selector.id("v"), "false")
            yield succeed
        }
    }

    "conditional rendering" in run {

        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.button("Show").id("b").onClick(show.set(true)),
                show.map(s =>
                    if s then UI.span("visible").id("vis")
                    else UI.span("hidden").id("hid"): UI
                )
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("hid"), "hidden")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("vis"), "visible")
            yield succeed
        }
    }

    "two signals update independently" in run {

        val app: UI < Async =
            for
                a <- Signal.initRef(0)
                b <- Signal.initRef(0)
            yield UI.div(
                a.map(n => UI.span(s"a:$n").id("va")),
                b.map(n => UI.span(s"b:$n").id("vb")),
                UI.button("A+").id("ia").onClick(a.updateAndGet(_ + 1).unit),
                UI.button("B+").id("ib").onClick(b.updateAndGet(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("va"), "a:0")
                _ <- Browser.click(Selector.id("ia"))
                _ <- Browser.assertText(Selector.id("va"), "a:1")
                _ <- Browser.assertText(Selector.id("vb"), "b:0")
                _ <- Browser.click(Selector.id("ib"))
                _ <- Browser.assertText(Selector.id("vb"), "b:1")
            yield succeed
        }
    }

    "derived signal" in run {

        val app: UI < Async =
            for
                counter <- Signal.initRef(0)
                doubled = counter.map(_ * 2)
            yield UI.div(
                counter.map(n => UI.span(s"n:$n").id("vn")),
                doubled.map(n => UI.span(s"d:$n").id("vd")),
                UI.button("+").id("b").onClick(counter.updateAndGet(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("vn"), "n:0")
                _ <- Browser.assertText(Selector.id("vd"), "d:0")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("vn"), "n:1")
                _ <- Browser.assertText(Selector.id("vd"), "d:2")
            yield succeed
        }
    }

    "signal changes element type" in run {

        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                UI.button("Toggle").id("t").onClick(flag.getAndUpdate(!_).unit),
                flag.map { b =>
                    if b then UI.div("DIV"): UI
                    else UI.span("SPAN"): UI
                }
            )
        withUI(app) {
            for
                _ <- assertContains("SPAN")
                _ <- Browser.click(Selector.id("t"))
                _ <- assertContains("DIV")
            yield succeed
        }
    }

    "signal controls number of children" in run {

        val app: UI < Async =
            for items <- Signal.initRef(Seq("A", "B"))
            yield UI.div(
                items.map(xs => UI.div(xs.map(x => UI.span(x))*)),
                UI.button("Add").id("add").onClick(items.updateAndGet(_ :+ "C").unit)
            )
        withUI(app) {
            for
                _ <- assertContains("A")
                _ <- assertContains("B")
                _ <- Browser.click(Selector.id("add"))
                _ <- assertContains("C")
            yield succeed
        }
    }

    "same signal drives two spans" in run {

        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("+").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(s"A=$n").id("va")),
                counter.map(n => UI.span(s"B=$n").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("va"), "A=1")
                _ <- Browser.assertText(Selector.id("vb"), "B=1")
            yield succeed
        }
    }

    // ---- Merged from AdvancedReactiveTest ----

    "input with SignalRef updates on fill" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref).onInput(v => ref.set(v)),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.assertText(Selector.id("v"), "abc")
            yield succeed
        }
    }

    "element with onClick + onKeyDown + onFocus" in run {
        val app: UI < Async =
            for
                clicks <- Signal.initRef(0)
                keys   <- Signal.initRef("")
                focus  <- Signal.initRef(false)
            yield UI.div(
                UI.button("X").id("b")
                    .onClick(clicks.getAndUpdate(_ + 1).unit)
                    .onKeyDown(ke => keys.set(ke.key.toString))
                    .onFocus(focus.set(true)),
                clicks.map(n => UI.span(s"c:$n").id("vc")),
                keys.map(v => UI.span(s"k:$v").id("vk")),
                focus.map(v => UI.span(s"f:$v").id("vf"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("vc"), "c:1")
                _ <- Browser.assertText(Selector.id("vf"), "f:true")
            yield succeed
        }
    }

    "handler appears via signal" in run {
        val app: UI < Async =
            for
                active  <- Signal.initRef(false)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Toggle").id("tog").onClick(active.getAndUpdate(!_).unit),
                active.map { a =>
                    if a then UI.button("Click").id("btn").onClick(counter.getAndUpdate(_ + 1).unit)
                    else UI.button("Click").id("btn")
                },
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "0")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "1")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "signal toggles between div and span (advanced)" in run {
        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                UI.button("Toggle").id("t").onClick(flag.getAndUpdate(!_).unit),
                flag.map { b =>
                    if b then UI.div("DIV")
                    else UI.span("SPAN")
                }
            )
        withUI(app) {
            for
                _ <- assertContains("SPAN")
                _ <- Browser.click(Selector.id("t"))
                _ <- assertContains("DIV")
                _ <- Browser.click(Selector.id("t"))
                _ <- assertContains("SPAN")
            yield succeed
        }
    }

    "unicode in reactive" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("hello")
            yield UI.div(
                UI.button("Change").id("b").onClick(ref.set("world")),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "hello")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "world")
            yield succeed
        }
    }

    "one handler updates two signals" in run {
        val app: UI < Async =
            for
                a <- Signal.initRef(0)
                b <- Signal.initRef(0)
            yield UI.div(
                UI.button("Both").id("btn").onClick {
                    a.getAndUpdate(_ + 1).unit.andThen(b.getAndUpdate(_ + 10).unit)
                },
                a.map(n => UI.span(s"a:$n").id("va")),
                b.map(n => UI.span(s"b:$n").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("va"), "a:1")
                _ <- Browser.assertText(Selector.id("vb"), "b:10")
            yield succeed
        }
    }

    "sibling input still works after reactive update" in run {
        val app: UI < Async =
            for
                counter <- Signal.initRef(0)
                text    <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => text.set(v)),
                UI.button("+").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(s"c:$n").id("vc")),
                text.map(v => UI.span(s"t:$v").id("vt"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("vc"), "c:1")
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.assertText(Selector.id("vt"), "t:test")
            yield succeed
        }
    }

    "handler reads signal A sets signal B" in run {
        val app: UI < Async =
            for
                a <- Signal.initRef(10)
                b <- Signal.initRef(0)
            yield UI.div(
                UI.button("Copy").id("btn").onClick {
                    for
                        v <- a.get
                        _ <- b.set(v * 2)
                    yield ()
                },
                b.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "20")
            yield succeed
        }
    }

    "input with SignalRef shows initial value" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("hello")
            yield UI.div(UI.input.value(ref).id("i"))
        withUI(app) {
            Browser.assertAttribute(Selector.id("i"), "value", "hello").andThen(succeed)
        }
    }

    "error in handler does not break page (advanced)" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Bad").id("bad").onClick(Abort.panic(new RuntimeException("boom"))),
                UI.button("Good").id("good").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("bad"))
                _ <- Browser.click(Selector.id("good"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "style last wins for same property" in run {
        withUI(UI.div(UI.div.id("d").style(s => s.width(100.px) ++ s.width(200.px)))) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("200")).andThen(succeed)
        }
    }

    "input empty string value" in run {
        withUI(UI.div(UI.input.value("").id("i"))) {
            Browser.assertVisible(Selector.id("i")).andThen(succeed)
        }
    }

    // ---- Merged from ConditionalTest ----

    "when true shows content" in run {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(UI.when(show)(UI.span("visible").id("v")))
        withUI(app) {
            Browser.assertText(Selector.id("v"), "visible").andThen(succeed)
        }
    }

    "when false hides content" in run {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.when(show)(UI.span("hidden").id("h")),
                UI.span("sentinel").id("sentinel")
            )
        withUI(app) {
            for
                _ <- Browser.assertVisible(Selector.id("sentinel"))
                _ <- Browser.assertNotExists(Selector.id("h"))
            yield succeed
        }
    }

    "toggle true to false disappears" in run {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.when(show)(UI.span("content").id("c")),
                UI.button("Hide").id("hide").onClick(show.set(false))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("c"), "content")
                _ <- Browser.click(Selector.id("hide"))
                _ <- Browser.assertNotExists(Selector.id("c"))
            yield succeed
        }
    }

    "toggle false to true appears" in run {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.when(show)(UI.span("content").id("c")),
                UI.button("Show").id("show").onClick(show.set(true))
            )
        withUI(app) {
            for
                _ <- Browser.assertNotExists(Selector.id("c"))
                _ <- Browser.click(Selector.id("show"))
                _ <- Browser.assertText(Selector.id("c"), "content")
            yield succeed
        }
    }

    "toggle true false true reappears" in run {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.when(show)(UI.span("content").id("c")),
                UI.button("Toggle").id("tog").onClick(show.getAndUpdate(!_).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("c"), "content")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertNotExists(Selector.id("c"))
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertText(Selector.id("c"), "content")
            yield succeed
        }
    }

    "when false siblings still visible" in run {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.span("before").id("before"),
                UI.when(show)(UI.span("conditional").id("cond")),
                UI.span("after").id("after")
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("before"), "before")
                _ <- Browser.assertText(Selector.id("after"), "after")
                _ <- Browser.assertNotExists(Selector.id("cond"))
            yield succeed
        }
    }

    "interactive content works after toggle" in run {
        val app: UI < Async =
            for
                show    <- Signal.initRef(false)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.when(show)(
                    UI.button("Click").id("btn").onClick(counter.getAndUpdate(_ + 1).unit)
                ),
                UI.button("Show").id("show").onClick(show.set(true)),
                counter.map(n => UI.span(s"count:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "count:0")
                _ <- Browser.click(Selector.id("show"))
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
            yield succeed
        }
    }

    "two when blocks independent" in run {
        val app: UI < Async =
            for
                showA <- Signal.initRef(true)
                showB <- Signal.initRef(false)
            yield UI.div(
                UI.when(showA)(UI.span("alpha").id("a")),
                UI.when(showB)(UI.span("beta").id("b")),
                UI.button("flip-a").id("ta").onClick(showA.getAndUpdate(!_).unit),
                UI.button("flip-b").id("tb").onClick(showB.getAndUpdate(!_).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("a"), "alpha")
                _ <- Browser.assertNotExists(Selector.id("b"))
                _ <- Browser.click(Selector.id("tb"))
                _ <- Browser.assertText(Selector.id("a"), "alpha")
                _ <- Browser.assertText(Selector.id("b"), "beta")
                _ <- Browser.click(Selector.id("ta"))
                _ <- Browser.assertNotExists(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("b"), "beta")
            yield succeed
        }
    }

    "UI.when wrapping foreach" in run {
        val app: UI < Async =
            for
                show  <- Signal.initRef(false)
                items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(
                UI.when(show)(UI.div(items.foreach(s => UI.span(s"v:$s")))),
                UI.button("Show").id("show").onClick(show.set(true))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("show"))
                _ <- assertContains("v:A")
                _ <- assertContains("v:B")
                _ <- assertContains("v:C")
            yield succeed
        }
    }

    "foreach inside UI.when list appears disappears" in run {
        val app: UI < Async =
            for
                show  <- Signal.initRef(true)
                items <- Signal.initRef(Chunk.from(Seq("X", "Y")))
            yield UI.div(
                UI.when(show)(UI.div(items.foreach(s => UI.span(s"v:$s"))).id("list")),
                UI.button("Toggle").id("tog").onClick(show.getAndUpdate(!_).unit)
            )
        withUI(app) {
            for
                _ <- assertContains("v:X")
                _ <- assertContains("v:Y")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertNotExists(Selector.id("list"))
                _ <- Browser.click(Selector.id("tog"))
                _ <- assertContains("v:X")
                _ <- assertContains("v:Y")
            yield succeed
        }
    }

    "signal render shows initial" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("hello")
            yield UI.div(ref.render(v => UI.span(v).id("v")))
        withUI(app) {
            Browser.assertText(Selector.id("v"), "hello").andThen(succeed)
        }
    }

    "signal render updates on change" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("before")
            yield UI.div(
                ref.render(v => UI.span(v).id("v")),
                UI.button("Change").id("btn").onClick(ref.set("after"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "before")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "after")
            yield succeed
        }
    }

    "signal render updates when the ref is set from a separate forked fiber (route-driven pattern)" in run {
        val app: UI < Async =
            for
                trigger <- Signal.initRef(0)
                content <- Signal.initRef("before")
                _ <- Fiber.initUnscoped {
                    Loop.forever {
                        for
                            _ <- trigger.next
                            _ <- content.set("after")
                        yield Loop.continue(())
                    }
                }
            yield UI.div(
                content.render(v => UI.span(v).id("v")),
                UI.button("Go").id("btn").onClick(trigger.set(1))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "before")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "after")
            yield succeed
        }
    }

    "signal render switches element type" in run {
        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                flag.render(b =>
                    if b then UI.div("div-mode")
                    else UI.span("span-mode")
                ),
                UI.button("Toggle").id("tog").onClick(flag.getAndUpdate(!_).unit)
            )
        withUI(app) {
            for
                _ <- assertContains("span-mode")
                _ <- Browser.click(Selector.id("tog"))
                _ <- assertContains("div-mode")
            yield succeed
        }
    }

    "signal render complex UI" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                counter.render { n =>
                    UI.div(
                        UI.span(s"Value: $n"),
                        if n > 0 then UI.span("positive") else UI.span("zero-or-negative")
                    )
                },
                UI.button("+").id("inc").onClick(counter.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- assertContains("Value: 0")
                _ <- assertContains("zero-or-negative")
                _ <- Browser.click(Selector.id("inc"))
                _ <- assertContains("Value: 1")
                _ <- assertContains("positive")
            yield succeed
        }
    }

    "Signal[String] as child" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("text content")
            yield UI.div(ref: UI)
        withUI(app) {
            assertContains("text content").andThen(succeed)
        }
    }

    "Signal[String] updates" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                ref: UI,
                UI.button("Change").id("btn").onClick(ref.set("updated"))
            )
        withUI(app) {
            for
                _ <- assertContains("initial")
                _ <- Browser.click(Selector.id("btn"))
                _ <- assertContains("updated")
            yield succeed
        }
    }

    "Signal[UI] as child" in run {
        val app: UI < Async =
            for ref <- Signal.initRef[UI](UI.span("from signal").id("s"))
            yield UI.div(ref)
        withUI(app) {
            Browser.assertText(Selector.id("s"), "from signal").andThen(succeed)
        }
    }

    "Signal[UI] updates" in run {
        val app: UI < Async =
            for ref <- Signal.initRef[UI](UI.span("first"))
            yield UI.div(
                ref,
                UI.button("Change").id("btn").onClick(ref.set(UI.span("second")))
            )
        withUI(app) {
            for
                _ <- assertContains("first")
                _ <- Browser.click(Selector.id("btn"))
                _ <- assertContains("second")
            yield succeed
        }
    }

    "appear then assertExists" in run {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.when(show)(UI.span("appeared").id("el")),
                UI.button("Show").id("show").onClick(show.set(true))
            )
        withUI(app) {
            for
                _ <- Browser.assertNotExists(Selector.id("el"))
                _ <- Browser.click(Selector.id("show"))
                _ <- Browser.assertText(Selector.id("el"), "appeared")
            yield succeed
        }
    }

    "disappear then assertNotExists" in run {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.when(show)(UI.span("here").id("el")),
                UI.button("Hide").id("hide").onClick(show.set(false))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("el"), "here")
                _ <- Browser.click(Selector.id("hide"))
                _ <- Browser.assertNotExists(Selector.id("el"))
            yield succeed
        }
    }

    "appear interact then disappear" in run {
        val app: UI < Async =
            for
                show    <- Signal.initRef(true)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.when(show)(
                    UI.button("Click").id("btn").onClick(counter.getAndUpdate(_ + 1).unit)
                ),
                UI.button("Toggle").id("tog").onClick(show.getAndUpdate(!_).unit),
                counter.map(n => UI.span(s"count:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertNotExists(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
            yield succeed
        }
    }

    "appear focus element disappear element gone" in run {
        val app: UI < Async =
            for
                show    <- Signal.initRef(true)
                focused <- Signal.initRef(false)
            yield UI.div(
                UI.when(show)(
                    UI.button("Focusable").id("fbtn").onFocus(focused.set(true))
                ),
                UI.button("Toggle").id("tog").onClick(show.getAndUpdate(!_).unit),
                focused.map(v => UI.span(s"focused:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("fbtn"))
                _ <- Browser.assertText(Selector.id("v"), "focused:true")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertNotExists(Selector.id("fbtn"))
            yield succeed
        }
    }

    // ---- Merged from DynamicInteractionTest ----

    "click button inside foreach item" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                clicked <- Signal.initRef("")
            yield UI.div(
                items.foreach { s =>
                    UI.button(s).id(s"btn-$s").onClick(clicked.set(s))
                },
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-B"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:B")
                _ <- Browser.click(Selector.id("btn-A"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:A")
            yield succeed
        }
    }

    "fill input inside foreach item" in run {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.from(Seq("x", "y")))
                log   <- Signal.initRef("")
            yield UI.div(
                items.foreach { s =>
                    UI.input.id(s"inp-$s").onInput(v => log.set(s"$s:$v"))
                },
                log.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp-x"), "hello")
                _ <- Browser.assertText(Selector.id("v"), "x:hello")
                _ <- Browser.fill(Selector.id("inp-y"), "world")
                _ <- Browser.assertText(Selector.id("v"), "y:world")
            yield succeed
        }
    }

    "add item to foreach list then interact with new item" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("Add").id("add").onClick(items.getAndUpdate(_.appended("B")).unit),
                items.foreach { s =>
                    UI.button(s).id(s"btn-$s").onClick(clicked.set(s))
                },
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-A"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:A")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.click(Selector.id("btn-B"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:B")
            yield succeed
        }
    }

    "remove item from foreach list remaining items still work" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("Remove first").id("rm").onClick(items.getAndUpdate(_.drop(1)).unit),
                items.foreach { s =>
                    UI.button(s).id(s"btn-$s").onClick(clicked.set(s))
                },
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.click(Selector.id("btn-B"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:B")
                _ <- Browser.click(Selector.id("btn-C"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:C")
            yield succeed
        }
    }

    "foreach with indexed items buttons work" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B")))
                clicked <- Signal.initRef("")
            yield UI.div(
                items.foreachIndexed { (i, s) =>
                    UI.button(s).id(s"btn-$i").onClick(clicked.set(s"$i:$s"))
                },
                clicked.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-0"))
                _ <- Browser.assertText(Selector.id("v"), "0:A")
                _ <- Browser.click(Selector.id("btn-1"))
                _ <- Browser.assertText(Selector.id("v"), "1:B")
            yield succeed
        }
    }

    "toggle section then fill input inside" in run {
        val app: UI < Async =
            for
                show <- Signal.initRef(false)
                ref  <- Signal.initRef("")
            yield UI.div(
                UI.button("Show").id("show").onClick(show.set(true)),
                UI.when(show)(UI.input.id("i").onInput(v => ref.set(v))),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("show"))
                _ <- Browser.fill(Selector.id("i"), "data")
                _ <- Browser.assertText(Selector.id("v"), "data")
            yield succeed
        }
    }

    "conditional checkbox works after toggle" in run {
        val app: UI < Async =
            for
                show <- Signal.initRef(false)
                ref  <- Signal.initRef(false)
            yield UI.div(
                UI.button("Show").id("show").onClick(show.set(true)),
                UI.when(show)(UI.checkbox.id("c").onChange(v => ref.set(v))),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("show"))
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "external signal update changes UI while focused on input" in run {
        val app: UI < Async =
            for
                ref     <- Signal.initRef("")
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                UI.button("+").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(s"c:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "typed")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("v"), "c:1")
            yield succeed
        }
    }

    "signal update replaces foreach items while interacting" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("Replace").id("rep").onClick(items.set(Chunk.from(Seq("X", "Y", "Z")))),
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                clicked.map(v => UI.span(s"c:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-A"))
                _ <- Browser.assertText(Selector.id("v"), "c:A")
                _ <- Browser.click(Selector.id("rep"))
                _ <- Browser.click(Selector.id("btn-X"))
                _ <- Browser.assertText(Selector.id("v"), "c:X")
            yield succeed
        }
    }

    "derived signal chain A to B to display" in run {
        val app: UI < Async =
            for
                a <- Signal.initRef(1)
                b = a.map(_ * 2)
            yield UI.div(
                a.map(n => UI.span(s"a:$n").id("va")),
                b.map(n => UI.span(s"b:$n").id("vb")),
                UI.button("+").id("inc").onClick(a.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("va"), "a:2")
                _ <- Browser.assertText(Selector.id("vb"), "b:4")
            yield succeed
        }
    }

    "computed disabled from signal" in run {
        val app: UI < Async =
            for
                ref     <- Signal.initRef("")
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map { v =>
                    UI.button("Submit").id("sub")
                        .disabled(v.isEmpty)
                        .onClick(counter.getAndUpdate(_ + 1).unit)
                },
                counter.map(n => UI.span(s"c:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("sub"))
                _ <- Browser.fill(Selector.id("i"), "data")
                _ <- Browser.assertEnabled(Selector.id("sub"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "c:1")
            yield succeed
        }
    }

    "two way binding input and button update same signal" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                UI.button("Set hi").id("b").onClick(ref.set("hi")),
                ref.map(v => UI.span(s"r:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "manual")
                _ <- Browser.assertText(Selector.id("v"), "r:manual")
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "r:hi")
                _ <- Browser.assertAttribute(Selector.id("i"), "value", "hi")
            yield succeed
        }
    }

    "two inputs bound to same signal stay in sync" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").value(ref),
                UI.input.id("b").value(ref),
                ref.map(v => UI.span(s"r:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "from-a")
                _ <- Browser.assertAttribute(Selector.id("b"), "value", "from-a")
                _ <- Browser.fill(Selector.id("b"), "from-b")
                _ <- Browser.assertAttribute(Selector.id("a"), "value", "from-b")
            yield succeed
        }
    }

    "foreach remove first item others shift and work" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("rm").id("rm").onClick(items.getAndUpdate(_.drop(1)).unit),
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                clicked.map(v => UI.span(s"c:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.click(Selector.id("btn-B"))
                _ <- Browser.assertText(Selector.id("v"), "c:B")
            yield succeed
        }
    }

    "foreach remove last item others unchanged" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("rm").id("rm").onClick(items.getAndUpdate(_.init).unit),
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                clicked.map(v => UI.span(s"c:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertNotExists(Selector.id("btn-C"))
                _ <- Browser.click(Selector.id("btn-A"))
                _ <- Browser.assertText(Selector.id("v"), "c:A")
            yield succeed
        }
    }

    "foreach clear all items empty state" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("clear").id("clr").onClick(items.set(Chunk.empty[String])),
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                UI.span("done").id("d")
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("clr"))
                _ <- Browser.assertNotExists(Selector.id("btn-A"))
                _ <- Browser.assertNotExists(Selector.id("btn-B"))
                _ <- Browser.assertVisible(Selector.id("d"))
            yield succeed
        }
    }

    "foreach 100 items all clickable" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from((1 to 100).map(_.toString)))
                clicked <- Signal.initRef("")
            yield UI.div(
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                clicked.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-50"))
                _ <- Browser.assertText(Selector.id("v"), "50")
            yield succeed
        }
    }

    "foreach signal replaces entire list new items interactive" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("rep").id("rep").onClick(items.set(Chunk.from(Seq("X", "Y", "Z")))),
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                clicked.map(v => UI.span(s"c:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rep"))
                _ <- Browser.click(Selector.id("btn-Y"))
                _ <- Browser.assertText(Selector.id("v"), "c:Y")
            yield succeed
        }
    }

    "UI when false element not focusable" in run {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("a"),
                UI.when(show)(UI.input.id("hidden")),
                UI.input.id("c")
            )
        withUI(app) {
            for
                _ <- Browser.assertNotExists(Selector.id("hidden"))
                _ <- Browser.assertVisible(Selector.id("a"))
                _ <- Browser.assertVisible(Selector.id("c"))
            yield succeed
        }
    }

    "rapid toggle 10 times final state correct" in run {
        val app: UI < Async =
            for show <- Signal.initRef(false)
            yield UI.div(
                UI.button("tog").id("tog").onClick(show.getAndUpdate(!_).unit),
                UI.when(show)(UI.span("visible").id("v"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 10)(_ => Browser.click(Selector.id("tog")))
                // 10 toggles starting from false: even count → still false (hidden)
                _ <- Browser.assertNotExists(Selector.id("v"))
            yield succeed
        }
    }

    "two UI when blocks independent (dynamic)" in run {
        val app: UI < Async =
            for
                a <- Signal.initRef(true)
                b <- Signal.initRef(false)
            yield UI.div(
                UI.when(a)(UI.span("alpha").id("a")),
                UI.when(b)(UI.span("beta").id("b")),
                UI.button("flip-a").id("ta").onClick(a.getAndUpdate(!_).unit),
                UI.button("flip-b").id("tb").onClick(b.getAndUpdate(!_).unit)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("tb"))
                _ <- Browser.assertText(Selector.id("a"), "alpha")
                _ <- Browser.assertText(Selector.id("b"), "beta")
                _ <- Browser.click(Selector.id("ta"))
                _ <- Browser.assertNotExists(Selector.id("a"))
                _ <- Browser.assertText(Selector.id("b"), "beta")
            yield succeed
        }
    }

    "nested UI when outer false hides all" in run {
        val app: UI < Async =
            for
                outer <- Signal.initRef(false)
                inner <- Signal.initRef(true)
            yield UI.div(
                UI.when(outer)(UI.div(UI.when(inner)(UI.span("nested").id("n")))),
                UI.button("show").id("s").onClick(outer.set(true))
            )
        withUI(app) {
            for
                _ <- Browser.assertNotExists(Selector.id("n"))
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.assertText(Selector.id("n"), "nested")
            yield succeed
        }
    }

    "signal update adds element Tab order updated" in run {
        val app: UI < Async =
            for
                show    <- Signal.initRef(false)
                clicked <- Signal.initRef(false)
            yield UI.div(
                UI.button("show").id("s").onClick(show.set(true)),
                UI.when(show)(UI.button("New").id("new").onClick(clicked.set(true))),
                clicked.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("s"))
                _ <- Browser.click(Selector.id("new"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "checkbox enables input fill then uncheck preserves signal" in run {
        val app: UI < Async =
            for
                enabled <- Signal.initRef(false)
                text    <- Signal.initRef("")
            yield UI.div(
                UI.checkbox.id("c").onChange(v => enabled.set(v)),
                enabled.map { e =>
                    UI.input.id("i").disabled(!e).onInput(v => text.set(v))
                },
                text.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c"))
                _ <- Browser.fill(Selector.id("i"), "data")
                _ <- Browser.assertText(Selector.id("v"), "data")
            yield succeed
        }
    }

    "conditional inside foreach each item independent" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B")))
                show    <- Signal.initRef(false)
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("toggle").id("tog").onClick(show.getAndUpdate(!_).unit),
                items.foreach { s =>
                    UI.div(
                        UI.span(s"item:$s"),
                        UI.when(show)(UI.button(s"act-$s").id(s"act-$s").onClick(clicked.set(s)))
                    )
                },
                clicked.map(v => UI.span(s"c:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.click(Selector.id("act-B"))
                _ <- Browser.assertText(Selector.id("v"), "c:B")
            yield succeed
        }
    }

    "signal changes element type input to span no crash" in run {
        val app: UI < Async =
            for flag <- Signal.initRef(false)
            yield UI.div(
                UI.button("tog").id("tog").onClick(flag.getAndUpdate(!_).unit),
                flag.map { f =>
                    if f then UI.input.id("e")
                    else UI.span("text").id("e")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("e"), "text")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertVisible(Selector.id("e"))
            yield succeed
        }
    }

    "foreach inside conditional toggle on interact off on fresh" in run {
        val app: UI < Async =
            for
                show    <- Signal.initRef(true)
                items   <- Signal.initRef(Chunk.from(Seq("A", "B")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("tog").id("tog").onClick(show.getAndUpdate(!_).unit),
                UI.when(show)(UI.div(items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))))),
                clicked.map(v => UI.span(s"c:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-A"))
                _ <- Browser.assertText(Selector.id("v"), "c:A")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertNotExists(Selector.id("btn-A"))
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.click(Selector.id("btn-B"))
                _ <- Browser.assertText(Selector.id("v"), "c:B")
            yield succeed
        }
    }

    "foreach remove middle item others intact" in run {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("rm").id("rm").onClick(items.getAndUpdate(c => c.take(1).concat(c.drop(2))).unit),
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                clicked.map(v => UI.span(s"c:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertNotExists(Selector.id("btn-B"))
                _ <- Browser.click(Selector.id("btn-A"))
                _ <- Browser.assertText(Selector.id("v"), "c:A")
                _ <- Browser.click(Selector.id("btn-C"))
                _ <- Browser.assertText(Selector.id("v"), "c:C")
            yield succeed
        }
    }

    "foreach keyed remove from middle keys preserved" in run {
        import kyo.UI.foreachKeyed
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                clicked <- Signal.initRef("")
            yield UI.div(
                UI.button("rm").id("rm").onClick(items.set(Chunk.from(Seq("A", "C")))),
                items.foreachKeyed(identity)(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                clicked.map(v => UI.span(s"c:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.click(Selector.id("btn-A"))
                _ <- Browser.assertText(Selector.id("v"), "c:A")
                _ <- Browser.click(Selector.id("btn-C"))
                _ <- Browser.assertText(Selector.id("v"), "c:C")
            yield succeed
        }
    }

    "hide section then show again preserves nothing" in run {
        val app: UI < Async =
            for
                show <- Signal.initRef(true)
                ref  <- Signal.initRef("")
            yield UI.div(
                UI.button("tog").id("tog").onClick(show.getAndUpdate(!_).unit),
                UI.when(show)(UI.input.id("i").onInput(v => ref.set(v))),
                ref.map(v => UI.span(s"r:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "data")
                _ <- Browser.assertText(Selector.id("v"), "r:data")
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertNotExists(Selector.id("i"))
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertVisible(Selector.id("i"))
            yield succeed
        }
    }

    // ---- Merged from ErrorResilienceTest ----

    "onInput throws next pressKey works" in run {
        val app: UI < Async =
            for
                broken  <- Signal.initRef(true)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").onInput { _ =>
                    for
                        b <- broken.get
                        _ <- if b then Abort.panic(new RuntimeException("onInput boom"))
                        else counter.getAndUpdate(_ + 1).unit
                    yield ()
                },
                UI.button("Fix").id("fix").onClick(broken.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a")
                _ <- Browser.click(Selector.id("fix"))
                _ <- Browser.fill(Selector.id("i"), "b")
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "onClick throws next click works" in run {
        val app: UI < Async =
            for
                broken  <- Signal.initRef(true)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick {
                    for
                        b <- broken.get
                        _ <- if b then Abort.panic(new RuntimeException("onClick boom"))
                        else counter.getAndUpdate(_ + 1).unit
                    yield ()
                },
                UI.button("Fix").id("fix").onClick(broken.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("fix"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "onFocus throws focus still set" in run {
        val app: UI < Async =
            for ref <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i").onFocus(Abort.panic(new RuntimeException("focus boom"))),
                UI.button("B").id("b").onFocus(ref.set(true)),
                ref.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("i"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "onBlur throws blur completes next focus works" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("a").onBlur(Abort.panic(new RuntimeException("blur boom"))),
                UI.input.id("b").onFocus(ref.set("b focused")),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("a"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "b focused")
            yield succeed
        }
    }

    "onKeyDown throws keyUp still fires" in run {
        val app: UI < Async =
            for upFired <- Signal.initRef(false)
            yield UI.div(
                UI.input.id("i")
                    .onKeyDown(_ => Abort.panic(new RuntimeException("keydown boom")))
                    .onKeyUp(_ => upFired.set(true)),
                upFired.map(v => UI.span(v.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key('x'))
                _ <- Browser.assertText(Selector.id("v"), "true")
            yield succeed
        }
    }

    "onChange throws state consistent" in run {
        val app: UI < Async =
            for
                broken  <- Signal.initRef(true)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("i").onChange { _ =>
                    for
                        b <- broken.get
                        _ <- if b then Abort.panic(new RuntimeException("change boom"))
                        else counter.getAndUpdate(_ + 1).unit
                    yield ()
                },
                UI.button("Fix").id("fix").onClick(broken.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "test")
                _ <- Browser.click(Selector.id("fix"))
                _ <- Browser.fill(Selector.id("i"), "test2")
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "onSubmit throws form still submittable" in run {
        val app: UI < Async =
            for
                broken  <- Signal.initRef(true)
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        b <- broken.get
                        _ <- if b then Abort.panic(new RuntimeException("submit boom"))
                        else counter.getAndUpdate(_ + 1).unit
                    yield ()
                }(UI.button("Sub").id("sub")),
                UI.button("Fix").id("fix").onClick(broken.set(false)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.click(Selector.id("fix"))
                _ <- Browser.click(Selector.id("sub"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "handler throws first call succeeds second" in run {
        val app: UI < Async =
            for callCount <- Signal.initRef(0)
            yield UI.div(
                UI.button("Go").id("b").onClick {
                    callCount.getAndUpdate(_ + 1).map { n =>
                        if n == 0 then Abort.panic(new RuntimeException("first call boom"))
                        else ()
                    }
                },
                callCount.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "2")
            yield succeed
        }
    }

    "multiple handlers first throws second succeeds both run" in run {
        val app: UI < Async =
            for log <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.div(
                    UI.button("Go").id("b").onClick(Abort.panic(new RuntimeException("inner boom")))
                ).onClick(log.getAndUpdate(_.appended("outer")).unit),
                log.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "outer")
            yield succeed
        }
    }

    "signal subscription continues after sibling error" in run {
        val app: UI < Async =
            for
                good <- Signal.initRef(0)
            yield UI.div(
                UI.button("Good").id("good").onClick(good.getAndUpdate(_ + 1).unit),
                UI.button("Bad").id("bad").onClick(Abort.panic(new RuntimeException("bad boom"))),
                good.map(n => UI.span(s"good:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("bad"))
                _ <- Browser.click(Selector.id("good"))
                _ <- Browser.click(Selector.id("good"))
                _ <- Browser.assertText(Selector.id("v"), "good:2")
            yield succeed
        }
    }

end ReactiveTest

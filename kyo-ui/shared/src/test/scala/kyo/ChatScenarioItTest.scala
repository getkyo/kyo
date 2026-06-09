package kyo

import kyo.Browser.*
import kyo.UI.foreach

class ChatScenarioItTest extends UITest:

    "type message Enter appears in list input clears" in {
        val app: UI < Async =
            for
                input    <- Signal.initRef("")
                messages <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then messages.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                }(
                    UI.input.id("inp").value(input),
                    UI.button("Send").id("send")
                ),
                messages.map(ms => UI.span(s"count:${ms.size}").id("count"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "hello")
                _ <- Browser.press(Selector.id("inp"), Key.Enter)
                _ <- Browser.assertText(Selector.id("count"), "count:1")
            yield ()
        }
    }

    "second message appears below first" in {
        val app: UI < Async =
            for
                input    <- Signal.initRef("")
                messages <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("inp").value(input),
                UI.button("Send").id("send").onClick {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then messages.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                },
                messages.map(ms => UI.span(s"count:${ms.size}").id("count"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "hello")
                _ <- Browser.click(Selector.id("send"))
                _ <- Browser.fill(Selector.id("inp"), "world")
                _ <- Browser.click(Selector.id("send"))
                _ <- Browser.assertText(Selector.id("count"), "count:2")
            yield ()
        }
    }

    "Enter on empty input nothing sent" in {
        val app: UI < Async =
            for
                input    <- Signal.initRef("")
                messages <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.form.id("f").onSubmit {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then messages.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                }(
                    UI.input.id("inp").value(input),
                    UI.button("Send").id("send")
                ),
                messages.map(ms => UI.span(s"count:${ms.size}").id("count"))
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("inp"), Key.Enter)
                _ <- Browser.assertText(Selector.id("count"), "count:0")
            yield ()
        }
    }

    "multiple rapid messages all appear" in {
        val app: UI < Async =
            for
                input    <- Signal.initRef("")
                messages <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("inp").value(input),
                UI.button("Send").id("send").onClick {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then messages.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                },
                messages.map(ms => UI.span(s"count:${ms.size}").id("count"))
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(1 to 5) { i =>
                    Browser.fill(Selector.id("inp"), s"msg$i").andThen(Browser.click(Selector.id("send")))
                }
                _ <- Browser.assertText(Selector.id("count"), "count:5")
            yield ()
        }
    }

    "very long message signal preserves" in {
        val longMsg = "x" * 500
        val app: UI < Async =
            for
                input <- Signal.initRef("")
                last  <- Signal.initRef("")
            yield UI.div(
                UI.input.id("inp").value(input),
                UI.button("Send").id("send").onClick {
                    for
                        v <- input.get
                        _ <- last.set(v)
                        _ <- input.set("")
                    yield ()
                },
                last.map(v => UI.span(s"len:${v.length}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), longMsg)
                _ <- Browser.click(Selector.id("send"))
                _ <- Browser.assertText(Selector.id("v"), "len:500")
            yield ()
        }
    }

    "messages display correct content" in {
        val app: UI < Async =
            for
                input    <- Signal.initRef("")
                messages <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("inp").value(input),
                UI.button("Send").id("send").onClick {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then messages.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                },
                messages.foreach(m => UI.span(m))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "hello")
                _ <- Browser.click(Selector.id("send"))
                _ <- Browser.fill(Selector.id("inp"), "world")
                _ <- Browser.click(Selector.id("send"))
                _ <- assertContains("hello")
                _ <- assertContains("world")
            yield ()
        }
    }

    "50 messages all in list" in {
        val app: UI < Async =
            for messages <- Signal.initRef(Chunk.from(0 until 50))
            yield UI.div(
                messages.map(ms => UI.span(s"count:${ms.size}").id("v")),
                messages.foreach(i => UI.span(s"msg$i"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "count:50")
                _ <- assertContains("msg0")
                _ <- assertContains("msg49")
            yield ()
        }
    }

    "input focuses after send" in {
        val app: UI < Async =
            for
                input    <- Signal.initRef("")
                messages <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.input.id("inp").value(input),
                UI.button("Send").id("send").onClick {
                    for
                        v <- input.get
                        _ <- if v.nonEmpty then messages.getAndUpdate(_.appended(v)).andThen(input.set(""))
                        else Kyo.lift(())
                    yield ()
                },
                messages.map(ms => UI.span(s"count:${ms.size}").id("count"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("inp"), "hello")
                _ <- Browser.click(Selector.id("send"))
                _ <- Browser.assertText(Selector.id("count"), "count:1")
                _ <- Browser.fill(Selector.id("inp"), "world")
                _ <- Browser.click(Selector.id("send"))
                _ <- Browser.assertText(Selector.id("count"), "count:2")
            yield ()
        }
    }

end ChatScenarioItTest

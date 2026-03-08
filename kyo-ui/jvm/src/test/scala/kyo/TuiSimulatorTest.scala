package kyo

import scala.language.implicitConversions

class TuiSimulatorTest extends Test with UIScope:

    "counter" - {
        "initial frame shows 0 between buttons" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        span(count.map(_.toString)),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    ),
                    cols = 20,
                    rows = 3
                )
                val f = sim.frame
                assert(f.contains("-"), s"Should show '-':\n$f")
                assert(f.contains("0"), s"Should show '0':\n$f")
                assert(f.contains("+"), s"Should show '+':\n$f")
        }

        "clicking + increments counter" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        span(count.map(_.toString)),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    ),
                    cols = 20,
                    rows = 3
                )
                sim.clickOn("+")
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("1"), s"Should show '1' after clicking +:\n$f")
        }

        "clicking - decrements counter" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        span(count.map(_.toString)),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    ),
                    cols = 20,
                    rows = 3
                )
                sim.clickOn("-")
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("-1"), s"Should show '-1' after clicking -:\n$f")
        }

        "multiple clicks accumulate" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        span(count.map(_.toString)),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    ),
                    cols = 20,
                    rows = 3
                )
                // Tab to + button, then use Enter 3 times
                sim.tab(); sim.tab() // skip -, skip span, land on +
                sim.enter(); sim.waitForEffects()
                sim.enter(); sim.waitForEffects()
                sim.enter(); sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("3"), s"Should show '3' after 3 activations:\n$f")
        }
    }

    "text input" - {
        "typing updates input value" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    nav(
                        input.value(text).placeholder("Type..."),
                        span(text.map(v => s"=$v"))
                    ),
                    cols = 40,
                    rows = 3
                )
                // Focus the input
                sim.tab()
                sim.typeText("hello")
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("hello"), s"Should show typed text:\n$f")
                assert(f.contains("=hello"), s"Should show signal value:\n$f")
        }

        "backspace deletes characters" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    nav(
                        input.value(text).placeholder("Type..."),
                        span(text.map(v => s"=$v"))
                    ),
                    cols = 40,
                    rows = 3
                )
                sim.tab()
                sim.typeText("abc")
                sim.key("Backspace")
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("=ab"), s"Should show 'ab' after backspace:\n$f")
        }

        "placeholder shown when empty" in run {
            for
                text <- Signal.initRef("")
            yield
                val sim = TuiSimulator(
                    div(
                        input.value(text).placeholder("Type here")
                    ),
                    cols = 50,
                    rows = 3
                )
                val f = sim.frame
                assert(f.contains("Type here"), s"Should show placeholder:\n$f")
        }
    }

    "todo list" - {
        "add item via button click" in run {
            for
                text  <- Signal.initRef("")
                items <- Signal.initRef(Chunk.empty[String])
            yield
                val sim = TuiSimulator(
                    div(
                        nav(
                            input.value(text).placeholder("Add..."),
                            button("Add").onClick {
                                for
                                    t <- text.get
                                    _ <- if t.nonEmpty then items.getAndUpdate(_.append(t)).unit
                                    else ((): Unit < Sync)
                                    _ <- text.set("")
                                yield ()
                            }
                        ),
                        ul(items.foreach(item => li(span(item))))
                    ),
                    cols = 40,
                    rows = 10
                )
                // Focus input, type, tab to Add button, press Enter
                sim.tab()
                sim.typeText("Buy milk")
                sim.waitForEffects()
                sim.tab() // move to Add button
                sim.enter()
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("Buy milk"), s"Should show added item:\n$f")
        }
    }

    "styles" - {
        "bold text renders" in {
            val sim = TuiSimulator(p.style(Style.bold)("Bold text"), cols = 40, rows = 5)
            val f   = sim.frame
            assert(f.contains("Bold text"), s"Should show bold text:\n$f")
        }

        "colored text renders" in {
            val sim = TuiSimulator(
                p.style(Style.bg("#0000ff").color("#ffffff"))("Blue bg"),
                cols = 20,
                rows = 3
            )
            val f = sim.frame
            assert(f.contains("Blue bg"), s"Should show colored text:\n$f")
        }
    }

    "table" - {
        "renders header and data in rows" in {
            val sim = TuiSimulator(
                table(
                    tr(th("Name"), th(" Age")),
                    tr(td("Alice"), td(" 30"))
                ),
                cols = 50,
                rows = 5
            )
            val f          = sim.frame
            val lines      = f.split("\n")
            val headerLine = lines.find(l => l.contains("Name") && l.contains("Age"))
            assert(headerLine.isDefined, s"Header should be on one line:\n$f")
            val dataLine = lines.find(l => l.contains("Alice") && l.contains("30"))
            assert(dataLine.isDefined, s"Data should be on one line:\n$f")
        }
    }

    "borders" - {
        "rounded border renders box-drawing chars" in {
            val sim = TuiSimulator(
                div.style(Style.border(8, Style.BorderStyle.solid, "#888").rounded(8))("Hi"),
                cols = 20,
                rows = 5
            )
            val f = sim.frame
            assert(f.contains("╭"), s"Should have rounded top-left corner:\n$f")
            assert(f.contains("╯"), s"Should have rounded bottom-right corner:\n$f")
            assert(f.contains("Hi"), s"Should show content:\n$f")
        }

        "dashed border renders" in {
            val sim = TuiSimulator(
                div.style(Style.border(8, Style.BorderStyle.dashed, "#888"))("Hi"),
                cols = 20,
                rows = 5
            )
            val f = sim.frame
            assert(f.contains("Hi"), s"Should show content:\n$f")
            assert(f.contains("┄") || f.contains("┆"), s"Should have dashed chars:\n$f")
        }
    }

    "tabs" - {
        "clicking tab buttons switches content" in run {
            for
                tab <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    div(
                        nav(
                            button("Tab1").onClick(tab.set(0)),
                            button("Tab2").onClick(tab.set(1))
                        ),
                        UI.when(tab.map(_ == 0))(p("Content A")),
                        UI.when(tab.map(_ == 1))(p("Content B"))
                    ),
                    cols = 50,
                    rows = 5
                )
                val f1 = sim.frame
                java.lang.System.err.println(s"TAB INITIAL:\n$f1")
                assert(f1.contains("Content A"), s"Initially tab 1:\n$f1")
                // Tab to Tab2 button then press Enter
                sim.tab()
                java.lang.System.err.println(s"TAB AFTER TAB (focused=${sim.focusedIndex}):")
                sim.enter()
                sim.waitForEffects()
                val f2 = sim.frame
                java.lang.System.err.println(s"TAB AFTER ENTER:\n$f2")
                assert(f2.contains("Content B"), s"After activating tab 2:\n$f2")
                assert(!f2.contains("Content A"), s"Tab 1 should be hidden:\n$f2")
        }
    }

    "keyboard navigation" - {
        "tab cycles focus between buttons" in {
            val sim = TuiSimulator(
                nav(
                    button("A"),
                    button("B"),
                    button("C")
                ),
                cols = 20,
                rows = 3
            )
            val f0 = sim.focusedIndex
            sim.tab()
            val f1 = sim.focusedIndex
            sim.tab()
            val f2 = sim.focusedIndex
            assert(f0 != f1 && f1 != f2, s"Tab should cycle focus: $f0 -> $f1 -> $f2")
        }

        "enter activates focused button" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("+").onClick(count.getAndUpdate(_ + 1).unit),
                        span(count.map(_.toString))
                    ),
                    cols = 20,
                    rows = 3
                )
                sim.enter()
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("1"), s"Enter should activate button:\n$f")
        }

        "space activates focused button" in run {
            for
                count <- Signal.initRef(0)
            yield
                val sim = TuiSimulator(
                    nav(
                        button("+").onClick(count.getAndUpdate(_ + 1).unit),
                        span(count.map(_.toString))
                    ),
                    cols = 20,
                    rows = 3
                )
                sim.space()
                sim.waitForEffects()
                val f = sim.frame
                assert(f.contains("1"), s"Space should activate button:\n$f")
        }
    }

end TuiSimulatorTest

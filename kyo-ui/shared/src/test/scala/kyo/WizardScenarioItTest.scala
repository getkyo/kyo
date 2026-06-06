package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class WizardScenarioItTest extends UITest:

    "step 1 fill next step 2 shown" in {
        val app: UI < Async =
            for
                step <- Signal.initRef(1)
                name <- Signal.initRef("")
            yield UI.div(
                step.map {
                    case 1 => UI.div(
                            UI.input.id("name").value(name),
                            UI.button("Next").id("next1").onClick(step.set(2))
                        )
                    case _ => UI.div(UI.span("Step 2").id("s2"))
                }
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("next1"))
                _ <- Browser.assertText(Selector.id("s2"), "Step 2")
            yield ()
        }
    }

    "premium path completes with all values" in {
        val app: UI < Async =
            for
                step        <- Signal.initRef(1)
                name        <- Signal.initRef("")
                plan        <- Signal.initRef("free")
                card        <- Signal.initRef("")
                result      <- Signal.initRef("")
                showBilling <- Signal.initRef(false)
            yield UI.div(
                step.map {
                    case 1 => UI.div(
                            UI.input.id("name").value(name),
                            UI.button("Next").id("next1").onClick(step.set(2))
                        )
                    case 2 => UI.div(
                            UI.select(UI.option("Free").value("free"), UI.option("Premium").value("premium"))
                                .id("plan").value(plan).onChange(v => plan.set(v)),
                            UI.button("Back").id("back2").onClick(step.set(1)),
                            UI.button("Next").id("next2").onClick {
                                for
                                    p <- plan.get
                                    _ <- showBilling.set(p == "premium")
                                    _ <- step.set(3)
                                yield ()
                            }
                        )
                    case 3 => UI.div(
                            UI.when(showBilling)(UI.input.id("card").value(card)),
                            UI.button("Back").id("back3").onClick(step.set(2)),
                            UI.button("Finish").id("finish").onClick {
                                for
                                    n <- name.get
                                    p <- plan.get
                                    c <- card.get
                                    _ <- result.set(s"$n|$p|$c")
                                    _ <- step.set(4)
                                yield ()
                            }
                        )
                    case _ => UI.span("done")
                },
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("next1"))
                _ <- Browser.select(Selector.id("plan"), "premium")
                _ <- Browser.click(Selector.id("next2"))
                _ <- Browser.fill(Selector.id("card"), "1234")
                _ <- Browser.click(Selector.id("finish"))
                _ <- Browser.assertText(Selector.id("v"), "result:Alice|premium|1234")
            yield ()
        }
    }

    "free path skips billing" in {
        val app: UI < Async =
            for
                step        <- Signal.initRef(1)
                name        <- Signal.initRef("")
                plan        <- Signal.initRef("free")
                result      <- Signal.initRef("")
                showBilling <- Signal.initRef(false)
            yield UI.div(
                step.map {
                    case 1 => UI.div(
                            UI.input.id("name").value(name),
                            UI.button("Next").id("next1").onClick(step.set(2))
                        )
                    case 2 => UI.div(
                            UI.select(UI.option("Free").value("free"), UI.option("Premium").value("premium"))
                                .id("plan").value(plan),
                            UI.button("Next").id("next2").onClick {
                                for
                                    p <- plan.get
                                    _ <- showBilling.set(p == "premium")
                                    _ <- step.set(3)
                                yield ()
                            }
                        )
                    case 3 => UI.div(
                            UI.button("Finish").id("finish").onClick {
                                for
                                    n <- name.get
                                    p <- plan.get
                                    _ <- result.set(s"$n|$p")
                                    _ <- step.set(4)
                                yield ()
                            }
                        )
                    case _ => UI.span("done")
                },
                result.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Bob")
                _ <- Browser.click(Selector.id("next1"))
                _ <- Browser.click(Selector.id("next2"))
                _ <- Browser.click(Selector.id("finish"))
                _ <- Browser.assertText(Selector.id("v"), "result:Bob|free")
            yield ()
        }
    }

    "back to step 1 name preserved" in {
        val app: UI < Async =
            for
                step <- Signal.initRef(1)
                name <- Signal.initRef("")
            yield UI.div(
                step.map {
                    case 1 => UI.div(
                            UI.input.id("name").value(name),
                            UI.button("Next").id("next1").onClick(step.set(2))
                        )
                    case _ => UI.div(
                            UI.button("Back").id("back2").onClick(step.set(1))
                        )
                }
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("next1"))
                _ <- Browser.click(Selector.id("back2"))
                _ <- Browser.assertAttribute(Selector.id("name"), "value", "Alice")
            yield ()
        }
    }

    "back change name forward shows updated" in {
        val app: UI < Async =
            for
                step   <- Signal.initRef(1)
                name   <- Signal.initRef("")
                result <- Signal.initRef("")
            yield UI.div(
                step.map {
                    case 1 => UI.div(
                            UI.input.id("name").value(name),
                            UI.button("Next").id("next1").onClick(step.set(2))
                        )
                    case _ => UI.div(
                            UI.button("Back").id("back").onClick(step.set(1)),
                            UI.button("Done").id("done").onClick {
                                name.get.map(n => result.set(s"result:$n"))
                            }
                        )
                },
                result.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("name"), "Alice")
                _ <- Browser.click(Selector.id("next1"))
                _ <- Browser.click(Selector.id("back"))
                _ <- Browser.fill(Selector.id("name"), "Bob")
                _ <- Browser.click(Selector.id("next1"))
                _ <- Browser.click(Selector.id("done"))
                _ <- Browser.assertText(Selector.id("v"), "result:Bob")
            yield ()
        }
    }

    "all steps signal backed values preserved" in {
        val app: UI < Async =
            for
                step <- Signal.initRef(1)
                a    <- Signal.initRef("")
                b    <- Signal.initRef("")
            yield UI.div(
                step.map {
                    case 1 => UI.div(
                            UI.input.id("a").value(a),
                            UI.button("Next").id("n1").onClick(step.set(2))
                        )
                    case 2 => UI.div(
                            UI.input.id("b").value(b),
                            UI.button("Back").id("b2").onClick(step.set(1)),
                            UI.button("Next").id("n2").onClick(step.set(3))
                        )
                    case _ => UI.span("done")
                },
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("a"), "step1val")
                _ <- Browser.click(Selector.id("n1"))
                _ <- Browser.fill(Selector.id("b"), "step2val")
                _ <- Browser.click(Selector.id("b2"))
                _ <- Browser.assertText(Selector.id("va"), "a:step1val")
                _ <- Browser.click(Selector.id("n1"))
                _ <- Browser.assertText(Selector.id("vb"), "b:step2val")
            yield ()
        }
    }

    "Enter advances step via form submit" in {
        val app: UI < Async =
            for step <- Signal.initRef(1)
            yield UI.div(
                step.map {
                    case 1 => UI.form.id("f1").onSubmit(step.set(2))(
                            UI.input.id("i"),
                            UI.button("Next").id("next")
                        )
                    case _ => UI.span("step2").id("s2")
                }
            )
        withUI(app) {
            for
                _ <- Browser.press(Selector.id("i"), Key.Enter)
                _ <- Browser.assertText(Selector.id("s2"), "step2")
            yield ()
        }
    }

end WizardScenarioItTest

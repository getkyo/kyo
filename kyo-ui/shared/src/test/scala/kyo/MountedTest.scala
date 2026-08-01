package kyo

import kyo.Browser.*

/** Behavior tests for [[kyo.UI.mounted]]: the effectful component mount node.
  *
  * Runs against the LiveView transport (UITest.withUI = runHandlers + real Chrome over the WebSocket
  * exchange), i.e. the shared ReactiveUI engine: mount effects execute server-side on fibers descending
  * from the session, which is also what makes the Env test meaningful (context flows through the
  * HttpServer.init ancestry into the mount effect at run time, with no Env in any UI-facing type).
  */
class MountedTest extends UITest:

    "mounted effect renders its content" in {
        val app: UI < Async =
            Kyo.lift(UI.div(
                UI.mounted(Kyo.lift(UI.span("hello-from-mount").id("content")))
                    .placeholder(UI.span("loading...").id("ph"))
            ))
        withUI(app) {
            Browser.assertText(Selector.id("content"), "hello-from-mount")
        }
    }

    "placeholder shows while the effect is pending, content after it publishes" in {
        val app: (Promise[Unit, Any], UI < Async) < Sync =
            for gate <- Promise.init[Unit, Any]
            yield (
                gate,
                Kyo.lift(UI.div(
                    UI.mounted(gate.get.andThen(Kyo.lift(UI.span("ready").id("content"))))
                        .placeholder(UI.span("loading...").id("ph"))
                ))
            )
        app.map { (gate, ui) =>
            withUI(ui) {
                for
                    _ <- Browser.assertText(Selector.id("ph"), "loading...")
                    _ <- gate.completeUnitDiscard
                    _ <- Browser.assertText(Selector.id("content"), "ready")
                yield ()
            }
        }
    }

    "Env provided at the mount root flows into the mount effect (runMount-style fiber ancestry)" in {
        // Direct engine test: subscribe is invoked from THIS fiber, so the mount runner descends from it (like the
        // browser runMount path) and the erased Env[String] resolves at run time. Deliberately NOT via withUI: in the
        // LiveView transport session fibers descend from kyo-http internals, so root Env does not reach mount effects
        // there today (documented follow-up).
        val greetEffect: UI < (Async & Scope & Env[String]) =
            Env.get[String].map(greeting => UI.span(greeting).id("content"))
        val ui: UI = UI.div(UI.mounted(greetEffect))
        Env.run("hello-env") {
            Scope.run {
                for
                    seen <- Promise.init[String, Any]
                    exchange = new kyo.internal.UIExchange:
                        def onChange(path: Seq[String], changed: UI)(using Frame): Unit < Async =
                            kyo.internal.HtmlRenderer.render(changed, path).map { html =>
                                if html.contains("hello-env") then seen.completeDiscard(Result.succeed(html))
                                else ()
                            }
                    root <- kyo.internal.ReactiveUI.normalize(ui, Seq.empty)
                    _    <- kyo.internal.ReactiveUI.subscribe(root, exchange)
                    html <- seen.get
                yield assert(html.contains("hello-env"))
            }
        }
    }

    "keyless mounted remounts (effect re-runs) when the enclosing region re-renders" in {
        val app: UI < Async =
            for
                runs <- AtomicInt.init(0)
                tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("Tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map(_ => UI.div(UI.mounted(runs.incrementAndGet.map(n => UI.span(s"runs:$n").id("content")))))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("content"), "runs:1")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("content"), "runs:2")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("content"), "runs:3")
            yield ()
        }
    }

    "keyed mounted keeps its live instance across region re-renders" in {
        val app: UI < Async =
            for
                runs <- AtomicInt.init(0)
                tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("Tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map(n =>
                    UI.div(
                        UI.span(s"tick:$n").id("tickview"),
                        UI.mounted(runs.incrementAndGet.map(r => UI.span(s"runs:$r").id("content"))).keyed("box")
                    )
                )
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("content"), "runs:1")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("tickview"), "tick:1")
                _ <- Browser.assertText(Selector.id("content"), "runs:1")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("tickview"), "tick:2")
                _ <- Browser.assertText(Selector.id("content"), "runs:1")
            yield ()
        }
    }

    "keyed mounted keeps component-local signal state across region re-renders" in {
        def counterComponent(runs: AtomicInt): UI < (Async & Scope) =
            for
                _     <- runs.incrementAndGet
                count <- Signal.initRef(0)
            yield UI.div(
                UI.button("Inc").id("inc").onClick(count.getAndUpdate(_ + 1).unit),
                count.map(c => UI.span(s"count:$c").id("count"))
            )
        val app: UI < Async =
            for
                runs <- AtomicInt.init(0)
                tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("Tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map(_ => UI.div(UI.mounted(counterComponent(runs)).keyed("counter")))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("count"), "count:0")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "count:2")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("count"), "count:2")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "count:3")
            yield ()
        }
    }

    "key change tears down the old instance (Scope closed) and mounts a fresh one" in {
        def component(key: Int, runs: AtomicInt, released: AtomicInt): UI < (Async & Scope) =
            for
                _ <- runs.incrementAndGet
                _ <- Scope.ensure(released.incrementAndGet.unit)
            yield UI.span(s"instance:$key").id("content")
        val app: (AtomicInt, AtomicInt, UI < Async) < Sync =
            for
                runs     <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                sel      <- Signal.initRef(1)
            yield (
                runs,
                released,
                Kyo.lift(UI.div(
                    UI.button("Next").id("next").onClick(sel.getAndUpdate(_ + 1).unit),
                    sel.map(k => UI.div(UI.mounted(component(k, runs, released)).keyed(k)))
                ))
            )
        app.map { (runs, released, ui) =>
            withUI(ui) {
                for
                    _ <- Browser.assertText(Selector.id("content"), "instance:1")
                    _ <- Browser.click(Selector.id("next"))
                    _ <- Browser.assertText(Selector.id("content"), "instance:2")
                    r <- released.get
                    n <- runs.get
                yield
                    assert(r == 1) // old instance's Scope was closed on key change
                    assert(n == 2) // and the effect ran exactly once per key
            }
        }
    }

    "failed mount renders the error state while the enclosing region stays alive" in {
        val app: UI < Async =
            for other <- Signal.initRef("sibling-0")
            yield UI.div(
                UI.button("Bump").id("bump").onClick(other.set("sibling-1")),
                other.map(v => UI.span(v).id("sibling")),
                UI.mounted(Abort.fail(new RuntimeException("boom-mount")))
                    .onError(e => UI.span(s"error:${e.source}:${e.message}").id("content"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("content"), "error:Effect:boom-mount")
                _ <- Browser.click(Selector.id("bump"))
                _ <- Browser.assertText(Selector.id("sibling"), "sibling-1")
            yield ()
        }
    }

    "handler inside a nested region INSIDE mounted content dispatches (region -> mounted -> region -> button)" in {
        def component(): UI < (Async & Scope) =
            for
                gate  <- Signal.initRef(true)
                count <- Signal.initRef(0)
            yield UI.div(
                gate.map(g =>
                    if g then
                        UI.div(
                            UI.button("Inc").id("inc").onClick(count.getAndUpdate(_ + 1).unit),
                            count.map(c => UI.span(s"count:$c").id("count"))
                        )
                    else UI.span("off")
                )
            )
        val app: UI < Async =
            for
                tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("Tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map(_ => UI.div(UI.mounted(component()).keyed("nested")))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("count"), "count:0")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "count:1")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "count:2")
            yield ()
        }
    }

    "handler under TWO nested regions inside mounted content dispatches (mounted -> region -> region -> button)" in {
        def component(): UI < (Async & Scope) =
            for
                outer <- Signal.initRef(0)
                inner <- Signal.initRef(0)
                count <- Signal.initRef(0)
            yield UI.div(
                outer.map(_ =>
                    UI.div(
                        inner.map(_ =>
                            UI.div(
                                UI.button("Inc").id("inc").onClick(count.getAndUpdate(_ + 1).unit),
                                count.map(c => UI.span(s"count:$c").id("count"))
                            )
                        )
                    )
                )
            )
        val app: UI < Async =
            Kyo.lift(UI.div(UI.mounted(component()).keyed("deep")))
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("count"), "count:0")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "count:1")
            yield ()
        }
    }

    "mount content whose ROOT is a region stays live (region-at-cell-root: patches AND dispatch)" in {
        def component(): UI < (Async & Scope) =
            for
                idSig <- Signal.initRef(0)
                count <- Signal.initRef(0)
            yield (idSig.map { _ =>
                UI.div(
                    UI.button("Inc").id("inc").onClick(count.getAndUpdate(_ + 1).unit),
                    count.map(c => UI.span(s"count:$c").id("count"))
                )
            }: UI)
        val app: UI < Async =
            Kyo.lift(UI.div(UI.mounted(component()).keyed("rootregion")))
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("count"), "count:0")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "count:1")
            yield ()
        }
    }

    "mounted node as the direct ROOT of a region's content stays live (route shape: region content = Mounted)" in {
        def component(runs: AtomicInt): UI < (Async & Scope) =
            for
                _     <- runs.incrementAndGet
                count <- Signal.initRef(0)
            yield UI.div(
                UI.button("Inc").id("inc").onClick(count.getAndUpdate(_ + 1).unit),
                count.map(c => UI.span(s"count:$c").id("count"))
            )
        val app: UI < Async =
            for
                runs <- AtomicInt.init(0)
                tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("Tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map(n => (UI.mounted(component(runs)).keyed(("box", n)): UI))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("count"), "count:0")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "count:1")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("count"), "count:0")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("count"), "count:1")
            yield ()
        }
    }

    "nested route shape end-to-end: region -> Mounted(root) -> region(content root) -> element -> region -> button" in {
        def page(count: Signal.SignalRef[Int]): UI < (Async & Scope) =
            for
                identity <- Signal.initRef("me")
                query    <- Signal.initRef("loaded")
            yield (identity.map { _ =>
                UI.main(
                    (query.map { _ =>
                        UI.div(
                            UI.button("Inc").id("inc").onClick(count.getAndUpdate(_ + 1).unit),
                            count.map(c => UI.span(s"count:$c").id("count"))
                        )
                    }: UI)
                )
            }: UI)
        val app: (Signal.SignalRef[String], UI < Async) < Sync =
            for
                count    <- Signal.initRef(0)
                location <- Signal.initRef("/a")
            yield (
                location,
                Kyo.lift(UI.div(
                    UI.span("header").id("hdr"),
                    UI.div((location.map(path => (UI.mounted(page(count)).keyed(path): UI)): UI))
                ))
            )
        app.map { (location, ui) =>
            withUI(ui) {
                for
                    _ <- Browser.assertText(Selector.id("count"), "count:0")
                    _ <- Browser.click(Selector.id("inc"))
                    _ <- Browser.assertText(Selector.id("count"), "count:1")
                    _ <- location.set("/b")
                    // count survives navigation: the count signal is shared across pages
                    _ <- Browser.assertText(Selector.id("count"), "count:1")
                    _ <- Browser.click(Selector.id("inc"))
                    _ <- Browser.assertText(Selector.id("count"), "count:2")
                yield ()
            }
        }
    }

    "click on a REACTIVE child of a button inside mounted content bubbles to the button handler" in {
        def component(): UI < (Async & Scope) =
            for
                label <- Signal.initRef("Leave")
                count <- Signal.initRef(0)
            yield UI.div(
                UI.button((label.map(s => (UI.Ast.Text(s): UI)): UI)).id("inc").onClick(count.getAndUpdate(_ + 1).unit),
                count.map(c => UI.span(s"count:$c").id("count"))
            )
        val app: UI < Async =
            Kyo.lift(UI.div(UI.mounted(component()).keyed("i18nbtn")))
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("count"), "count:0")
                _ <- Browser.click(Selector.id("inc")) // centre click hits the inner reactive span
                _ <- Browser.assertText(Selector.id("count"), "count:1")
            yield ()
        }
    }

    "ENGINE: dispatch targeting a reactive label INSIDE a button (below intermediate static elements) runs the button handler" in {
        Scope.run {
            for
                label   <- Signal.initRef("Leave")
                clicked <- AtomicInt.init(0)
                ui = UI.div(
                    UI.div(UI.span("cards")),
                    UI.div( // button-bar (static intermediate)
                        UI.button((label.map(s => (UI.Ast.Text(s): UI)): UI))
                            .id("leave")
                            .onClick(clicked.incrementAndGet.unit)
                    )
                )
                exchange = new kyo.internal.UIExchange:
                    def onChange(path: Seq[String], changed: UI)(using Frame): Unit < Async = ()
                root     <- kyo.internal.ReactiveUI.normalize(ui, Seq.empty)
                dispatch <- kyo.internal.ReactiveUI.subscribe(root, exchange)
                // Target = the LABEL region's path: div(0)=cards-div... button-bar=child 1, button=child 0, label=child 0.
                labelPath = Seq("1", "0", "0")
                handled <- dispatch.handle(
                    labelPath,
                    kyo.internal.UIEvent.Click(labelPath, kyo.internal.MouseEventData(UI.Modifiers.none, Absent))
                )
                n <- clicked.get
            yield assert(handled && n == 1, s"handled=$handled clicks=$n (expected the BUTTON handler to run)")
        }
    }

    "duplicate mounted key: the first slot mounts, the duplicate slot renders an inline error card" in {
        val app: UI < Async =
            for
                runs <- AtomicInt.init(0)
            yield UI.div(
                UI.mounted(runs.incrementAndGet.map(r => UI.span(s"runs:$r").id("first"))).keyed("dup"),
                UI.mounted(runs.incrementAndGet.map(r => UI.span(s"runs:$r").id("second"))).keyed("dup")
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("first"), "runs:1")
                // duplicate slot never runs its effect: it paints the error card rather than silently sharing the first instance
                _ <- Browser.assertText(Selector.css(".kyo-mount-error"), "kyo-ui: duplicate mounted key 'dup'")
            yield ()
        }
    }

    "unstable key (fresh per re-render) re-creates the instance each pass (the churn-hint path)" in {
        val app: UI < Async =
            for
                runs <- AtomicInt.init(0)
                tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("Tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.map(n =>
                    UI.div(
                        UI.mounted(runs.incrementAndGet.map(r => UI.span(s"runs:$r").id("content"))).keyed(("box", n))
                    )
                )
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("content"), "runs:1")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("content"), "runs:2")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("content"), "runs:3")
                // Third consecutive key change: the unstable-key dev hint logs on this pass.
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("content"), "runs:4")
            yield ()
        }
    }

    "background fiber failure after publish flips the mounted node to its error render (node-scope supervision)" in {
        val setup =
            for gate <- Promise.init[Unit, Any]
            yield (
                gate,
                UI.div(
                    UI.span("sibling").id("sibling"),
                    UI.mounted(
                        UI.fork(gate.get.andThen(Abort.fail(new RuntimeException("feed-died"))))
                            .map(_ => UI.span("live").id("content"))
                    ).keyed("game")
                        .onError(e => UI.span(s"error:${e.message}").id("content"))
                )
            )
        setup.map { (gate, ui) =>
            withUI(Kyo.lift(ui)) {
                for
                    _ <- Browser.assertText(Selector.id("content"), "live")
                    _ <- gate.completeUnitDiscard // feed dies AFTER publish
                    _ <- Browser.assertText(Selector.id("content"), "error:feed-died")
                    _ <- Browser.assertText(Selector.id("sibling"), "sibling")
                yield ()
            }
        }
    }

    "keyed instance kept across region re-renders still flips on a later feed failure (supervision survives adoption)" in {
        val app: UI < Async =
            for
                gate <- Promise.init[Unit, Any]
                tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("Tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                UI.button("Kill").id("kill").onClick(gate.completeUnitDiscard),
                tick.map(n =>
                    UI.div(
                        UI.span(s"tick:$n").id("tick-val"),
                        UI.mounted(
                            UI.fork(gate.get.andThen(Abort.fail(new RuntimeException("late-fail"))))
                                .map(_ => UI.span("live").id("content"))
                        ).keyed("stable")
                            .onError(e => UI.span(s"error:${e.message}").id("content"))
                    )
                )
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("content"), "live")
                _ <- Browser.click(Selector.id("tick")) // instance is ADOPTED across the re-render, not re-created
                _ <- Browser.assertText(Selector.id("tick-val"), "tick:1")
                _ <- Browser.assertText(Selector.id("content"), "live")
                _ <- Browser.click(Selector.id("kill"))
                _ <- Browser.assertText(Selector.id("content"), "error:late-fail")
            yield ()
        }
    }

end MountedTest

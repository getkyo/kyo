package kyo

import kyo.internal.MouseEventData
import kyo.internal.UIEvent

/** Effect-typed event handlers: `onClick[S](...)(using Isolate[S, Sync, S])`. Context effects ride the
  * handler erased and resolve from the dispatching fiber's inherited context at run time.
  *
  * Direct engine tests (no browser): the dispatch handle is invoked from THIS computation, mirroring the
  * browser backend's event-drain fiber, which descends from the `runMount` caller, so `Env.run` around the
  * dispatch is exactly the production ancestry. (LiveView-transport dispatch runs on kyo-http session fibers
  * and does not inherit the caller context, the same documented gap as mounted effects there.)
  */
class HandlerEnvTest extends UITest:

    "onClick handler with erased Env resolves against the dispatching fiber's context" in {
        Env.run("clicked-with-env") {
            Scope.run {
                for
                    seen <- Promise.init[String, Any]
                    ui = UI.div(
                        UI.button("Go").id("btn").onClick(
                            Env.get[String].map(v => seen.completeDiscard(Result.succeed(v)))
                        )
                    )
                    exchange = new kyo.internal.UIExchange:
                        def onChange(path: Seq[String], changed: UI)(using Frame): Unit < Async = ()
                    root <- kyo.internal.ReactiveUI.normalize(ui, Seq.empty)
                    sub  <- kyo.internal.ReactiveUI.subscribe(root, exchange)
                    _    <- sub.handle(Seq("0"), UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent)))
                    v    <- seen.get
                yield assert(v == "clicked-with-env")
            }
        }
    }

    "pure handlers still infer S = Any (source compatibility)" in {
        // Compile-level assertion: the pre-existing handler shapes typecheck unchanged against the
        // effect-polymorphic setters.
        Scope.run {
            for
                ref <- Signal.initRef(0)
                ui = UI.div(
                    UI.button("A").onClick(ref.set(1)),
                    UI.button("B").onClick((_: UI.MouseEvent) => ref.set(2)),
                    UI.form(UI.button("S")).onSubmit(ref.set(3)),
                    UI.button("H").onHover(ref.set(4)).onBlur(ref.set(5))
                )
                root <- kyo.internal.ReactiveUI.normalize(ui, Seq.empty)
            yield assert(root.path.isEmpty)
        }
    }

end HandlerEnvTest

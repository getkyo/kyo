package kyo.internal

import kyo.*

/** Teardown / cascade proof for the fully-scoped ReactiveUI subscription (Phase 2).
  *
  * The subscription forks every reactive region via `Fiber.init` rooted at the caller's `Scope`; each region opens a
  * per-value `Scope` (via `Signal.observe`) that owns its children. Closing the root `Scope` must cascade-interrupt
  * every descendant region fiber and release every leaf observation.
  *
  * Two complementary witnesses, both public API:
  *   - `live`: a `Scope.acquireRelease` finalizer registered in the held-open root `Scope.run`. After the root scope closes
  *     (the holding fiber is interrupted), `assertEventually(live == 0)` directly proves the finalizers ran (scope closed),
  *     which is the clean, non-reviving signal that the cascade has completed.
  *   - leaf `waiters()`: a region fiber parked on a `SignalRef` leaf is a ghost after interrupt (`waiters()` does not drop
  *     until the leaf is `set` again, which swaps the promise and discards the ghost). So a teardown leaf SETs each leaf
  *     once after the cascade is complete and asserts `waiters() == 0`: a leaked LIVE region fiber would re-park on the
  *     swapped promise and keep `waiters() == 1`, so `== 0` proves the cascade released that descendant observation.
  *
  * "waiters flat across re-renders" uses `waiters()` directly: each re-render SETs the leaf, clearing ghosts, so the live
  * count is exact and must never exceed 1.
  */
class ReactiveUITeardownTest extends kyo.test.Test[Any]:

    // A no-op exchange: the teardown/cascade proof does not depend on rendered output, only on subscription liveness.
    private val stubExchange: UIExchange =
        new UIExchange:
            def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async = ()

    "bound input re-renders the latest value across back-to-back changes (convergence)" in {
        // A SignalRef-bound input region. normalize maps the bound leaf to the constant `ui`, so the region rides the
        // SignalRef's exact register-before-read observe leaf. This proves the hardened loop is BOTH lossless and
        // churn-free WITHOUT the old deferred next-capture: two back-to-back ref edits (no wait between them) must each
        // be observed, and the FINAL re-render must carry the latest value, converging without any unrelated later
        // change (the prior deferred-capture loop could miss the second of a back-to-back pair and park until the next
        // unrelated change). A renderingExchange renders each emitted region to HTML (reading the bound ref fresh, as
        // the real wire does) and records the produced `value="..."`; the recorded sequence is the wire truth.
        for
            ref      <- Signal.initRef("")
            rendered <- AtomicRef.init(Chunk.empty[String])
            // Exchange that renders each emitted input region to HTML and records it (the real onChange wire behavior).
            recordingExchange =
                new UIExchange:
                    def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async =
                        HtmlRenderer.render(ui, path).map(html => rendered.updateAndGet(_.append(html)).unit)
            tree = UI.input.id("i").value(ref)
            live <- AtomicInt.init(0)
            fiber <- Fiber.initUnscoped(Scope.run {
                for
                    _    <- Scope.acquireRelease(live.incrementAndGet)(_ => live.decrementAndGet.unit)
                    root <- ReactiveUI.normalize(tree, Seq.empty)
                    _    <- ReactiveUI.subscribe(root, recordingExchange)
                    // The region is live: the bound leaf has exactly one parked waiter (exact-leaf observe).
                    _ <- assertEventually(ref.waiters.map(_ == 1))
                    _ <- Async.never
                yield ()
            })
            _ <- assertEventually(live.get.map(_ == 1))
            // Wait for the initial render (empty value) before driving edits, so liveness is established.
            _ <- assertEventually(rendered.get.map(_.exists(_.contains("""value=""""))))
            // Two back-to-back edits with NO wait between them: the loop must observe the second even when it lands
            // while the first render is in flight (register-before-read), and converge to the final value.
            _ <- ref.set("a")
            _ <- ref.set("ab")
            // Convergence witness: the LATEST recorded render must carry value="ab". A missed back-to-back wakeup would
            // leave the last render at value="a" (or the empty initial) until an unrelated change; the exact leaf never
            // misses, so this settles to "ab" on its own.
            _    <- assertEventually(rendered.get.map(_.lastOption.exists(_.contains("""value="ab""""))))
            last <- rendered.get.map(_.last)
            _    <- fiber.interrupt
            _    <- assertEventually(live.get.map(_ == 0))
        yield assert(last.contains("""value="ab""""))
        end for
    }

    "every leaf waiters == 0 after Scope closes" in {
        // Three independent reactive leaves over three retained SignalRefs. Each is a `map`-over-leaf reactive node, so
        // observe routes through the leaf's exact loop: while live each leaf has exactly one parked waiter.
        for
            ref1 <- Signal.initRef("a")
            ref2 <- Signal.initRef("b")
            ref3 <- Signal.initRef("c")
            tree = UI.div(
                ref1.map(s => UI.span(s)),
                ref2.map(s => UI.span(s)),
                ref3.map(s => UI.span(s))
            )
            live <- AtomicInt.init(0)
            // Hold the root Scope open in a fiber the test can interrupt. The `live` finalizer is the direct witness that
            // the root scope closes on interrupt; the three subscriptions are forked under it.
            fiber <- Fiber.initUnscoped(Scope.run {
                for
                    _    <- Scope.acquireRelease(live.incrementAndGet)(_ => live.decrementAndGet.unit)
                    root <- ReactiveUI.normalize(tree, Seq.empty)
                    _    <- ReactiveUI.subscribe(root, stubExchange)
                    _    <- assertEventually(ref1.waiters.map(_ == 1))
                    _    <- assertEventually(ref2.waiters.map(_ == 1))
                    _    <- assertEventually(ref3.waiters.map(_ == 1))
                    _    <- Async.never
                yield ()
            })
            _ <- assertEventually(live.get.map(_ == 1))
            _ <- fiber.interrupt
            // Finalizer witness: the root scope closed (cascade ran).
            _ <- assertEventually(live.get.map(_ == 0))
            // Descendant witness: SET each leaf (swapping its promise, discarding the parked ghost). A leaked live region
            // fiber would re-park and keep waiters == 1; `== 0` proves every leaf observation was released by the cascade.
            _  <- ref1.set("a2")
            _  <- ref2.set("b2")
            _  <- ref3.set("c2")
            _  <- assertEventually(ref1.waiters.map(_ == 0))
            _  <- assertEventually(ref2.waiters.map(_ == 0))
            _  <- assertEventually(ref3.waiters.map(_ == 0))
            w1 <- ref1.waiters
            w2 <- ref2.waiters
            w3 <- ref3.waiters
        yield assert(w1 == 0 && w2 == 0 && w3 == 0)
        end for
    }

    "waiters flat across repeated re-renders" in {
        // A parent reactive region (UI.when over outerRef, kept true) whose body is a reactive child over childRef. Each
        // re-render is driven by SETTING childRef (the leaf), which both renders the new value AND swaps the leaf promise
        // (clearing the prior park's ghost), so waiters() is exact: it counts only LIVE child observations. The child
        // region's per-value observe Scope must release the prior observation before re-parking, so the count stays
        // flat at exactly 1 across all re-renders. The old transitive leak (a descendant fiber per re-render that the
        // one-level interrupt missed) would have the count climb past 1; here it never does.
        for
            outerRef <- Signal.initRef(true)
            childRef <- Signal.initRef("init")
            tree = UI.when(outerRef)(UI.div(childRef.map(s => UI.span(s))))
            live <- AtomicInt.init(0)
            fiber <- Fiber.initUnscoped(Scope.run {
                for
                    _    <- Scope.acquireRelease(live.incrementAndGet)(_ => live.decrementAndGet.unit)
                    root <- ReactiveUI.normalize(tree, Seq.empty)
                    _    <- ReactiveUI.subscribe(root, stubExchange)
                    _    <- assertEventually(childRef.waiters.map(_ == 1))
                    _    <- Async.never
                yield ()
            })
            _ <- assertEventually(live.get.map(_ == 1))
            // Re-render 5 times by setting the leaf to a fresh value; after each set the live child re-parks, so waiters
            // must settle back to exactly 1 (never climb). Each set clears the prior park's ghost, making waiters exact.
            _ <- Kyo.foreachDiscard(Chunk("r1", "r2", "r3", "r4", "r5")) { v =>
                childRef.set(v).andThen(assertEventually(childRef.waiters.map(_ == 1)))
            }
            max <- childRef.waiters
            _   <- fiber.interrupt
            _   <- assertEventually(live.get.map(_ == 0))
        yield assert(max == 1)
        end for
    }

    "nested grandchild released when the root Scope closes (transitive cascade)" in {
        // Three-level nesting: outer (UI.when) -> inner (UI.when) -> grandchild reactive over grandRef, all live while
        // outer and inner are true. Closing the root Scope must cascade through every level and release the grandchild's
        // observation: the old one-level interrupt missed grandchildren and left them live. This proves the transitive
        // depth of the cascade with public API only (no internal hook).
        //
        // The trigger is the root Scope closing (interrupting the holder fiber), the same reliable witness as the flat
        // "every leaf waiters == 0" leaf, lifted to depth 3. The root close is awaited via the test's own root `live`
        // finalizer (`live == 0` means every root finalizer ran, the cascade is complete), then grandRef is set exactly
        // once to clear the ghost: a dead grandchild never re-parks, so `waiters == 0`; a leaked grandchild re-parks on
        // the swapped promise and keeps `waiters == 1`. (A mid-session parent value change closing a per-value Scope is
        // covered by the "waiters flat across repeated re-renders" leaf; here we add the cascade's depth.)
        for
            outer    <- Signal.initRef(true)
            inner    <- Signal.initRef(true)
            grandRef <- Signal.initRef("v")
            tree = UI.when(outer)(UI.when(inner)(UI.div(grandRef.map(s => UI.span(s)))))
            live <- AtomicInt.init(0)
            fiber <- Fiber.initUnscoped(Scope.run {
                for
                    _    <- Scope.acquireRelease(live.incrementAndGet)(_ => live.decrementAndGet.unit)
                    root <- ReactiveUI.normalize(tree, Seq.empty)
                    _    <- ReactiveUI.subscribe(root, stubExchange)
                    _    <- assertEventually(grandRef.waiters.map(_ == 1))
                    _    <- Async.never
                yield ()
            })
            _ <- assertEventually(live.get.map(_ == 1))
            // The grandchild (three levels deep) is live: one parked leaf waiter.
            _ <- assertEventually(grandRef.waiters.map(_ == 1))
            // Close the root Scope; await the root finalizer so the cascade is provably complete before the single set.
            _ <- fiber.interrupt
            _ <- assertEventually(live.get.map(_ == 0))
            // Cascade complete: a single ghost-clearing set must leave the grandchild observation gone (dead, never
            // re-parks). A leaked grandchild would re-park and keep waiters == 1.
            _ <- grandRef.set("after-teardown")
            _ <- assertEventually(grandRef.waiters.map(_ == 0))
            w <- grandRef.waiters
        yield assert(w == 0)
        end for
    }

end ReactiveUITeardownTest

package kyo.internal

import java.util.concurrent.CopyOnWriteArrayList
import kyo.*

/** Direct-engine tests for node-scope supervision (no browser): a background fiber a mounted node forked with
  * `UI.fork` failing AFTER publication flips the node into its error routing (node `.onError`, then the
  * default error UI), first-failure-wins, teardown interrupts never flip, and the
  * fibers that do not participate (`Fiber.init`, `Fiber.initUnscoped`) leave the node alone. The engine is
  * driven via `ReactiveUI.normalize`/`subscribe` with a recording `UIExchange` (the MountedTest direct-test
  * idiom).
  */
class MountSupervisionTest extends kyo.test.Test[Any]:

    /** Records every emission's rendered HTML and completes marker promises on first match. */
    final private class Recording(markers: Dict[String, Fiber.Promise.Unsafe[String, Any]]):
        val emissions = new CopyOnWriteArrayList[String]()
        val exchange = new UIExchange:
            def onChange(path: Seq[String], changed: UI)(using Frame): Unit < Async =
                HtmlRenderer.render(changed, path).map { html =>
                    discard(emissions.add(html))
                    markers.foreach { (marker, p) =>
                        if html.contains(marker) then
                            import AllowUnsafe.embrace.danger
                            discard(p.complete(Result.succeed(html)))
                    }
                }
        def count(marker: String): Int =
            import scala.jdk.CollectionConverters.*
            emissions.asScala.count(_.contains(marker))
        def indexOf(marker: String): Int =
            import scala.jdk.CollectionConverters.*
            emissions.asScala.indexWhere(_.contains(marker))
    end Recording

    private def recording(markers: String*)(using Frame): (Recording, Dict[String, Fiber[String, Any]]) < Sync =
        Sync.Unsafe.defer {
            val ps  = markers.map(m => m -> Fiber.Promise.Unsafe.init[String, Any]()).toMap
            val rec = new Recording(Dict.from(ps))
            (rec, Dict.from(ps.map((m, p) => m -> (p.safe: Fiber[String, Any]))))
        }

    private def run(ui: UI, rec: Recording)(using Frame): Unit < (Async & Scope) =
        ReactiveUI.normalize(ui, Seq.empty).map(root => ReactiveUI.subscribe(root, rec.exchange).unit)

    private def boom = new RuntimeException("feed died")

    "a published keyed mount flips to its default error render when a background fiber fails" in {
        Scope.run {
            for
                gate         <- Promise.init[Unit, Any]
                (rec, marks) <- recording("ready", "feed died")
                effect = UI.fork(gate.get.andThen(Abort.fail(boom)))
                    .map(_ => UI.span("ready").id("content"))
                _ <- run(UI.div(UI.mounted(effect).keyed("k")), rec)
                _ <- marks("ready").get
                _ <- gate.completeUnitDiscard
                _ <- marks("feed died").get
            yield assert(rec.indexOf("ready") < rec.indexOf("feed died"))
        }
    }

    "a keyless mount flips too" in {
        Scope.run {
            for
                gate         <- Promise.init[Unit, Any]
                (rec, marks) <- recording("ready", "feed died")
                effect = UI.fork(gate.get.andThen(Abort.fail(boom)))
                    .map(_ => UI.span("ready").id("content"))
                _ <- run(UI.div(UI.mounted(effect)), rec)
                _ <- marks("ready").get
                _ <- gate.completeUnitDiscard
                _ <- marks("feed died").get
            yield assert(rec.count("feed died") >= 1)
        }
    }

    "a node-local .onError wins for a supervised failure" in {
        Scope.run {
            for
                gate         <- Promise.init[Unit, Any]
                (rec, marks) <- recording("ready", "custom:Fork:feed died")
                effect = UI.fork(gate.get.andThen(Abort.fail(boom)))
                    .map(_ => UI.span("ready").id("content"))
                node = UI.mounted(effect).keyed("k").onError(e => UI.span(s"custom:${e.source}:${e.message}"))
                _ <- run(UI.div(node), rec)
                _ <- marks("ready").get
                _ <- gate.completeUnitDiscard
                _ <- marks("custom:Fork:feed died").get
            yield assert(rec.count("custom:Fork:feed died") == 1)
        }
    }

    "first failure wins: two failing feeds, exactly one error paint" in {
        Scope.run {
            for
                gate1        <- Promise.init[Unit, Any]
                gate2        <- Promise.init[Unit, Any]
                (rec, marks) <- recording("ready", "kyo-mount-error")
                effect =
                    for
                        _ <- UI.fork(gate1.get.andThen(Abort.fail(new RuntimeException("first"))))
                        _ <- UI.fork(gate2.get.andThen(Abort.fail(new RuntimeException("second"))))
                    yield UI.span("ready").id("content")
                _ <- run(UI.div(UI.mounted(effect).keyed("k")), rec)
                _ <- marks("ready").get
                _ <- gate1.completeUnitDiscard
                _ <- marks("kyo-mount-error").get
                _ <- gate2.completeUnitDiscard
                _ <- Async.sleep(100L.millis)
            yield assert(rec.count("kyo-mount-error") == 1)
        }
    }

    "a supervised failure during pending is deferred until the effect settles, then flips" in {
        // The flip is serialized after publish, but the intermediate "ready" paint is not observable through the
        // coalescing content signal (observe keeps only the latest), so we assert: no error while pending, error after settle.
        Scope.run {
            for
                gate         <- Promise.init[Unit, Any]
                (rec, marks) <- recording("feed died")
                effect = UI.fork(Abort.fail(boom)) // fails while the effect is still pending
                    .map(_ => gate.get)
                    .map(_ => UI.span("ready").id("content"))
                _ <- run(UI.div(UI.mounted(effect).keyed("k")), rec)
                _ <- Async.sleep(100L.millis) // failure has long landed…
                noneYet = rec.count("kyo-mount-error") == 0 // …but nothing flipped while pending
                _ <- gate.completeUnitDiscard
                _ <- marks("feed died").get // flip arrives once the effect settled
            yield assert(noneYet)
        }
    }

    "an effect failure racing a supervised failure routes once (shared once-guard)" in {
        Scope.run {
            for
                (rec, _) <- recording()
                effect = UI.fork(Abort.fail(new RuntimeException("from fiber")))
                    .andThen(Abort.fail(new RuntimeException("from effect")))
                _ <- run(UI.div(UI.mounted(effect).keyed("k")), rec)
                _ <- Async.sleep(150L.millis)
            yield assert(rec.count("kyo-mount-error") == 1)
        }
    }

    "eviction: a key swap interrupts feeds without any error paint" in {
        Scope.run {
            for
                sel          <- Signal.initRef("a")
                (rec, marks) <- recording("inst:a", "inst:b")
                effect = (k: String) =>
                    UI.fork(Async.never).map(_ => UI.span(s"inst:$k").id("content"))
                ui = UI.div(sel.map(k => UI.div(UI.mounted(effect(k)).keyed(k))))
                _ <- run(ui, rec)
                _ <- marks("inst:a").get
                _ <- sel.set("b")
                _ <- marks("inst:b").get
                _ <- Async.sleep(100L.millis)
            yield assert(rec.count("kyo-mount-error") == 0)
        }
    }

    "a plain Fiber.init feed failure does NOT flip the node (supervision is opt-in)" in {
        Scope.run {
            for
                gate         <- Promise.init[Unit, Any]
                (rec, marks) <- recording("ready")
                effect = Fiber.init(gate.get.andThen(Abort.fail(boom)))
                    .map(_ => UI.span("ready").id("content"))
                _ <- run(UI.div(UI.mounted(effect).keyed("k")), rec)
                _ <- marks("ready").get
                _ <- gate.completeUnitDiscard
                _ <- Async.sleep(100L.millis)
            yield assert(rec.count("kyo-mount-error") == 0)
        }
    }

    "initUnscoped feed failure does NOT flip the node" in {
        Scope.run {
            for
                gate         <- Promise.init[Unit, Any]
                (rec, marks) <- recording("ready")
                effect = Fiber.initUnscoped(gate.get.andThen(Abort.fail(boom)))
                    .map(_ => UI.span("ready").id("content"))
                _ <- run(UI.div(UI.mounted(effect).keyed("k")), rec)
                _ <- marks("ready").get
                _ <- gate.completeUnitDiscard
                _ <- Async.sleep(100L.millis)
            yield assert(rec.count("kyo-mount-error") == 0)
        }
    }
end MountSupervisionTest

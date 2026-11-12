package kyo

import kyo.internal.HtmlRenderer
import kyo.internal.MountDispatch
import kyo.internal.MouseEventData
import kyo.internal.ReactiveRegion
import kyo.internal.ReactiveUI
import kyo.internal.UIEvent
import kyo.internal.UIExchange

/** Behavior of [[kyo.UI.mounted]] on the shared engine, without a browser: a tree is normalized and subscribed against an exchange that
  * records every paint, and events go straight to the subscription's handler.
  *
  * What a path shows is the last paint at that path or at one of its ancestors, since an ancestor's paint replaces everything below it.
  */
class MountedTest extends kyo.test.Test[Any]:

    import UI.*

    private class Recorder extends UIExchange:
        private val paints = AtomicRef.Unsafe.init(Chunk.empty[(Seq[String], String)])(using AllowUnsafe.embrace.danger)

        def onChange(
            region: ReactiveRegion,
            path: Seq[String],
            context: ReactiveRegion.RegionIdentity,
            parentContext: ReactiveRegion.ParentContext,
            previous: Maybe[UI],
            ui: UI
        )(using Frame): Unit < Async =
            HtmlRenderer.render(ui, path).map(html => Sync.Unsafe.defer(discard(paints.getAndUpdate(_.append((path, html))))))

        /** The initial page, which a transport renders itself before subscribing. */
        def initial(ui: UI)(using Frame): Unit < Sync =
            HtmlRenderer.render(ui, Seq.empty).map(html => Sync.Unsafe.defer(discard(paints.getAndUpdate(_.append((Seq.empty, html))))))

        def shown(path: Seq[String])(using Frame): String < Sync =
            Sync.Unsafe.defer(paints.get()).map { all =>
                all.reverseIterator.find((p, _) => path.startsWith(p)).map(_._2).getOrElse("")
            }

        def paintsAt(path: Seq[String])(using Frame): Int < Sync =
            Sync.Unsafe.defer(paints.get()).map(_.count((p, _) => p == path))

        def paintsEndingWith(suffix: Seq[String])(using Frame): Int < Sync =
            Sync.Unsafe.defer(paints.get()).map(_.count((p, _) => p.endsWith(suffix)))
    end Recorder

    private def click(path: String*): UIEvent = UIEvent.Click(path, MouseEventData(UI.Modifiers.none, Absent))

    private def mount[A](ui: UI)(f: (Recorder, (Seq[String], UIEvent) => Boolean < Async) => A < (Async & Scope))(using
        Frame
    ): A < Async =
        Scope.run {
            val recorder = new Recorder
            for
                _    <- recorder.initial(ui)
                root <- ReactiveUI.normalize(ui, Seq.empty)
                sub  <- ReactiveUI.subscribe(root, recorder)
                a    <- f(recorder, sub.handle)
            yield a
            end for
        }

    private def showsEventually(recorder: Recorder, path: Seq[String], text: String)(using Frame, kyo.test.AssertScope): Unit < Async =
        assertEventually(recorder.shown(path).map(_.contains(text)))

    "a mounted node shows its placeholder until the effect publishes, then the content" in {
        for
            gate <- Promise.init[Unit, Any]
            ui = div(UI.mounted(gate.get.andThen(span("ready"))).placeholder(span("loading")))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0"), "loading")
                    _ <- gate.completeUnitDiscard
                    _ <- showsEventually(recorder, Seq("0"), "ready")
                yield ()
            }
        yield succeed
    }

    "a keyless node gets a new instance whenever its region re-renders" in {
        for
            runs <- AtomicInt.init(0)
            tick <- Signal.initRef(0)
            ui = div(tick.render(_ => div(UI.mounted(runs.incrementAndGet.map(n => span(s"runs:$n"))))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "0"), "runs:1")
                    _ <- tick.set(1)
                    _ <- showsEventually(recorder, Seq("0", "0"), "runs:2")
                yield ()
            }
        yield succeed
    }

    "a keyed node keeps its instance while its region re-renders" in {
        for
            runs <- AtomicInt.init(0)
            tick <- Signal.initRef(0)
            ui = div(tick.render(n => div(span(s"tick:$n"), UI.mounted(runs.incrementAndGet.map(r => span(s"runs:$r"))).keyed("box"))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "1"), "runs:1")
                    _ <- tick.set(1)
                    _ <- showsEventually(recorder, Seq("0", "0"), "tick:1")
                    _ <- showsEventually(recorder, Seq("0", "1"), "runs:1")
                    _ <- tick.set(2)
                    _ <- showsEventually(recorder, Seq("0", "0"), "tick:2")
                    _ <- showsEventually(recorder, Seq("0", "1"), "runs:1")
                yield ()
            }
            total <- runs.get
        yield assert(total == 1)
    }

    /** Runs `change`, waits for the next paint at `path`, and returns what the path shows then. */
    private def afterRepaint(recorder: Recorder, path: Seq[String])(change: => Unit < Async)(using
        Frame,
        kyo.test.AssertScope
    ): String < Async =
        for
            before <- recorder.paintsAt(path)
            _      <- change
            _      <- assertEventually(recorder.paintsAt(path).map(_ > before))
            shown  <- recorder.shown(path)
        yield shown

    "a keyed node keeps its instance when an outer region re-renders the region around it" in {
        for
            runs  <- AtomicInt.init(0)
            outer <- Signal.initRef(0)
            inner <- Signal.initRef("x")
            node = UI.mounted(runs.incrementAndGet.map(r => span(s"runs:$r"))).keyed("box")
            ui   = div(outer.render(n => div(span(s"outer:$n"), inner.render(_ => div(node)))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _      <- showsEventually(recorder, Seq("0", "1", "0"), "runs:1")
                    first  <- afterRepaint(recorder, Seq("0", "1", "0"))(outer.set(1))
                    _      <- inner.set("y")
                    second <- afterRepaint(recorder, Seq("0", "1", "0"))(outer.set(2))
                    total  <- runs.get
                yield
                    assert(first.contains("runs:1") && second.contains("runs:1"), s"shown: $first / $second")
                    assert(total == 1, s"the instance was rebuilt by outer re-renders: $total runs")
            }
        yield succeed
    }

    "a keyless node gets a new instance when an outer region re-renders the region around it" in {
        for
            runs  <- AtomicInt.init(0)
            outer <- Signal.initRef(0)
            node = UI.mounted(runs.incrementAndGet.map(r => span(s"runs:$r")))
            ui   = div(outer.render(n => div(span(s"outer:$n"), outer.map(_ => "x").render(_ => div(node)))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _      <- showsEventually(recorder, Seq("0", "1", "0"), "runs:")
                    before <- runs.get
                    _      <- outer.set(1)
                    // The inner region may also render again on its own, so the check is a new run, shown, not an exact count.
                    _ <- assertEventually(runs.get.map(_ > before))
                    _ <- assertEventually(
                        runs.get.map(latest => recorder.shown(Seq("0", "1", "0")).map(_.contains(s"runs:$latest")))
                    )
                yield ()
            }
        yield succeed
    }

    "row keys deeper than 63 levels identify instances without taking positions along" in {
        for
            runs  <- AtomicInt.init(0)
            extra <- Signal.initRef(false)
            items = Signal.initConst(Chunk("a"))
            node  = UI.mounted(runs.incrementAndGet.map(r => span(s"runs:$r"))).keyed("row")
            row   = (show: Boolean) =>
                if show then UI.Ast.Div(children = Chunk(span("inserted"), node)) else UI.Ast.Div(children = Chunk(node))
            deep = (show: Boolean) =>
                (1 to 70).foldLeft[UI](items.foreachKeyed(identity)(_ => row(show)))((inner, _) => div(inner))
            ui = div(extra.render(deep))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- assertEventually(runs.get.map(_ == 1))
                    _ <- extra.set(true)
                    // The mount now sits second in its row; a paint there means it was subscribed again after the re-render.
                    _     <- assertEventually(recorder.paintsEndingWith(Seq("a", "1")).map(_ >= 1))
                    total <- runs.get
                yield assert(total == 1, s"a sibling deep inside a row rebuilt the instance: $total runs")
            }
        yield succeed
    }

    "a sibling inserted before the region around a keyed node leaves the instance in place" in {
        for
            runs  <- AtomicInt.init(0)
            outer <- Signal.initRef(false)
            inner <- Signal.initRef("x")
            node   = UI.mounted(runs.incrementAndGet.map(r => span(s"runs:$r"))).keyed("box")
            region = inner.render(_ => div(node))
            ui     = div(outer.render { extra =>
                if extra then UI.Ast.Div(children = Chunk(span("inserted"), region)) else UI.Ast.Div(children = Chunk(region))
            })
            _ <- mount(ui) { (recorder, _) =>
                for
                    _     <- showsEventually(recorder, Seq("0", "0", "0"), "runs:1")
                    shown <- afterRepaint(recorder, Seq("0", "1", "0"))(outer.set(true))
                    total <- runs.get
                yield
                    assert(shown.contains("runs:1"), s"shown: $shown")
                    assert(total == 1, s"the inserted sibling rebuilt the instance: $total runs")
            }
        yield succeed
    }

    "a keyed instance is released when the region around it goes away" in {
        for
            released <- AtomicBoolean.init(false)
            outer    <- Signal.initRef(true)
            inner    <- Signal.initRef("x")
            node = UI.mounted(Scope.ensure(released.set(true)).andThen(span("held"))).keyed("box")
            ui   = div(outer.render(show => if show then div(inner.render(_ => div(node))) else div(span("gone"))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "0", "0"), "held")
                    _ <- outer.set(false)
                    _ <- showsEventually(recorder, Seq("0"), "gone")
                    _ <- assertEventually(released.get)
                yield ()
            }
        yield succeed
    }

    "an outer re-render that interrupts a region while it releases its keyed instances still releases all of them" in {
        for
            entered  <- AtomicInt.init(0)
            released <- AtomicInt.init(0)
            gate     <- Promise.init[Unit, Any]
            outer    <- Signal.initRef(0)
            inner    <- Signal.initRef(true)
            node = (name: String) =>
                UI.mounted(
                    Scope.ensure(entered.incrementAndGet.andThen(gate.get).andThen(released.incrementAndGet.unit)).andThen(span(name))
                ).keyed(name)
            ui = div(outer.render(_ => div(inner.render(s => if s then div(node("mount-a"), node("mount-b")) else div(span("empty"))))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "0", "0"), "mount-a")
                    _ <- showsEventually(recorder, Seq("0", "0", "1"), "mount-b")
                    _ <- inner.set(false)
                    _ <- assertEventually(entered.get.map(_ == 1))
                    _ <- afterRepaint(recorder, Seq("0"))(outer.set(1))
                    _ <- gate.completeUnitDiscard
                    _ <- assertEventually(released.get.map(_ == 2))
                yield ()
            }
        yield succeed
    }

    private def mountedNodes(rui: ReactiveUI): Chunk[ReactiveUI] =
        Chunk.from(rui.mounted.fold(Seq.empty[ReactiveUI])(_ => Seq(rui)) ++ rui.children.flatMap(mountedNodes))

    // An outer re-render stops the old subscription of a region only once the outer value's scope has drained, while the new
    // subscription of that region already works on the same table. The table follows the newest subscription; the older one, still
    // running a pass, must neither release nor record anything in it.
    "a region's newer subscription owns its mount table, and an older one still running leaves the table alone" in {
        Scope.run {
            for
                released <- AtomicBoolean.init(false)
                root <- ReactiveUI.normalize(div(UI.mounted(Scope.ensure(released.set(true)).andThen(span("held"))).keyed("k")), Seq.empty)
                node     = mountedNodes(root).head
                spec     = node.mounted.get
                identity = spec.identity.get
                outer    = ReactiveUI.MountTableRef.init()
                pass     = ReactiveUI.RegionKeys(Seq(root))
                older    = outer.child(pass, root)
                _     <- older.beginWalk(Seq(node), ReactiveUI.RegionKeys(Seq(node)))
                first <- older.claim(node, identity, spec)
                _     <- older.endWalk
                _     <- assertEventually(first.cell.current.map(_.isInstanceOf[ReactiveUI.MountState.Ready]))
                newer = outer.child(pass, root)
                _     <- newer.beginWalk(Seq(node), ReactiveUI.RegionKeys(Seq(node)))
                kept  <- newer.claim(node, identity, spec)
                _     <- older.beginWalk(Seq.empty, ReactiveUI.RegionKeys.empty)
                _     <- older.endWalk
                stale <- older.claim(node, identity, spec)
                _     <- newer.endWalk
                gone  <- released.get
                _     <- newer.beginWalk(Seq(node), ReactiveUI.RegionKeys(Seq(node)))
                again <- newer.claim(node, identity, spec)
                _     <- newer.endWalk
                _     <- outer.evictAll
            yield
                assert(kept eq first, "the newer subscription did not find the kept instance")
                assert(!gone, "the older subscription released the instance the newer one shows")
                assert(stale eq first, "the older subscription's claim did not reuse the live instance")
                assert(again eq first, "the table lost the instance after the older subscription's pass")
        }
    }

    "an event registration that ends leaves a later one at the same path in place" in {
        for
            dispatch <- MountDispatch.init
            cell     <- Signal.initRef[ReactiveUI.MountState](ReactiveUI.MountState.Pending(Absent))
            path = Seq("0", "1")
            _     <- dispatch.register(path, cell)
            _     <- dispatch.register(path, cell)
            _     <- dispatch.unregister(path, cell)
            found <- dispatch.lookup(path)
        yield assert(found.exists(_ eq cell))
    }

    "siblings appearing before a keyed node do not change its identity" in {
        for
            runs  <- AtomicInt.init(0)
            extra <- Signal.initRef(false)
            node = UI.mounted(runs.incrementAndGet.map(r => span(s"runs:$r"))).keyed("box")
            ui   = div(extra.render { show =>
                val before = if show then Chunk[UI](span("first"), span("second")) else Chunk.empty[UI]
                UI.Ast.Div(children = before.append(node))
            })
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "0"), "runs:1")
                    _ <- extra.set(true)
                    _ <- showsEventually(recorder, Seq("0", "2"), "runs:1")
                    _ <- extra.set(false)
                    _ <- showsEventually(recorder, Seq("0", "0"), "runs:1")
                yield ()
            }
            total <- runs.get
        yield assert(total == 1)
    }

    "two call sites at the same position with the same key are separate instances" in {
        for
            aRuns  <- AtomicInt.init(0)
            bRuns  <- AtomicInt.init(0)
            choice <- Signal.initRef(true)
            ui = div(choice.render { a =>
                if a then div(UI.mounted(aRuns.incrementAndGet.map(n => span(s"a:$n"))).keyed(1))
                else div(UI.mounted(bRuns.incrementAndGet.map(n => span(s"b:$n"))).keyed(1))
            })
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "0"), "a:1")
                    _ <- choice.set(false)
                    _ <- showsEventually(recorder, Seq("0", "0"), "b:1")
                    _ <- choice.set(true)
                    _ <- showsEventually(recorder, Seq("0", "0"), "a:2")
                yield ()
            }
        yield succeed
    }

    // The helpers below take a `(using Frame)` the way application code does, so every mount they build gets the same frame.

    private def counted(runs: AtomicRef[Map[String, Int]], name: String)(using Frame): UI < Sync =
        runs.updateAndGet(m => m.updated(name, m.getOrElse(name, 0) + 1)).map(m => span(s"$name:${m(name)}"))

    private def panels(id: String, saveFirst: Boolean, runs: AtomicRef[Map[String, Int]])(using Frame): UI =
        val cover = UI.mounted(counted(runs, "cover")).keyed(id)
        val save  = UI.mounted(counted(runs, "save")).keyed(id)
        div(if saveFirst then save else cover, if saveFirst then cover else save)
    end panels

    "mounts built under one passed-down Frame are told apart by where .keyed is written" in {
        for
            runs      <- AtomicRef.init(Map.empty[String, Int])
            saveFirst <- Signal.initRef(false)
            ui = div(saveFirst.render(first => panels("album-1", first, runs)))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "0"), "cover:1")
                    _ <- showsEventually(recorder, Seq("0", "1"), "save:1")
                    _ <- saveFirst.set(true)
                    _ <- showsEventually(recorder, Seq("0", "0"), "save:1")
                    _ <- showsEventually(recorder, Seq("0", "1"), "cover:1")
                yield ()
            }
            counts <- runs.get
        yield assert(counts == Map("cover" -> 1, "save" -> 1), counts.toString)
    }

    private def tile(ref: String, runs: AtomicInt, released: AtomicInt)(using Frame): UI =
        UI.mounted(Scope.ensure(released.incrementAndGet.unit).andThen(runs.incrementAndGet.map(n => span(s"$ref:$n")))).keyed(ref)

    "the same keyed tile shown twice in one region gives each slot its own instance" in {
        for
            runs     <- AtomicInt.init(0)
            released <- AtomicInt.init(0)
            tick     <- Signal.initRef(0)
            ui = div(tick.render(_ => UI.Ast.Div(children = Chunk("x", "y", "x").map(tile(_, runs, released)))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _       <- assertEventually(runs.get.map(_ == 3))
                    _       <- showsEventually(recorder, Seq("0", "0"), "x:")
                    _       <- showsEventually(recorder, Seq("0", "2"), "x:")
                    first   <- recorder.shown(Seq("0", "0"))
                    second  <- recorder.shown(Seq("0", "2"))
                    _       <- afterRepaint(recorder, Seq("0"))(tick.set(1))
                    _       <- showsEventually(recorder, Seq("0", "0"), first)
                    _       <- showsEventually(recorder, Seq("0", "2"), second)
                    total   <- runs.get
                    dropped <- released.get
                yield
                    assert(first != second, s"both slots show $first")
                    assert(total == 3 && dropped == 0, s"runs: $total, released: $dropped")
            }
        yield succeed
    }

    "a duplicate that goes away leaves the other instances where they are" in {
        for
            runs     <- AtomicInt.init(0)
            released <- AtomicInt.init(0)
            show     <- Signal.initRef(true)
            ui = div(show.render { s =>
                UI.Ast.Div(children = Chunk("x", "y", "x").zipWithIndex.map { (ref, i) =>
                    if i == 0 && !s then UI.empty else tile(ref, runs, released)
                })
            })
            _ <- mount(ui) { (recorder, _) =>
                for
                    _      <- assertEventually(runs.get.map(_ == 3))
                    _      <- showsEventually(recorder, Seq("0", "2"), "x:")
                    before <- recorder.shown(Seq("0", "2"))
                    _      <- afterRepaint(recorder, Seq("0"))(show.set(false))
                    _      <- assertEventually(released.get.map(_ == 1))
                    _      <- showsEventually(recorder, Seq("0", "2"), "x:")
                    after  <- recorder.shown(Seq("0", "2"))
                    total  <- runs.get
                yield assert(after == before && total == 3, s"before: $before, after: $after, runs: $total")
            }
        yield succeed
    }

    "duplicates whose places all shift keep their instances" in {
        for
            runs     <- AtomicInt.init(0)
            released <- AtomicInt.init(0)
            lead     <- Signal.initRef(false)
            ui = div(lead.render { l =>
                UI.Ast.Div(children = Chunk.from((if l then Seq(span("lead"))
                                                  else Seq.empty) ++ Seq("x", "x").map(tile(_, runs, released))))
            })
            _ <- mount(ui) { (recorder, _) =>
                for
                    _       <- assertEventually(runs.get.map(_ == 2))
                    _       <- afterRepaint(recorder, Seq("0"))(lead.set(true))
                    _       <- showsEventually(recorder, Seq("0", "1"), "x:")
                    _       <- showsEventually(recorder, Seq("0", "2"), "x:")
                    total   <- runs.get
                    dropped <- released.get
                yield assert(total == 2 && dropped == 0, s"runs: $total, released: $dropped")
            }
        yield succeed
    }

    private def editorOrViewer(editing: Boolean, id: String, runs: AtomicRef[Map[String, Int]])(using Frame): UI =
        if editing then UI.mounted(counted(runs, "editor")).keyed(id)
        else UI.mounted(counted(runs, "viewer")).keyed(id)

    "two .keyed sites in one helper at one position are separate instances" in {
        for
            runs    <- AtomicRef.init(Map.empty[String, Int])
            editing <- Signal.initRef(false)
            ui = div(editing.render(e => div(editorOrViewer(e, "doc-1", runs))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "0"), "viewer:1")
                    _ <- editing.set(true)
                    _ <- showsEventually(recorder, Seq("0", "0"), "editor:1")
                    _ <- editing.set(false)
                    _ <- showsEventually(recorder, Seq("0", "0"), "viewer:2")
                yield ()
            }
        yield succeed
    }

    private def card(name: String, runs: AtomicRef[Map[String, Int]])(using Frame, UI.MountSite): UI =
        UI.mounted(counted(runs, name)).keyed("card")

    "a helper that takes the MountSite makes each of its callers a place of its own" in {
        for
            runs   <- AtomicRef.init(Map.empty[String, Int])
            choice <- Signal.initRef(true)
            ui = div(choice.render(a => div(if a then card("a", runs) else card("b", runs))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "0"), "a:1")
                    _ <- choice.set(false)
                    _ <- showsEventually(recorder, Seq("0", "0"), "b:1")
                    _ <- choice.set(true)
                    _ <- showsEventually(recorder, Seq("0", "0"), "a:2")
                yield ()
            }
        yield succeed
    }

    "rows of a keyed list keep separate instances of the same keyed node across list changes" in {
        for
            runs  <- AtomicRef.init(Map.empty[String, Int])
            items <- Signal.initRef(Chunk("a", "b"))
            row = (id: String) =>
                UI.mounted(runs.updateAndGet(m => m.updated(id, m.getOrElse(id, 0) + 1)).map(m => span(s"$id:${m(id)}")))
                    .keyed("row")
            ui = div(items.foreachKeyed(identity)(id => div(row(id))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "a", "0"), "a:1")
                    _ <- showsEventually(recorder, Seq("0", "b", "0"), "b:1")
                    _ <- items.set(Chunk("c", "a", "b"))
                    _ <- showsEventually(recorder, Seq("0", "c", "0"), "c:1")
                    _ <- showsEventually(recorder, Seq("0", "a", "0"), "a:1")
                    _ <- showsEventually(recorder, Seq("0", "b", "0"), "b:1")
                yield ()
            }
            counts <- runs.get
        yield assert(counts == Map("a" -> 1, "b" -> 1, "c" -> 1))
    }

    "the same call site shown twice with one key gives each slot its own instance" in {
        for
            runs <- AtomicInt.init(0)
            ui = UI.Ast.Div(children =
                Chunk.from(Seq(1, 2).map(_ => UI.mounted(runs.incrementAndGet.map(n => span(s"runs:$n"))).keyed("same")))
            )
            _ <- mount(ui) { (recorder, _) =>
                for
                    _      <- showsEventually(recorder, Seq("0"), "runs:")
                    _      <- showsEventually(recorder, Seq("1"), "runs:")
                    first  <- recorder.shown(Seq("0"))
                    second <- recorder.shown(Seq("1"))
                yield assert(first != second, s"both slots show $first")
            }
            total <- runs.get
        yield assert(total == 2)
    }

    "a key change releases the old instance completely before the new effect starts" in {
        for
            key               <- Signal.initRef("a")
            releasing         <- Promise.init[Unit, Any]
            finishRelease     <- Promise.init[Unit, Any]
            released          <- AtomicBoolean.init(false)
            sawReleaseAtStart <- Promise.init[Boolean, Any]
            effect = (k: String) =>
                if k == "a" then
                    Scope.ensure(releasing.completeUnitDiscard.andThen(finishRelease.get).andThen(released.set(true)))
                        .andThen(span("a"))
                else released.get.map(r => sawReleaseAtStart.completeDiscard(Result.succeed(r))).andThen(span("b"))
            ui = div(key.render(k => div(UI.mounted(effect(k)).keyed(k))))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _        <- showsEventually(recorder, Seq("0", "0"), ">a<")
                    _        <- key.set("b")
                    _        <- releasing.get
                    started  <- sawReleaseAtStart.done
                    _        <- finishRelease.completeUnitDiscard
                    observed <- sawReleaseAtStart.get
                    _        <- showsEventually(recorder, Seq("0", "0"), ">b<")
                yield
                    assert(!started)
                    assert(observed)
            }
        yield succeed
    }

    "while a new instance is pending, KeepLatest shows an inert snapshot of the old content" in {
        for
            key        <- Signal.initRef("a")
            gate       <- Promise.init[Unit, Any]
            nestedRuns <- AtomicInt.init(0)
            clicks     <- AtomicInt.init(0)
            effect = (k: String) =>
                if k == "a" then
                    Kyo.lift(div(
                        span("old"),
                        button("hit").onClick(clicks.incrementAndGet.unit),
                        UI.mounted(nestedRuns.incrementAndGet.map(n => span(s"nested:$n")))
                    ))
                else gate.get.andThen(span("new"))
            ui = div(key.render(k => div(UI.mounted(effect(k)).keyed(k))))
            _ <- mount(ui) { (recorder, handle) =>
                for
                    _      <- showsEventually(recorder, Seq("0", "0", "2"), "nested:1")
                    _      <- handle(Seq("0", "0", "1"), click("0", "0", "1"))
                    _      <- assertEventually(clicks.get.map(_ == 1))
                    before <- recorder.paintsAt(Seq("0"))
                    _      <- key.set("b")
                    _      <- assertEventually(recorder.paintsAt(Seq("0")).map(_ > before))
                    _      <- showsEventually(recorder, Seq("0", "0"), "old")
                    _      <- handle(Seq("0", "0", "1"), click("0", "0", "1"))
                    _      <- gate.completeUnitDiscard
                    _      <- showsEventually(recorder, Seq("0", "0"), "new")
                    n      <- nestedRuns.get
                    c      <- clicks.get
                yield
                    assert(n == 1, s"the snapshot re-ran a nested mount: $n runs")
                    assert(c == 1, s"a click on the snapshot reached the old handler: $c clicks")
            }
        yield succeed
    }

    "while a new instance is pending, Placeholder shows the placeholder" in {
        for
            key  <- Signal.initRef("a")
            gate <- Promise.init[Unit, Any]
            effect = (k: String) => if k == "a" then Kyo.lift(span("old")) else gate.get.andThen(span("new"))
            ui     = div(key.render(k =>
                div(UI.mounted(effect(k)).keyed(k).placeholder(span("waiting")).whilePending(PendingPolicy.Placeholder))
            ))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _   <- showsEventually(recorder, Seq("0", "0"), "old")
                    _   <- key.set("b")
                    _   <- showsEventually(recorder, Seq("0", "0"), "waiting")
                    now <- recorder.shown(Seq("0", "0"))
                    _   <- gate.completeUnitDiscard
                    _   <- showsEventually(recorder, Seq("0", "0"), "new")
                yield assert(!now.contains("old"))
            }
        yield succeed
    }

    "a failed effect renders onError and the enclosing region keeps working" in {
        for
            tick <- Signal.initRef(0)
            ui = div(tick.render(n =>
                div(
                    span(s"tick:$n"),
                    UI.mounted(Abort.fail(new Exception("boom")))
                        .onError(e => span(s"error:${e.message}:${e.source}"))
                )
            ))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "1"), "error:boom:Effect")
                    _ <- tick.set(1)
                    _ <- showsEventually(recorder, Seq("0", "0"), "tick:1")
                yield ()
            }
        yield succeed
    }

    "a UI.fork fiber that fails after the node published switches it to its error" in {
        for
            feed <- Promise.init[Unit, Any]
            effect = UI.fork(feed.get.andThen(Abort.fail(new Exception("feed died")))).andThen(span("content"))
            ui     = div(UI.mounted(effect).onError(e => span(s"error:${e.message}:${e.source}")))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0"), "content")
                    _ <- feed.completeUnitDiscard
                    _ <- showsEventually(recorder, Seq("0"), "error:feed died:Fork")
                yield ()
            }
        yield succeed
    }

    "a failure is final: a later publish does not replace it" in {
        val failed = ReactiveUI.MountState.Failed(UI.Ast.Text("first")(using Frame.internal))
        val after  = ReactiveUI.MountSupervision.publish(UI.Ast.Text("late")(using Frame.internal))(failed)
        assert(after == failed)
    }

    "a fork failure is not overwritten when the effect publishes afterwards" in {
        for
            effectDone <- Promise.init[Unit, Any]
            effect =
                for
                    fiber <- UI.fork(Abort.fail(new Exception("early")))
                    _     <- fiber.getResult
                    _     <- effectDone.completeUnitDiscard
                yield span("late content")
            ui = div(UI.mounted(effect).onError(e => span(s"error:${e.message}")))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _     <- showsEventually(recorder, Seq("0"), "error:early")
                    _     <- effectDone.get
                    shown <- recorder.shown(Seq("0"))
                yield assert(!shown.contains("late content"))
            }
        yield succeed
    }

    "a plain Fiber.init failure does not switch the node" in {
        for
            done <- Promise.init[Unit, Any]
            effect =
                for
                    fiber <- Fiber.init(Abort.fail(new Exception("ignored")))
                    _     <- fiber.getResult
                    _     <- done.completeUnitDiscard
                yield span("content")
            ui = div(UI.mounted(effect).onError(e => span(s"error:${e.message}")))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _     <- done.get
                    _     <- showsEventually(recorder, Seq("0"), "content")
                    shown <- recorder.shown(Seq("0"))
                yield assert(!shown.contains("error"))
            }
        yield succeed
    }

    "a handler inside the content of a kept instance runs after its region re-renders" in {
        for
            tick   <- Signal.initRef(0)
            clicks <- AtomicInt.init(0)
            ui = div(tick.render(n =>
                div(span(s"tick:$n"), UI.mounted(Kyo.lift(div(button("hit").onClick(clicks.incrementAndGet.unit)))).keyed("k"))
            ))
            _ <- mount(ui) { (recorder, handle) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "1"), "hit")
                    _ <- tick.set(1)
                    _ <- showsEventually(recorder, Seq("0", "0"), "tick:1")
                    _ <- showsEventually(recorder, Seq("0", "1"), "hit")
                    _ <- handle(Seq("0", "1", "0"), click("0", "1", "0"))
                    _ <- assertEventually(clicks.get.map(_ == 1))
                yield ()
            }
        yield succeed
    }

    "a keyed node nested in a kept instance survives the outer region's re-renders" in {
        for
            tick      <- Signal.initRef(0)
            innerRuns <- AtomicInt.init(0)
            inner = UI.mounted(innerRuns.incrementAndGet.map(n => span(s"inner:$n"))).keyed("inner")
            outer = UI.mounted(Kyo.lift(div(inner))).keyed("outer")
            ui    = div(tick.render(n => div(span(s"tick:$n"), outer)))
            _ <- mount(ui) { (recorder, _) =>
                for
                    _ <- showsEventually(recorder, Seq("0", "1", "0"), "inner:1")
                    _ <- tick.set(1)
                    _ <- showsEventually(recorder, Seq("0", "0"), "tick:1")
                    _ <- showsEventually(recorder, Seq("0", "1", "0"), "inner:1")
                yield ()
            }
            total <- innerRuns.get
        yield assert(total == 1)
    }

    "a button inside mounted content submits the form around the mount" in {
        for
            submits <- AtomicInt.init(0)
            ui = UI.form.onSubmit(submits.incrementAndGet.unit)(UI.mounted(Kyo.lift(div(button("send")))))
            _ <- mount(ui) { (recorder, handle) =>
                for
                    _ <- showsEventually(recorder, Seq("0"), "send")
                    _ <- handle(Seq("0", "0"), click("0", "0"))
                    _ <- assertEventually(submits.get.map(_ == 1))
                yield ()
            }
        yield succeed
    }

    "a disabled button inside mounted content does not submit the form around the mount" in {
        for
            submits <- AtomicInt.init(0)
            ui = UI.form.onSubmit(submits.incrementAndGet.unit)(UI.mounted(Kyo.lift(div(button("send").disabled(true)))))
            _ <- mount(ui) { (recorder, handle) =>
                for
                    _ <- showsEventually(recorder, Seq("0"), "send")
                    _ <- handle(Seq("0", "0"), click("0", "0"))
                    n <- submits.get
                yield assert(n == 0)
            }
        yield succeed
    }

    "closing the subscription releases what a keyed instance acquired" in {
        for
            released <- AtomicBoolean.init(false)
            ui = div(UI.mounted(Scope.ensure(released.set(true)).andThen(span("held"))).keyed("k"))
            _ <- mount(ui)((recorder, _) => showsEventually(recorder, Seq("0"), "held"))
            r <- released.get
        yield assert(r)
    }

end MountedTest

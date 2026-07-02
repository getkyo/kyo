package kyo

import kyo.Browser.*
import kyo.internal.HtmlOp
import kyo.internal.KeyboardEventData
import kyo.internal.MouseEventData
import kyo.internal.ReactiveUI
import kyo.internal.RegionKind
import kyo.internal.UIEvent
import kyo.internal.UIExchange

/** A trivial in-test backend node (no 3D dependency): the closest live precedent is `UI.Ast.Host`
  * (`UI.scala:1710-1726`). Only `placeholder` needs a body; `backend`/`backendChildren`/`boundProps`/
  * `structuralRegion` are satisfied directly by the case-class constructor params (widening the
  * trait's `private[kyo]` members, exactly as `Host` widens them via overriding `def`s).
  */
final case class FakeBackendNode(
    backend: String = "fake",
    backendChildren: Chunk[UI] = Chunk.empty,
    boundProps: Chunk[UI.Ast.BackendNode.BoundProp] = Chunk.empty,
    override val structuralRegion: Maybe[UI.Ast.BackendNode.StructuralBinding] = Absent
)(using val frame: Frame) extends UI.Ast.BackendNode:
    type Self = FakeBackendNode
    private[kyo] def placeholder: UI.Ast.BackendNode.Placeholder =
        UI.Ast.BackendNode.Placeholder("canvas", UI.Ast.Attrs())
    // Not exercised by this file's leaves (a self-return is enough to satisfy the abstract obligation).
    def id(v: String): FakeBackendNode = this
end FakeBackendNode

class ReactiveUITest extends UITest:

    // ---- HtmlOp construction ----

    "HtmlOp.Replace carries path and html" in {
        val op = HtmlOp.Replace(Seq("0", "1"), "<span>hello</span>")
        assert(op.path == Seq("0", "1"))
        assert(op.html == "<span>hello</span>")
    }

    "HtmlOp.Remove carries path" in {
        val op = HtmlOp.Remove(Seq("root", "child"))
        assert(op.path == Seq("root", "child"))
    }

    "HtmlOp.InjectCss carries css string" in {
        val op = HtmlOp.InjectCss(".foo { color: red; }")
        assert(op.css == ".foo { color: red; }")
    }

    // ---- UIEvent construction ----

    "UIEvent.Click carries path" in {
        val ev = UIEvent.Click(Seq("0"), MouseEventData(UI.Modifiers.none, Absent))
        assert(ev.path == Seq("0"))
    }

    "UIEvent.Input carries path and value" in {
        val ev = UIEvent.Input(Seq("0", "1"), "hello")
        ev match
            case UIEvent.Input(path, value) =>
                assert(path == Seq("0", "1"))
                assert(value == "hello")
            case _ => fail("expected Input")
        end match
    }

    "UIEvent.Change carries path and value" in {
        val ev = UIEvent.Change(Seq("root"), "changed")
        ev match
            case UIEvent.Change(_, value) => assert(value == "changed")
            case _                        => fail("expected Change")
    }

    "UIEvent.ChangeChecked carries checked boolean" in {
        val ev = UIEvent.ChangeChecked(Seq("0"), checked = true)
        ev match
            case UIEvent.ChangeChecked(_, checked) => assert(checked)
            case _                                 => fail("expected ChangeChecked")
    }

    "UIEvent.KeyDown carries keyboard data" in {
        val kbData = KeyboardEventData("Enter", UI.Modifiers(ctrl = false, alt = false, shift = true, meta = false), Absent)
        val ev     = UIEvent.KeyDown(Seq("0"), kbData)
        ev match
            case UIEvent.KeyDown(_, keyboard) =>
                assert(keyboard.key == "Enter")
                assert(keyboard.modifiers.shift)
                assert(!keyboard.modifiers.ctrl)
            case _ => fail("expected KeyDown")
        end match
    }

    "UIEvent.Submit carries path" in {
        val ev = UIEvent.Submit(Seq("form"), MouseEventData(UI.Modifiers.none, Absent))
        assert(ev.path == Seq("form"))
    }

    "UIEvent.Focus carries path" in {
        val ev = UIEvent.Focus(Seq("input"), MouseEventData(UI.Modifiers.none, Absent))
        assert(ev.path == Seq("input"))
    }

    // ---- BackendNode reactive descent ----

    "a BackendNode's boundProp normalizes to a PropRegion at path :+ key" in {
        for
            ref <- Signal.initRef(0)
            node = FakeBackendNode(boundProps =
                Chunk(UI.Ast.BackendNode.BoundProp(
                    "material.color",
                    ref.asInstanceOf[Signal[Any]],
                    v => v.toString
                ))
            )
            root <- ReactiveUI.normalize(UI.div(node), Seq.empty)
        yield
            val region = ReactiveUI.findNode(root, Seq("0", "material.color"))
            assert(region.isDefined)
            assert(region.get.regionKind.isInstanceOf[RegionKind.Prop])
            assert(region.get.path == Seq("0", "material.color"))
    }

    // A BackendNode's own top-level region is marked isConst = true even though it has a nonempty
    // boundProps (ReactiveUI.scala, the normalize BackendNode arm): the node's own signal is
    // Signal.initConst (never changes), and subscribeScoped walks `children` regardless of isConst,
    // so the propRegions are subscribed either way. Before that fix, isConst was `false` whenever
    // boundProps was nonempty, which made subscribeScoped `observe` a signal that can never emit a
    // second value; the resulting one-shot renderValue call re-walked and re-subscribed a fresh copy
    // of the whole subtree, forking without bound. This leaf drives TWO sequential signal changes and
    // counts every emission via an AtomicInt incremented inside the exchange, so it fails two distinct
    // ways a regression could hide: silent non-propagation (a `channel.take` would hang, caught by the
    // harness timeout) AND a duplicate/leaked re-subscription (the counter would race ahead of the
    // number of values actually consumed from the channel).
    "driving a boundProp signal emits exactly one SetProp per change, with no duplicate re-subscription" in {
        for
            ref     <- Signal.initRef(0)
            counter <- AtomicInt.init
            node = FakeBackendNode(boundProps =
                Chunk(UI.Ast.BackendNode.BoundProp(
                    "material.color",
                    ref.asInstanceOf[Signal[Any]],
                    v => v.toString
                ))
            )
            root    <- ReactiveUI.normalize(UI.div(node), Seq.empty)
            channel <- Channel.init[(ReactiveUI.Region.PropRegion, Any)](8)
            exchange = new UIExchange:
                def onChange(region: ReactiveUI.Region, value: Any)(using Frame): Unit < Async =
                    region match
                        case prop: ReactiveUI.Region.PropRegion =>
                            counter.incrementAndGet.andThen(Abort.runPartial[Closed](channel.put((prop, value))).unit)
                        case _ => Kyo.unit
            result <- Scope.run {
                for
                    _                        <- ReactiveUI.subscribe(root, exchange)
                    (propInit, initialValue) <- channel.take // the immediate emission at subscribe time (value 0)
                    countAfterInit           <- counter.get
                    _                        <- ref.set(255)
                    (prop1, value1)          <- channel.take
                    countAfterFirst          <- counter.get
                    _                        <- ref.set(99)
                    (prop2, value2)          <- channel.take
                    countAfterSecond         <- counter.get
                yield (
                    propInit.encode(initialValue),
                    countAfterInit,
                    prop1.encode(value1),
                    countAfterFirst,
                    prop2.encode(value2),
                    countAfterSecond
                )
            }
        yield
            val (initialEncoded, countAfterInit, encoded1, countAfterFirst, encoded2, countAfterSecond) = result
            assert(initialEncoded == "0")
            assert(countAfterInit == 1)
            assert(encoded1 == "255")
            assert(countAfterFirst == 2)
            assert(encoded2 == "99")
            assert(countAfterSecond == 3)
    }

    // The real path descends TWO BackendNode levels (Three.Ast.Embed -> its scene root -> a
    // material.color boundProp), not one; nothing above exercises nested-const descent past depth 1.
    // A `FakeBackendNode` nesting a second `FakeBackendNode` in its `backendChildren` reproduces the
    // same shape with no 3D dependency.
    "a nested BackendNode's boundProp (depth 2) normalizes to path :+ childIndex :+ key, and drives exactly one SetProp per change" in {
        for
            ref     <- Signal.initRef(0)
            counter <- AtomicInt.init
            inner = FakeBackendNode(boundProps =
                Chunk(UI.Ast.BackendNode.BoundProp("material.color", ref.asInstanceOf[Signal[Any]], v => v.toString))
            )
            outer = FakeBackendNode(backendChildren = Chunk(inner))
            root    <- ReactiveUI.normalize(UI.div(outer), Seq.empty)
            channel <- Channel.init[(ReactiveUI.Region.PropRegion, Any)](8)
            exchange = new UIExchange:
                def onChange(region: ReactiveUI.Region, value: Any)(using Frame): Unit < Async =
                    region match
                        case prop: ReactiveUI.Region.PropRegion =>
                            counter.incrementAndGet.andThen(Abort.runPartial[Closed](channel.put((prop, value))).unit)
                        case _ => Kyo.unit
            region = ReactiveUI.findNode(root, Seq("0", "0", "material.color"))
            _      = assert(region.isDefined)
            _      = assert(region.get.regionKind.isInstanceOf[RegionKind.Prop])
            _      = assert(region.get.path == Seq("0", "0", "material.color"))
            result <- Scope.run {
                for
                    _               <- ReactiveUI.subscribe(root, exchange)
                    (_, _)          <- channel.take // the immediate emission at subscribe time
                    countAfterInit  <- counter.get
                    _               <- ref.set(255)
                    (prop1, v1)     <- channel.take
                    countAfterFirst <- counter.get
                yield (prop1.encode(v1), countAfterInit, countAfterFirst)
            }
        yield
            val (encoded1, countAfterInit, countAfterFirst) = result
            assert(countAfterInit == 1)
            assert(encoded1 == "255")
            assert(countAfterFirst == 2)
    }

    // The structural analog of the depth-1 leaf above: a BackendNode's structuralRegion (a
    // render/foreach/foreachKeyed carrier) normalizes to a region at the node's OWN path (path-
    // transparent, not path :+ key), and driving its signal emits exactly one StructuralRegion per
    // change. The FIRST executable proof the generic ReactiveUI walk discovers and drives a structural
    // region at all. `findNode` matches path-equality depth-first
    // on the CALLER'S root, so it returns the node's own const carrier (regionKind = Dom by default,
    // sharing the SAME path); the structural region is a same-path CHILD of that carrier, found by
    // scanning its `children` for a `RegionKind.Structural` entry, not by a second `findNode` call.
    "a BackendNode's structuralRegion normalizes to a StructuralRegion at the node's OWN path, and drives exactly one ReplaceSubtree per change" in {
        for
            chunkSignal <- Signal.initRef(Chunk(1, 2, 3))
            counter     <- AtomicInt.init
            encode = (v: Any) => v.asInstanceOf[Chunk[Int]].mkString(",")
            node = FakeBackendNode(structuralRegion =
                Present(UI.Ast.BackendNode.StructuralBinding(chunkSignal.asInstanceOf[Signal[Any]], encode))
            )
            root    <- ReactiveUI.normalize(UI.div(node), Seq.empty)
            channel <- Channel.init[(ReactiveUI.Region.StructuralRegion, Any)](8)
            exchange = new UIExchange:
                def onChange(region: ReactiveUI.Region, value: Any)(using Frame): Unit < Async =
                    region match
                        case s: ReactiveUI.Region.StructuralRegion =>
                            counter.incrementAndGet.andThen(Abort.runPartial[Closed](channel.put((s, value))).unit)
                        case _ => Kyo.unit
            carrier = ReactiveUI.findNode(root, Seq("0"))
            _       = assert(carrier.isDefined)
            _       = assert(carrier.get.children.exists(c => c.path == Seq("0") && c.regionKind.isInstanceOf[RegionKind.Structural]))
            result <- Scope.run {
                for
                    _                   <- ReactiveUI.subscribe(root, exchange)
                    (initOp, initValue) <- channel.take // the immediate emission at subscribe time
                    countAfterInit      <- counter.get
                    _                   <- chunkSignal.set(Chunk(4, 5))
                    (op1, v1)           <- channel.take
                    countAfterFirst     <- counter.get
                yield (initOp.path, initOp.encode(initValue), countAfterInit, op1.encode(v1), countAfterFirst)
            }
        yield
            val (initPath, initEncoded, countAfterInit, encoded1, countAfterFirst) = result
            assert(initPath == Seq("0"))
            assert(initEncoded == "1,2,3")
            assert(countAfterInit == 1)
            assert(encoded1 == "4,5")
            assert(countAfterFirst == 2)
    }

    // ---- Browser-level reactive behaviour ----

    "reactive span updates on signal change" in {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                UI.button("Change").id("btn").onClick(ref.set("updated")),
                ref.map(v => UI.span(v).id("val"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("val"), "initial")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("val"), "updated")
            yield ()
        }
    }

    "UI.when hides element when signal is false" in {
        val app: UI < Async =
            for show <- Signal.initRef(true)
            yield UI.div(
                UI.button("Toggle").id("tog").onClick(show.getAndUpdate(!_).unit),
                UI.when(show)(UI.span("visible").id("target"))
            )
        withUI(app) {
            for
                _ <- Browser.assertExists(Selector.id("target"))
                _ <- Browser.click(Selector.id("tog"))
                _ <- Browser.assertNotExists(Selector.id("target"))
            yield ()
        }
    }

    "two reactive zones update independently" in {
        val app: UI < Async =
            for
                a <- Signal.initRef("A0")
                b <- Signal.initRef("B0")
            yield UI.div(
                UI.button("UpdateA").id("ua").onClick(a.set("A1")),
                UI.button("UpdateB").id("ub").onClick(b.set("B1")),
                a.map(v => UI.span(v).id("va")),
                b.map(v => UI.span(v).id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("va"), "A0")
                _ <- Browser.assertText(Selector.id("vb"), "B0")
                _ <- Browser.click(Selector.id("ua"))
                _ <- Browser.assertText(Selector.id("va"), "A1")
                _ <- Browser.assertText(Selector.id("vb"), "B0")
                _ <- Browser.click(Selector.id("ub"))
                _ <- Browser.assertText(Selector.id("va"), "A1")
                _ <- Browser.assertText(Selector.id("vb"), "B1")
            yield ()
        }
    }

    "nested reactive within reactive updates correctly" in {
        val app: UI < Async =
            for
                outer <- Signal.initRef(true)
                inner <- Signal.initRef("inner-val")
            yield UI.div(
                UI.button("HideOuter").id("ho").onClick(outer.set(false)),
                UI.button("UpdateInner").id("ui").onClick(inner.set("inner-new")),
                UI.when(outer)(UI.div(inner.map(t => UI.span(t).id("inner"))))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("inner"), "inner-val")
                _ <- Browser.click(Selector.id("ui"))
                _ <- Browser.assertText(Selector.id("inner"), "inner-new")
                _ <- Browser.click(Selector.id("ho"))
                _ <- Browser.assertNotExists(Selector.id("inner"))
            yield ()
        }
    }

    "reactive re-subscribes after DOM replacement" in {
        val app: UI < Async =
            for
                toggle <- Signal.initRef(false)
                inner  <- Signal.initRef("v0")
            yield UI.div(
                UI.button("Flip").id("flip").onClick(toggle.getAndUpdate(!_).unit),
                UI.button("Set").id("set").onClick(inner.set("v1")),
                toggle.map { t =>
                    UI.div.id(if t then "box-b" else "box-a")(
                        inner.map(v => UI.span(v).id("ispan"))
                    )
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("ispan"), "v0")
                _ <- Browser.click(Selector.id("flip"))             // outer reactive re-renders
                _ <- Browser.assertText(Selector.id("ispan"), "v0") // inner still works
                _ <- Browser.click(Selector.id("set"))
                _ <- Browser.assertText(Selector.id("ispan"), "v1")
            yield ()
        }
    }

    "signal update after multiple renders is idempotent" in {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("Inc").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                counter.map(n => UI.span(n.toString).id("cnt"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("cnt"), "3")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("cnt"), "5")
            yield ()
        }
    }

end ReactiveUITest

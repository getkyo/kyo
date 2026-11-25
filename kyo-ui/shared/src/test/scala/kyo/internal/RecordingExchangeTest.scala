package kyo.internal

import kyo.*
import kyo.internal.RecordingExchange.Op

class RecordingExchangeTest extends kyo.test.Test[Any]:

    import UI.*

    private val context = ReactiveRegion.RegionIdentity(Seq.empty, 0)

    private def paint(rec: RecordingExchange, path: String*)(using Frame): Unit < Async =
        rec.onChange(ReactiveRegion.HtmlRange("r"), path, context, ReactiveRegion.ParentContext.Other, Absent, UI.empty)

    "keeps every paint in the order it arrived" in {
        val rec = new RecordingExchange
        for
            _   <- paint(rec, "a")
            _   <- paint(rec, "b")
            _   <- paint(rec, "a", "0")
            ops <- rec.ops
        yield
            assert(ops == Chunk[Op](
                Op.Paint(Seq("a"), Absent, UI.empty),
                Op.Paint(Seq("b"), Absent, UI.empty),
                Op.Paint(Seq("a", "0"), Absent, UI.empty)
            ))
            assert(rec.recorded == ops)
        end for
    }

    "paints answers with the paints, oldest first" in {
        val rec = new RecordingExchange
        for
            _     <- paint(rec, "b")
            _     <- paint(rec, "c")
            shown <- rec.paints
        yield assert(shown.map(_.path) == Chunk(Seq("b"), Seq("c")))
        end for
    }

    "awaitOp is released by an op already recorded" in {
        val rec = new RecordingExchange
        for
            _ <- paint(rec, "a")
            _ <- rec.awaitOp { case Op.Paint(path, _, _) => path == Seq("a") }
        yield succeed
        end for
    }

    "awaitOp is released by a later op, and only by one its predicate takes" in {
        val rec = new RecordingExchange
        for
            waiting <- Fiber.init(rec.awaitOp { case Op.Paint(path, _, _) => path == Seq("wanted") })
            _       <- paint(rec, "other")
            early   <- waiting.done
            _       <- paint(rec, "wanted")
            _       <- waiting.get
        yield assert(!early)
        end for
    }

    "beforePaint suspends a paint before it is recorded" in {
        for
            entered <- Promise.init[Unit, Any]
            release <- Promise.init[Unit, Any]
            rec = new RecordingExchange(beforePaint = (_, _) => entered.completeUnitDiscard.andThen(release.get))
            painting <- Fiber.init(paint(rec, "p"))
            _        <- entered.get
            during   <- rec.paints
            _        <- release.completeUnitDiscard
            _        <- painting.get
            after    <- rec.paints
        yield
            assert(during.isEmpty)
            assert(after.map(_.path) == Chunk(Seq("p")))
        end for
    }

    "subscribed hands over the subscription of the tree it runs" in {
        val rec = new RecordingExchange
        for
            ref <- Signal.initRef("a")
            ui = UI.div(ref.render(v => UI.span(v)))
            _ <- ReactiveUI.subscribed(ui, rec) { _ =>
                ref.set("b").andThen(rec.awaitOp { case Op.Paint(_, _, shown) => shown == UI.span("b") })
            }
            paints <- rec.paints
        yield assert(paints.map(_.path).distinct == Chunk(Seq("0")))
        end for
    }

end RecordingExchangeTest

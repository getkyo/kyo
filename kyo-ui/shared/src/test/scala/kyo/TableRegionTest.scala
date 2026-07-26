package kyo

import kyo.Browser.*
import kyo.UI.foreach
import kyo.UI.foreachKeyed
import scala.language.implicitConversions

/** Chrome-backed regressions for reactive regions in parser-hostile contexts (table, tr, select).
  *
  * These paint paths were structurally broken under element anchors: the HTML parser foster-parented a
  * `<span>` anchor out of `<table>` at the initial paint, and the "in body" template fragment parse
  * silently dropped `<tr>` payloads on the patch path. Comment markers plus context-aware payload
  * parsing fix both. Every assert here is STRUCTURAL ("table tr", "select option"): a body-wide text
  * assert passes even with foster-parented content, which is exactly how the old bug stayed invisible.
  */
class TableRegionTest extends UITest:

    "reactive rows as direct table child paint inside the table and patch" in {
        val app: UI < Async =
            for rows <- Signal.initRef(Chunk("A", "B"))
            yield UI.div(
                UI.table(rows.foreach(r => UI.tr(UI.td(r).cssClass("cell")))),
                UI.button("Grow").id("grow").onClick(rows.getAndUpdate(_ :+ "C").unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertCount(Selector.css("table tr"), 2)
                _ <- Browser.assertCount(Selector.css("table td.cell"), 2)
                _ <- Browser.click(Selector.id("grow"))
                // The patched row lands INSIDE the table (the payload parse must use a table context;
                // the old "in body" parse dropped the <tr> wholesale).
                _ <- Browser.assertCount(Selector.css("table tr"), 3)
                _ <- Browser.assertText(Selector.css("table tr:last-child td"), "C")
            yield ()
        }
    }

    "reactive cell as direct tr child paints inside the row and patches" in {
        val app: UI < Async =
            for cell <- Signal.initRef("x")
            yield UI.div(
                UI.table(UI.tr(UI.th("head"), cell.map(v => UI.td(v).id("dyn-cell")))),
                UI.button("Bump").id("bump").onClick(cell.set("y"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.css("table tr td#dyn-cell"), "x")
                _ <- Browser.click(Selector.id("bump"))
                _ <- Browser.assertText(Selector.css("table tr td#dyn-cell"), "y")
            yield ()
        }
    }

    "keyed row regions add, remove, and reorder inside the table" in {
        val app: UI < Async =
            for rows <- Signal.initRef(Chunk("a", "b", "c"))
            yield UI.div(
                UI.table(rows.foreachKeyed(identity)(r => UI.tr(UI.td(r).cssClass("cell")))),
                UI.button("Rev").id("rev").onClick(rows.getAndUpdate(c => Chunk.from(c.toSeq.reverse)).unit),
                UI.button("Drop").id("drop").onClick(rows.getAndUpdate(_.init).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertCount(Selector.css("table tr"), 3)
                _ <- Browser.assertText(Selector.css("table tr:first-child td"), "a")
                _ <- Browser.click(Selector.id("rev"))
                _ <- Browser.assertText(Selector.css("table tr:first-child td"), "c")
                _ <- Browser.assertText(Selector.css("table tr:last-child td"), "a")
                _ <- Browser.click(Selector.id("drop"))
                _ <- Browser.assertCount(Selector.css("table tr"), 2)
            yield ()
        }
    }

    "row-region patches leave sibling static rows untouched" in {
        val app: UI < Async =
            for rows <- Signal.initRef(Chunk("r1"))
            yield UI.div(
                UI.table(
                    UI.tr(UI.th("H1").cssClass("head"), UI.th("H2").cssClass("head")),
                    rows.foreach(r => UI.tr(UI.td(r).cssClass("cell")))
                ),
                UI.button("Grow").id("grow").onClick(rows.getAndUpdate(_ :+ "r2").unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertCount(Selector.css("table th.head"), 2)
                _ <- Browser.click(Selector.id("grow"))
                // The range morph is bounded by the region's markers: the static header row is an
                // out-of-range sibling and must survive exactly once.
                _ <- Browser.assertCount(Selector.css("table th.head"), 2)
                _ <- Browser.assertCount(Selector.css("table td.cell"), 2)
                _ <- Browser.assertCount(Selector.css("table tr"), 3)
            yield ()
        }
    }

    "mounted rows inside a table paint inside it" in {
        val mountedRows: UI < (Async & Scope) =
            (UI.fragment(UI.tr(UI.td("M1").cssClass("mcell")), UI.tr(UI.td("M2").cssClass("mcell"))): UI)
        val app: UI < Async =
            UI.div(UI.table(UI.mounted(mountedRows).keyed("rows")))
        withUI(app) {
            for
                _ <- Browser.assertCount(Selector.css("table td.mcell"), 2)
                _ <- Browser.assertText(Selector.css("table tr:first-child td"), "M1")
            yield ()
        }
    }

    "nested region ($r) inside a table patches through both levels" in {
        val app: UI < Async =
            for
                outer <- Signal.initRef(0)
                inner <- Signal.initRef("v1")
            yield UI.div(
                UI.table(outer.map(o => (inner.map(v => UI.tr(UI.td(s"$o:$v").cssClass("cell"))): UI))),
                UI.button("Inner").id("inner").onClick(inner.set("v2")),
                UI.button("Outer").id("outer").onClick(outer.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.css("table td.cell"), "0:v1")
                _ <- Browser.click(Selector.id("inner"))
                _ <- Browser.assertText(Selector.css("table td.cell"), "0:v2")
                _ <- Browser.click(Selector.id("outer"))
                _ <- Browser.assertText(Selector.css("table td.cell"), "1:v2")
                _ <- Browser.assertCount(Selector.css("table td.cell"), 1)
            yield ()
        }
    }

    "reactive options as direct select child paint and patch" in {
        // Under element anchors the "in select" insertion mode dropped the <span> anchor wholesale,
        // so signal-driven options never painted and updates were dead. Comments survive that mode.
        val app: UI < Async =
            for opts <- Signal.initRef(Chunk("one", "two"))
            yield UI.div(
                UI.select(opts.foreach(o => UI.option(o).value(o))).id("sel"),
                UI.button("Grow").id("grow").onClick(opts.getAndUpdate(_ :+ "three").unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertCount(Selector.css("select option"), 2)
                _ <- Browser.click(Selector.id("grow"))
                _ <- Browser.assertCount(Selector.css("select option"), 3)
                _ <- Browser.assertText(Selector.css("select option:last-child"), "three")
            yield ()
        }
    }

end TableRegionTest

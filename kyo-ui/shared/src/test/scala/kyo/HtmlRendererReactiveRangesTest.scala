package kyo

import kyo.Browser.*
import kyo.UI.Ast.Text
import kyo.UI.foreach
import kyo.UI.foreachKeyed

class HtmlRendererReactiveRangesTest extends UITest:

    "HTML reactive regions render as safe logical comment ranges" in {
        for
            ref <- Signal.initRef("value")
            html <- kyo.internal.HtmlRenderer.render(
                ref.map(value => Text(value)),
                Seq("", "😀", "\"--\u0000")
            )
        yield
            val id = "r0000000000000002d83dde00000000040022002d002d0000"
            assert(html == s"<!--kyo-rs:$id-->value<!--kyo-re:$id-->")
            assert(!html.contains("data-kyo-reactive"))
    }

    "reactive rows render both logical anchors inside one explicit tbody" in {
        for
            rows <- Signal.initRef(Chunk("dynamic"))
            html <- kyo.internal.HtmlRenderer.render(
                UI.table(
                    UI.tr(UI.td("before").id("before")),
                    rows.foreach(value => UI.tr(UI.td(value).id(value))),
                    UI.tr(UI.td("after").id("after"))
                ),
                Seq.empty
            )
        yield
            val id    = "r000000010031"
            val start = s"<!--kyo-rs:$id-->"
            val end   = s"<!--kyo-re:$id-->"
            val host  = s"<tbody data-kyo-range-host=\"$id\">$start"
            assert(html.contains(host))
            assert(html.contains(s"$end</tbody>"))
            assert(html.indexOf("id=\"before\"") < html.indexOf(host))
            assert(html.indexOf("id=\"after\"") > html.indexOf(s"$end</tbody>"))
            assert(!html.contains("<span data-kyo-reactive"))
    }

    "reactive authored tbody remains a table child with sibling anchors" in {
        for
            show <- Signal.initRef(true)
            html <- kyo.internal.HtmlRenderer.render(
                UI.table(show.map(_ => UI.tbody(UI.tr(UI.td("row"))))),
                Seq.empty
            )
        yield
            assert(!html.contains("<tbody><!--kyo-rs:"))
            assert("<table[^>]*><!--kyo-rs:[^>]*--><tbody".r.findFirstIn(html).nonEmpty)
            assert("</tbody><!--kyo-re:[^>]*--></table>".r.findFirstIn(html).nonEmpty)
    }

    "foreach authored tbody remains a table child with sibling anchors" in {
        for
            sections <- Signal.initRef(Chunk("section"))
            html <- kyo.internal.HtmlRenderer.render(
                UI.table(sections.foreach(value => UI.tbody(UI.tr(UI.td(value))).id(value))),
                Seq.empty
            )
        yield
            assert(!html.contains("<tbody data-kyo-range-host"))
            assert("<table[^>]*><!--kyo-rs:[^>]*--><tbody".r.findFirstIn(html).nonEmpty)
            assert("</tbody><!--kyo-re:[^>]*--></table>".r.findFirstIn(html).nonEmpty)
    }

    "SignalRef-bound HTML elements receive an initial range but replacement content does not nest it" in {
        for
            ref         <- Signal.initRef("initial")
            initial     <- kyo.internal.HtmlRenderer.render(UI.input.value(ref), Seq("field"))
            replacement <- kyo.internal.HtmlRenderer.renderRegion(UI.input.value(ref), Seq("field"))
        yield
            val id = "r00000005006600690065006c0064"
            assert(initial.startsWith(s"<!--kyo-rs:$id--><input"))
            assert(initial.endsWith(s"<!--kyo-re:$id-->"))
            assert(!replacement.contains("kyo-rs:"))
            assert(!replacement.contains("kyo-re:"))
        end for
    }

    "directly nested reactive regions receive distinct logical ids" in {
        for
            outer  <- Signal.initRef(true)
            middle <- Signal.initRef(true)
            inner  <- Signal.initRef("value")
            nestedHtml <- kyo.internal.HtmlRenderer.render(
                outer.map(_ => (middle.map(_ => (inner.map(value => Text(value)): UI)): UI)),
                Seq("nested")
            )
            keyedHtml <- kyo.internal.HtmlRenderer.render(
                UI.fragment(UI.Ast.KeyedChild[UI]("", inner.map(value => Text(value)))),
                Seq("nested")
            )
        yield
            val nestedIds = "<!--kyo-rs:([^>]*)-->".r.findAllMatchIn(nestedHtml).map(_.group(1)).toSeq
            val keyedIds  = "<!--kyo-rs:([^>]*)-->".r.findAllMatchIn(keyedHtml).map(_.group(1)).toSeq
            assert(nestedIds.size == 3)
            assert(nestedIds.distinct.size == 3)
            assert(nestedIds(1).endsWith("n00000001"))
            assert(nestedIds(2).endsWith("n00000002"))
            assert(keyedIds.size == 1)
            assert(keyedIds.head.endsWith("00000000"))
            assert(!nestedIds.contains(keyedIds.head))
    }

    "server-push reactive rows retain legal table topology across repeated updates" in {
        val app: UI < Async =
            for
                rows                 <- Signal.initRef(Chunk("A"))
                nested               <- Signal.initRef("one")
                deepOuter            <- Signal.initRef(true)
                deepMiddle           <- Signal.initRef(true)
                deepValue            <- Signal.initRef("deep-one")
                sectioned            <- Signal.initRef(false)
                foreachSectioned     <- Signal.initRef(Chunk(false))
                nestedTableOuter     <- Signal.initRef(true)
                nestedTableSectioned <- Signal.initRef(false)
            yield UI.div(
                UI.table(
                    UI.tr(UI.td("before").id("before")),
                    rows.foreach(value => UI.tr(UI.td(value).id(s"row-$value"))),
                    UI.tr(UI.td("after").id("after"))
                ).id("table"),
                UI.table(UI.tbody(rows.foreach(value => UI.tr(UI.td(value).id(s"grouped-$value"))))).id("grouped-table"),
                UI.table(UI.tbody(UI.tr(rows.foreach(value => UI.td(value).id(s"cell-$value"))))).id("cell-table"),
                UI.ul(rows.foreach(value => UI.li(value).id(s"ul-$value"))).id("ul"),
                UI.ol(rows.foreach(value => UI.li(value).id(s"ol-$value"))).id("ol"),
                UI.select(rows.foreach(value => UI.option(value).value(value).id(s"option-$value"))).id("select"),
                UI.div(rows.foreachKeyed(identity)(value => UI.button(value).id(s"keyed-$value"))).id("keyed"),
                rows.map(_ => UI.div(nested.map(value => UI.span(value).id("nested-value"))).id("nested")),
                deepOuter.map(_ => (deepMiddle.map(_ => (deepValue.map(value => UI.span(value).id("deep-value")): UI)): UI)),
                UI.table(
                    sectioned.map { authored =>
                        if authored then UI.tbody(UI.tr(UI.td("authored"))).id("authored-body").data("state", "authored"): UI
                        else UI.tr(UI.td("row").id("transition-row")): UI
                    }
                ).id("transition-table"),
                UI.table(
                    foreachSectioned.foreach { authored =>
                        if authored then UI.tbody(UI.tr(UI.td("foreach-authored"))).id("foreach-authored-body").data("state", "authored")
                        else UI.tr(UI.td("foreach-row").id("foreach-transition-row"))
                    }
                ).id("foreach-transition-table"),
                UI.table(
                    nestedTableOuter.map(_ =>
                        (nestedTableSectioned.map { authored =>
                            if authored then
                                UI.tbody(UI.tr(UI.td("nested-authored"))).id("nested-authored-body").data("state", "authored"): UI
                            else UI.tr(UI.td("nested-row").id("nested-transition-row")): UI
                        }: UI)
                    )
                ).id("nested-transition-table"),
                UI.button("add").id("add").onClick(
                    rows.getAndUpdate(_ :+ "B").unit.andThen(nested.set("two"))
                ),
                UI.button("deep").id("deep").onClick(deepValue.set("deep-two")),
                UI.button("section").id("section").onClick(
                    sectioned.set(true).andThen(foreachSectioned.set(Chunk(true))).andThen(nestedTableSectioned.set(true))
                ),
                UI.button("rows").id("rows").onClick(
                    sectioned.set(false).andThen(foreachSectioned.set(Chunk(false))).andThen(nestedTableSectioned.set(false))
                ),
                UI.button("clear").id("clear").onClick(rows.set(Chunk.empty).andThen(nested.set("empty")))
            )
        withUI(app) {
            def topology(using Frame, kyo.test.AssertScope) =
                Browser.evalJson[String](
                    "(() => {" +
                        "const t=document.getElementById('table');" +
                        "const groups=Array.from(t.children).filter(e=>e.tagName==='TBODY');" +
                        "const reactive=groups.find(g=>Array.from(g.childNodes).some(n=>n.nodeType===8&&n.nodeValue.startsWith('kyo-rs:')));" +
                        "const isolated=!reactive.querySelector('#before,#after');" +
                        "const spans=t.querySelectorAll('span[data-kyo-reactive]').length;" +
                        "return [groups.length,reactive&&reactive.children.length,isolated,spans].join(':');" +
                        "})()"
                )
            def restrictedTopology(using Frame, kyo.test.AssertScope) =
                Browser.evalJson[String](
                    "(() => {" +
                        "const count=s=>document.querySelectorAll(s).length;" +
                        "return [count('#grouped-table>tbody>tr'),count('#cell-table>tbody>tr>td')," +
                        "count('#ul>li'),count('#ol>li'),count('#select>option'),count('#keyed>button')," +
                        "document.getElementById('nested-value').textContent," +
                        "document.querySelectorAll('span[data-kyo-reactive]').length].join(':');" +
                        "})()"
                )
            for
                initial           <- topology
                initialRestricted <- restrictedTopology
                _                 <- Browser.assertText(Selector.id("deep-value"), "deep-one")
                _                 <- Browser.click(Selector.id("deep"))
                _                 <- Browser.assertText(Selector.id("deep-value"), "deep-two")
                _                 <- Browser.click(Selector.id("section"))
                _                 <- Browser.assertText(Selector.id("authored-body"), "authored")
                authoredState <- Browser.evalJson[String](
                    "document.getElementById('authored-body').parentElement.id+':' + document.getElementById('authored-body').dataset.state"
                )
                foreachAuthoredState <- Browser.evalJson[String](
                    "document.getElementById('foreach-authored-body').parentElement.id+':' + document.getElementById('foreach-authored-body').dataset.state"
                )
                nestedAuthoredState <- Browser.evalJson[String](
                    "document.getElementById('nested-authored-body').parentElement.id+':' + document.getElementById('nested-authored-body').dataset.state"
                )
                _ <- Browser.click(Selector.id("rows"))
                _ <- Browser.assertText(Selector.id("transition-row"), "row")
                rowState <- Browser.evalJson[String](
                    "document.getElementById('transition-row').closest('tbody').parentElement.id"
                )
                foreachRowState <- Browser.evalJson[String](
                    "document.getElementById('foreach-transition-row').closest('tbody').parentElement.id"
                )
                nestedRowState <- Browser.evalJson[String](
                    "document.getElementById('nested-transition-row').closest('tbody').parentElement.id"
                )
                _                 <- Browser.click(Selector.id("add"))
                _                 <- Browser.assertText(Selector.id("row-B"), "B")
                updated           <- topology
                updatedRestricted <- restrictedTopology
                _                 <- Browser.click(Selector.id("clear"))
                _                 <- Browser.assertNotExists(Selector.id("row-A"))
                cleared           <- topology
                clearedRestricted <- restrictedTopology
            yield
                assert(initial == "3:1:true:0")
                assert(updated == "3:2:true:0")
                assert(cleared == "3:0:true:0")
                assert(initialRestricted == "1:1:1:1:1:1:one:0")
                assert(updatedRestricted == "2:2:2:2:2:2:two:0")
                assert(clearedRestricted == "0:0:0:0:0:0:empty:0")
                assert(authoredState == "transition-table:authored")
                assert(foreachAuthoredState == "foreach-transition-table:authored")
                assert(nestedAuthoredState == "nested-transition-table:authored")
                assert(rowState == "transition-table")
                assert(foreachRowState == "foreach-transition-table")
                assert(nestedRowState == "nested-transition-table")
            end for
        }
    }

end HtmlRendererReactiveRangesTest

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

    "authored table sections mixed with a transparent row boundary keep the outer anchors at table level" in {
        for
            outer <- Signal.initRef(0)
            rows  <- Signal.initRef(Chunk("A"))
            html <- kyo.internal.HtmlRenderer.render(
                UI.table(
                    outer.map(_ =>
                        UI.fragment(
                            UI.tbody(UI.tr(UI.td("before"))).id("mixed-before"),
                            rows.foreach(value => UI.tr(UI.td(value).id(s"mixed-$value"))),
                            UI.tbody(UI.tr(UI.td("after"))).id("mixed-after")
                        )
                    )
                ),
                Seq.empty
            )
        yield
            val tableStart = html.indexOf("<table")
            val outerStart = html.indexOf("<!--kyo-rs:", tableStart)
            val before     = html.indexOf("id=\"mixed-before\"")
            val rowHost    = html.indexOf("<tbody data-kyo-range-host=", before)
            val after      = html.indexOf("id=\"mixed-after\"")
            val outerEnd   = html.lastIndexOf("<!--kyo-re:")
            assert(tableStart >= 0 && tableStart < outerStart)
            assert(outerStart < before && before < rowHost && rowHost < after && after < outerEnd)
            assert(!html.substring(outerStart, before).contains("data-kyo-range-host"))
        end for
    }

    "SignalRef-bound Dropdown receives an initial range but replacement content does not nest it" in {
        for
            ref <- Signal.initRef("a")
            dropdown = UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("dropdown").value(ref)
            initial     <- kyo.internal.HtmlRenderer.render(dropdown, Seq("dropdown"))
            replacement <- kyo.internal.HtmlRenderer.renderRegion(dropdown, Seq("dropdown"))
        yield
            val id = "r0000000800640072006f00700064006f0077006e"
            assert(initial.startsWith(s"<!--kyo-rs:$id--><div"))
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
                nestedProperty       <- Signal.initRef(false)
            yield UI.div(
                UI.table(
                    UI.tr(UI.td("before").id("before")),
                    rows.foreach(value => UI.tr(UI.td(value).id(s"row-$value"))),
                    UI.tr(UI.td("after").id("after"))
                ).id("table"),
                UI.table(UI.tbody(rows.foreach(value => UI.tr(UI.td(value).id(s"grouped-$value"))))).id("grouped-table"),
                UI.table(UI.tbody(UI.tr(rows.foreach(value => UI.td(value).id(s"cell-$value"))))).id("cell-table"),
                UI.table(UI.tbody(UI.tr(rows.foreach(value => UI.th(value).id(s"header-cell-$value"))))).id("header-cell-table"),
                UI.ul(rows.foreach(value => UI.li(value).id(s"ul-$value"))).id("ul"),
                UI.ol(rows.foreach(value => UI.li(value).id(s"ol-$value"))).id("ol"),
                UI.select(rows.foreach(value => UI.option(value).value(value).id(s"option-$value"))).id("select"),
                UI.div(rows.foreachKeyed(identity)(value => UI.button(value).id(s"keyed-$value"))).id("keyed"),
                rows.map(_ => UI.div(nested.map(value => UI.span(value).id("nested-value"))).id("nested")),
                nestedProperty.map(value =>
                    UI.div(
                        UI.checkbox.id("nested-property").indeterminate(value),
                        UI.span(value.toString).id("nested-property-state")
                    )
                ),
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
                UI.button("property").id("property").onClick(nestedProperty.set(true)),
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
                        "const walker=document.createTreeWalker(t,NodeFilter.SHOW_COMMENT);let start=null,end=null,id=null,node;" +
                        "while(node=walker.nextNode()){if(node.nodeValue.startsWith('kyo-rs:')){start=node;id=node.nodeValue.slice(7);break;}}" +
                        "while(node=walker.nextNode()){if(node.nodeValue==='kyo-re:'+id){end=node;break;}}" +
                        "let count=0,isolated=true;node=start.nextSibling;while(node&&node!==end){" +
                        "if(node.nodeType===1){count++;if(node.matches('#before,#after')||node.querySelector('#before,#after'))isolated=false;}node=node.nextSibling;}" +
                        "const spans=t.querySelectorAll('span[data-kyo-reactive]').length;" +
                        "return [groups.length,count,isolated,spans].join(':');" +
                        "})()"
                )
            def restrictedTopology(using Frame, kyo.test.AssertScope) =
                Browser.evalJson[String](
                    "(() => {" +
                        "const count=s=>document.querySelectorAll(s).length;" +
                        "const prop=document.getElementById('nested-property');" +
                        "return [count('#grouped-table>tbody>tr'),count('#cell-table>tbody>tr>td'),count('#header-cell-table>tbody>tr>th')," +
                        "count('#ul>li'),count('#ol>li'),count('#select>option'),count('#keyed>button')," +
                        "document.getElementById('nested-value').textContent," +
                        "String(prop.indeterminate),String(prop.hasAttribute('data-kyo-prop-indeterminate'))," +
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
                _                 <- Browser.click(Selector.id("property"))
                _                 <- Browser.assertText(Selector.id("nested-property-state"), "true")
                updated           <- topology
                updatedRestricted <- restrictedTopology
                _                 <- Browser.click(Selector.id("clear"))
                _                 <- Browser.assertNotExists(Selector.id("row-A"))
                cleared           <- topology
                clearedRestricted <- restrictedTopology
            yield
                assert(initial == "3:1:true:0")
                assert(updated == "3:2:true:0")
                assert(cleared == "2:0:true:0")
                assert(initialRestricted == "1:1:1:1:1:1:1:one:false:false:0")
                assert(updatedRestricted == "2:2:2:2:2:2:2:two:true:false:0")
                assert(clearedRestricted == "0:0:0:0:0:0:0:empty:true:false:0")
                assert(authoredState == "transition-table:authored")
                assert(foreachAuthoredState == "foreach-transition-table:authored")
                assert(nestedAuthoredState == "nested-transition-table:authored")
                assert(rowState == "transition-table")
                assert(foreachRowState == "foreach-transition-table")
                assert(nestedRowState == "nested-transition-table")
            end for
        }
    }

    "server-push bound Dropdown updates inside its live logical range without an unknown id" in {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(
                UI.dropdown("Alpha" -> "a", "Beta" -> "b").id("live-dropdown").value(selected),
                UI.button("Beta").id("choose-beta").onClick(selected.set("b")),
                UI.button("Alpha").id("choose-alpha").onClick(selected.set("a"))
            )
        withUI(app) {
            for
                _ <- Browser.evalDiscard(
                    "window.__kyoDropdownErrors=[];window.__kyoOriginalConsoleError=console.error;" +
                        "console.error=function(error){window.__kyoDropdownErrors.push(String(error));" +
                        "window.__kyoOriginalConsoleError.apply(console,arguments);};"
                )
                _           <- Browser.assertText(Selector.id("live-dropdown-trigger"), "Alpha ▾")
                _           <- Browser.click(Selector.id("choose-beta"))
                _           <- Browser.assertText(Selector.id("live-dropdown-trigger"), "Beta ▾")
                _           <- Browser.click(Selector.id("choose-alpha"))
                _           <- Browser.assertText(Selector.id("live-dropdown-trigger"), "Alpha ▾")
                diagnostics <- Browser.evalJson[Seq[String]]("window.__kyoDropdownErrors")
            yield assert(diagnostics == Seq.empty)
        }
    }

    "server-push mixed authored sections and transparent rows retain table topology across updates" in {
        val app: UI < Async =
            for
                outer <- Signal.initRef(0)
                rows  <- Signal.initRef(Chunk("A"))
            yield UI.div(
                UI.table(
                    outer.map(_ =>
                        UI.fragment(
                            UI.tbody(UI.tr(UI.td("before"))).id("server-mixed-before"),
                            rows.foreach(value => UI.tr(UI.td(value).id(s"server-mixed-$value"))),
                            UI.tbody(UI.tr(UI.td("after"))).id("server-mixed-after")
                        )
                    )
                ).id("server-mixed-table"),
                UI.button("add").id("server-mixed-add").onClick(rows.getAndUpdate(_ :+ "B").unit),
                UI.button("outer").id("server-mixed-outer").onClick(outer.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            def topology(using Frame, kyo.test.AssertScope) =
                Browser.evalJson[String](
                    "(() => {const table=document.getElementById('server-mixed-table');" +
                        "const bodies=Array.from(table.children).filter(e=>e.tagName==='TBODY');" +
                        "return [bodies.length,bodies[0].id,bodies[1].children.length,bodies[2].id," +
                        "table.querySelectorAll(':scope > tbody > tbody').length].join(':');})()"
                )
            for
                initial  <- topology
                _        <- Browser.click(Selector.id("server-mixed-add"))
                _        <- Browser.assertText(Selector.id("server-mixed-B"), "B")
                inner    <- topology
                _        <- Browser.click(Selector.id("server-mixed-outer"))
                _        <- Browser.assertText(Selector.id("server-mixed-B"), "B")
                repeated <- topology
            yield
                assert(initial == "3:server-mixed-before:1:server-mixed-after:0")
                assert(inner == "3:server-mixed-before:2:server-mixed-after:0")
                assert(repeated == inner)
            end for
        }
    }

end HtmlRendererReactiveRangesTest

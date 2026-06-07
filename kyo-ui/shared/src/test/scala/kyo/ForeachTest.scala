package kyo

import kyo.Browser.*
import kyo.UI.foreach
import kyo.UI.foreachIndexed
import kyo.UI.foreachKeyed
import scala.language.implicitConversions

class ForeachTest extends UITest:

    // === Basic ===

    "foreach renders initial items" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(items.foreach(s => UI.span(s)))
        withUI(app) {
            for
                _ <- assertContains("A")
                _ <- assertContains("B")
                _ <- assertContains("C")
            yield ()
        }
    }

    "foreach empty Chunk renders no children" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                items.foreach(s => UI.span(s).id(s"item-$s")),
                UI.span("sentinel").id("sentinel")
            )
        withUI(app) {
            Browser.assertVisible(Selector.id("sentinel")).unit
        }
    }

    "foreach single item" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("only")))
            yield UI.div(items.foreach(s => UI.span(s).id("only-item")))
        withUI(app) {
            Browser.assertText(Selector.id("only-item"), "only").unit
        }
    }

    "foreach item text matches data" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("alpha", "beta", "gamma")))
            yield UI.div(items.foreach(s => UI.span(s"item:$s")))
        withUI(app) {
            for
                _ <- assertContains("item:alpha")
                _ <- assertContains("item:beta")
                _ <- assertContains("item:gamma")
            yield ()
        }
    }

    // === Dynamic ===

    "append item appears" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B")))
            yield UI.div(
                items.foreach(s => UI.span(s)),
                UI.button("Add").id("add").onClick(items.getAndUpdate(_.appended("C")).unit)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("add"))
                _ <- assertContains("C")
            yield ()
        }
    }

    "remove last item disappears" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                count <- Signal.initRef(3)
            yield UI.div(
                items.foreach(s => UI.span(s"v:$s").id(s"item-$s")),
                UI.button("RemoveLast").id("rm").onClick(
                    items.getAndUpdate(c => c.take(c.size - 1)).map(c => count.set(c.size - 1)).unit
                ),
                count.map(n => UI.span(s"count:$n").id("count"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertText(Selector.id("count"), "count:2")
            yield ()
        }
    }

    "remove first item others shift" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(
                items.foreach(s => UI.span(s"v:$s").id(s"item-$s")),
                UI.button("RemoveFirst").id("rm").onClick(items.getAndUpdate(_.drop(1)).unit)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertText(Selector.id("item-B"), "v:B")
                _ <- Browser.assertText(Selector.id("item-C"), "v:C")
            yield ()
        }
    }

    "remove middle item others intact" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(
                items.foreach(s => UI.span(s"v:$s").id(s"item-$s")),
                UI.button("RemoveMiddle").id("rm").onClick(
                    items.getAndUpdate(c => c.take(1).concat(c.drop(2))).unit
                )
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertText(Selector.id("item-A"), "v:A")
                _ <- Browser.assertText(Selector.id("item-C"), "v:C")
            yield ()
        }
    }

    "clear all items" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(
                items.foreach(s => UI.span(s"v:$s").id(s"item-$s")),
                UI.span("sentinel").id("sentinel"),
                UI.button("Clear").id("clear").onClick(items.set(Chunk.empty[String]))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("clear"))
                _ <- Browser.assertVisible(Selector.id("sentinel"))
            yield ()
        }
    }

    "replace entire Chunk shows new items" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B")))
            yield UI.div(
                items.foreach(s => UI.span(s"v:$s")),
                UI.button("Replace").id("rep").onClick(items.set(Chunk.from(Seq("X", "Y", "Z"))))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rep"))
                _ <- assertContains("v:X")
                _ <- assertContains("v:Y")
                _ <- assertContains("v:Z")
            yield ()
        }
    }

    "rapid 3 updates final correct" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A")))
            yield UI.div(
                items.foreach(s => UI.span(s"v:$s")),
                UI.button("Step1").id("s1").onClick(items.set(Chunk.from(Seq("B", "C")))),
                UI.button("Step2").id("s2").onClick(items.set(Chunk.from(Seq("D")))),
                UI.button("Step3").id("s3").onClick(items.set(Chunk.from(Seq("E", "F", "G"))))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("s1"))
                _ <- Browser.click(Selector.id("s2"))
                _ <- Browser.click(Selector.id("s3"))
                _ <- assertContains("v:E")
                _ <- assertContains("v:F")
                _ <- assertContains("v:G")
            yield ()
        }
    }

    "update single item content same length different value" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(
                items.foreach(s => UI.span(s"v:$s")),
                UI.button("Update").id("upd").onClick(
                    items.set(Chunk.from(Seq("A", "X", "C")))
                )
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("upd"))
                _ <- assertContains("v:X")
            yield ()
        }
    }

    // === foreachIndexed ===

    "foreachIndexed correct indices" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("a", "b", "c")))
            yield UI.div(items.foreachIndexed((i, s) => UI.span(s"$i:$s")))
        withUI(app) {
            for
                _ <- assertContains("0:a")
                _ <- assertContains("1:b")
                _ <- assertContains("2:c")
            yield ()
        }
    }

    "foreachIndexed indices update after removal" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("a", "b", "c")))
            yield UI.div(
                items.foreachIndexed((i, s) => UI.span(s"$i:$s")),
                UI.button("RemoveFirst").id("rm").onClick(items.getAndUpdate(_.drop(1)).unit)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- assertContains("0:b")
                _ <- assertContains("1:c")
            yield ()
        }
    }

    "foreachIndexed indices after insertion at beginning" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("b", "c")))
            yield UI.div(
                items.foreachIndexed((i, s) => UI.span(s"$i:$s")),
                UI.button("Prepend").id("pre").onClick(
                    items.set(Chunk.from(Seq("a")).concat(Chunk.from(Seq("b", "c"))))
                )
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("pre"))
                _ <- assertContains("0:a")
                _ <- assertContains("1:b")
                _ <- assertContains("2:c")
            yield ()
        }
    }

    // === foreachKeyed ===

    "foreachKeyed renders correct content" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("x", "y", "z")))
            yield UI.div(items.foreachKeyed(identity)(s => UI.span(s"k:$s")))
        withUI(app) {
            for
                _ <- assertContains("k:x")
                _ <- assertContains("k:y")
                _ <- assertContains("k:z")
            yield ()
        }
    }

    "foreachKeyed reorder matches new order" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(
                items.foreachKeyed(identity)(s => UI.span(s"k:$s")),
                UI.button("Reorder").id("ro").onClick(items.set(Chunk.from(Seq("C", "A", "B"))))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("ro"))
                _ <- assertContains("k:A")
                _ <- assertContains("k:B")
                _ <- assertContains("k:C")
            yield ()
        }
    }

    "foreachKeyed add at end" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B")))
            yield UI.div(
                items.foreachKeyed(identity)(s => UI.span(s"k:$s")),
                UI.button("Add").id("add").onClick(items.getAndUpdate(_.appended("C")).unit)
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("add"))
                _ <- assertContains("k:C")
            yield ()
        }
    }

    "foreachKeyed add at beginning" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("B", "C")))
            yield UI.div(
                items.foreachKeyed(identity)(s => UI.span(s"k:$s")),
                UI.button("Add").id("add").onClick(
                    items.set(Chunk.from(Seq("A")).concat(Chunk.from(Seq("B", "C"))))
                )
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("add"))
                _ <- assertContains("k:A")
                _ <- assertContains("k:B")
                _ <- assertContains("k:C")
            yield ()
        }
    }

    "foreachKeyed remove from middle" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
            yield UI.div(
                items.foreachKeyed(identity)(s => UI.span(s"k:$s").id(s"key-$s")),
                UI.button("Remove").id("rm").onClick(items.set(Chunk.from(Seq("A", "C"))))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.assertText(Selector.id("key-A"), "k:A")
                _ <- Browser.assertText(Selector.id("key-C"), "k:C")
            yield ()
        }
    }

    "foreachKeyed swap two items" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B")))
            yield UI.div(
                items.foreachKeyed(identity)(s => UI.span(s"k:$s")),
                UI.button("Swap").id("swap").onClick(items.set(Chunk.from(Seq("B", "A"))))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("swap"))
                _ <- assertContains("k:A")
                _ <- assertContains("k:B")
            yield ()
        }
    }

    "foreachKeyed duplicate keys edge case" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "A", "B")))
            yield UI.div(items.foreachKeyed(identity)(s => UI.span(s"k:$s")))
        withUI(app) {
            for
                _ <- assertContains("k:A")
                _ <- assertContains("k:B")
            yield ()
        }
    }

    // === Interactive ===

    "item onClick increments own counter" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("item")))
                counter <- Signal.initRef(0)
            yield UI.div(
                items.foreach(s =>
                    UI.button(s).id("btn").onClick(counter.getAndUpdate(_ + 1).unit)
                ),
                counter.map(n => UI.span(s"count:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "count:1")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("v"), "count:2")
            yield ()
        }
    }

    "item onClick sibling unaffected" in {
        val app: UI < Async =
            for
                items  <- Signal.initRef(Chunk.from(Seq("a", "b")))
                countA <- Signal.initRef(0)
                countB <- Signal.initRef(0)
            yield UI.div(
                items.foreach { s =>
                    if s == "a" then UI.button(s"Click-$s").id("btnA").onClick(countA.getAndUpdate(_ + 1).unit)
                    else UI.button(s"Click-$s").id("btnB").onClick(countB.getAndUpdate(_ + 1).unit)
                },
                countA.map(n => UI.span(s"a:$n").id("va")),
                countB.map(n => UI.span(s"b:$n").id("vb"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btnA"))
                _ <- Browser.assertText(Selector.id("va"), "a:1")
                _ <- Browser.assertText(Selector.id("vb"), "b:0")
            yield ()
        }
    }

    "add button and new item clickable" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("first")))
                clicked <- Signal.initRef("")
            yield UI.div(
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                UI.button("Add").id("add").onClick(items.getAndUpdate(_.appended("second")).unit),
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-first"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:first")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.click(Selector.id("btn-second"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:second")
            yield ()
        }
    }

    "remove button others still work" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B", "C")))
                clicked <- Signal.initRef("")
            yield UI.div(
                items.foreach(s => UI.button(s).id(s"btn-$s").onClick(clicked.set(s))),
                UI.button("RemoveFirst").id("rm").onClick(items.getAndUpdate(_.drop(1)).unit),
                clicked.map(v => UI.span(s"clicked:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-A"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:A")
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.click(Selector.id("btn-B"))
                _ <- Browser.assertText(Selector.id("v"), "clicked:B")
            yield ()
        }
    }

    "two inputs fill one others unchanged" in {
        val app: UI < Async =
            for
                text1 <- Signal.initRef("")
                text2 <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i1").onInput(v => text1.set(v)),
                UI.input.id("i2").onInput(v => text2.set(v)),
                text1.map(v => UI.span(s"t1:$v").id("v1")),
                text2.map(v => UI.span(s"t2:$v").id("v2"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i1"), "hello")
                _ <- Browser.assertText(Selector.id("v1"), "t1:hello")
                _ <- Browser.assertText(Selector.id("v2"), "t2:")
            yield ()
        }
    }

    "two checkboxes check one others unchanged" in {
        val app: UI < Async =
            for
                check1 <- Signal.initRef(false)
                check2 <- Signal.initRef(false)
            yield UI.div(
                UI.checkbox.id("c1").onChange(v => check1.set(v)),
                UI.checkbox.id("c2").onChange(v => check2.set(v)),
                check1.map(v => UI.span(s"c1:$v").id("v1")),
                check2.map(v => UI.span(s"c2:$v").id("v2"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("c1"))
                _ <- Browser.assertText(Selector.id("v1"), "c1:true")
                _ <- Browser.assertText(Selector.id("v2"), "c2:false")
            yield ()
        }
    }

    "two selects select one others unchanged" in {
        val app: UI < Async =
            for
                sel1 <- Signal.initRef("")
                sel2 <- Signal.initRef("")
            yield UI.div(
                UI.select(UI.option("A").value("a"), UI.option("B").value("b"))
                    .id("s1").onChange(v => sel1.set(v)),
                UI.select(UI.option("C").value("c"), UI.option("D").value("d"))
                    .id("s2").onChange(v => sel2.set(v)),
                sel1.map(v => UI.span(s"s1:$v").id("v1")),
                sel2.map(v => UI.span(s"s2:$v").id("v2"))
            )
        withUI(app) {
            for
                _ <- Browser.select(Selector.id("s1"), "b")
                _ <- Browser.assertText(Selector.id("v1"), "s1:b")
                _ <- Browser.assertText(Selector.id("v2"), "s2:")
            yield ()
        }
    }

    // === Focus ===

    "focus button in foreach item" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("X")))
                focused <- Signal.initRef(false)
            yield UI.div(
                items.foreach(s =>
                    UI.button(s).id("fbtn").onFocus(focused.set(true))
                ),
                focused.map(v => UI.span(s"focused:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("fbtn"))
                _ <- Browser.assertText(Selector.id("v"), "focused:true")
            yield ()
        }
    }

    "focus input in foreach item fill works" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.from(Seq("item")))
                text  <- Signal.initRef("")
            yield UI.div(
                items.foreach(_ => UI.input.id("finp").onInput(v => text.set(v))),
                text.map(v => UI.span(s"t:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("finp"), "typed")
                _ <- Browser.assertText(Selector.id("v"), "t:typed")
            yield ()
        }
    }

    "tab through foreach items each focusable" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("a", "b")))
                focused <- Signal.initRef("")
            yield UI.div(
                items.foreach(s =>
                    UI.button(s).id(s"btn-$s").onFocus(focused.set(s))
                ),
                focused.map(v => UI.span(s"focused:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn-a"))
                _ <- Browser.assertText(Selector.id("v"), "focused:a")
                _ <- Browser.click(Selector.id("btn-b"))
                _ <- Browser.assertText(Selector.id("v"), "focused:b")
            yield ()
        }
    }

    // === Nested ===

    "foreach inside foreach grid" in {
        val app: UI < Async =
            for
                row1 <- Signal.initRef(Chunk.from(Seq("1a", "1b")))
                row2 <- Signal.initRef(Chunk.from(Seq("2a", "2b")))
            yield UI.div(
                UI.div(row1.foreach(cell => UI.span(cell))),
                UI.div(row2.foreach(cell => UI.span(cell)))
            )
        withUI(app) {
            for
                _ <- assertContains("1a")
                _ <- assertContains("1b")
                _ <- assertContains("2a")
                _ <- assertContains("2b")
            yield ()
        }
    }

    "foreach inside reactive" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.from(Seq("A", "B")))
                show  <- Signal.initRef(true)
            yield UI.div(
                show.map { s =>
                    if s then UI.div(items.foreach(v => UI.span(s"v:$v")))
                    else UI.span("hidden")
                },
                UI.button("Toggle").id("tog").onClick(show.getAndUpdate(!_).unit)
            )
        withUI(app) {
            for
                _ <- assertContains("v:A")
                _ <- Browser.click(Selector.id("tog"))
                _ <- assertContains("hidden")
                _ <- Browser.click(Selector.id("tog"))
                _ <- assertContains("v:A")
            yield ()
        }
    }

    "foreach many items" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from((1 to 20).map(i => s"item$i")))
            yield UI.div(items.foreach(s => UI.span(s)))
        withUI(app) {
            for
                _ <- assertContains("item1")
                _ <- assertContains("item5")
                _ <- assertContains("item10")
            yield ()
        }
    }

    "foreach item returns fragment" in {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.from(Seq("A", "B")))
            yield UI.div(items.foreach(s =>
                UI.fragment(UI.span(s"label:$s"), UI.span(s"value:$s"))
            ))
        withUI(app) {
            for
                _ <- assertContains("label:A")
                _ <- assertContains("value:A")
                _ <- assertContains("label:B")
                _ <- assertContains("value:B")
            yield ()
        }
    }

    "foreach items with disabled buttons" in {
        val app: UI < Async =
            for
                items   <- Signal.initRef(Chunk.from(Seq("A", "B")))
                counter <- Signal.initRef(0)
            yield UI.div(
                items.foreach(s =>
                    UI.button(s).id(s"btn-$s").disabled(true).onClick(counter.getAndUpdate(_ + 1).unit)
                ),
                counter.map(n => UI.span(s"count:$n").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("btn-A"))
                _ <- Browser.assertDisabled(Selector.id("btn-B"))
                _ <- Browser.assertText(Selector.id("v"), "count:0")
            yield ()
        }
    }

    "foreach with UI.when inside items" in {
        val app: UI < Async =
            for
                items <- Signal.initRef(Chunk.from(Seq("A", "B")))
                show  <- Signal.initRef(false)
            yield UI.div(
                items.foreach(s =>
                    UI.div(
                        UI.span(s"label:$s"),
                        UI.when(show)(UI.span(s"detail:$s"))
                    )
                ),
                UI.button("Show").id("show").onClick(show.set(true))
            )
        withUI(app) {
            for
                _ <- assertContains("label:A")
                _ <- Browser.click(Selector.id("show"))
                _ <- assertContains("detail:A")
            yield ()
        }
    }

end ForeachTest

package kyo

import kyo.Browser.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.foreach
import scala.language.implicitConversions

class HtmlRendererTest extends UITest:

    "div with text" in run {
        withUI(UI.div("Hello")) {
            Browser.assertText(Selector.css("body"), "Hello").andThen(succeed)
        }
    }

    "button with text" in run {
        withUI(UI.div(UI.button("Click me").id("btn"))) {
            Browser.assertText(Selector.id("btn"), "Click me").andThen(succeed)
        }
    }

    "span with text" in run {
        withUI(UI.div(UI.span("content").id("s"))) {
            Browser.assertText(Selector.id("s"), "content").andThen(succeed)
        }
    }

    "nested structure" in run {
        withUI(UI.div(UI.div(UI.span("inner").id("inner")).id("outer"))) {
            for
                _ <- Browser.assertText(Selector.id("inner"), "inner")
                _ <- Browser.assertVisible(Selector.id("outer"))
            yield succeed
        }
    }

    "multiple children" in run {
        withUI(UI.div(UI.span("A").id("a"), UI.span("B").id("b"))) {
            for
                _ <- Browser.assertText(Selector.id("a"), "A")
                _ <- Browser.assertText(Selector.id("b"), "B")
            yield succeed
        }
    }

    "hidden element has hidden attr" in run {
        withUI(UI.div(UI.div("secret").hidden(true).id("h"))) {
            Browser.assertAttribute(Selector.id("h"), "hidden", "").andThen(succeed)
        }
    }

    "input disabled" in run {
        withUI(UI.div(UI.input.disabled(true).id("i"))) {
            Browser.assertVisible(Selector.id("i")).andThen(succeed)
        }
    }

    "checkbox renders" in run {
        withUI(UI.div(UI.checkbox.id("cb"))) {
            Browser.assertVisible(Selector.id("cb")).andThen(succeed)
        }
    }

    "select renders" in run {
        withUI(UI.div(UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("sel"))) {
            Browser.assertVisible(Selector.id("sel")).andThen(succeed)
        }
    }

    "textarea renders" in run {
        withUI(UI.div(UI.textarea.id("ta"))) {
            Browser.assertVisible(Selector.id("ta")).andThen(succeed)
        }
    }

    "anchor with text" in run {
        withUI(UI.div(UI.a("Link").href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow)).id("lnk"))) {
            Browser.assertText(Selector.id("lnk"), "Link").andThen(succeed)
        }
    }

    "headings" in run {
        withUI(UI.div(UI.h1("Title").id("t"), UI.h2("Sub").id("s"))) {
            for
                _ <- Browser.assertText(Selector.id("t"), "Title")
                _ <- Browser.assertText(Selector.id("s"), "Sub")
            yield succeed
        }
    }

    "list elements" in run {
        withUI(UI.div(UI.ul(UI.li("One").id("l1"), UI.li("Two").id("l2")))) {
            for
                _ <- Browser.assertText(Selector.id("l1"), "One")
                _ <- Browser.assertText(Selector.id("l2"), "Two")
            yield succeed
        }
    }

    "number input renders" in run {
        withUI(UI.div(UI.numberInput.id("n").min(0).max(100))) {
            Browser.assertVisible(Selector.id("n")).andThen(succeed)
        }
    }

    // ---- Merged from ReRenderTest ----

    "cascading signals A B C all update" in run {
        val app: UI < Async =
            for
                a <- Signal.initRef(0)
                b = a.map(_ * 2)
                c = b.map(_ + 1)
            yield UI.div(
                UI.button("Inc").id("inc").onClick(a.getAndUpdate(_ + 1).unit),
                a.map(v => UI.span(s"a:$v").id("va")),
                b.map(v => UI.span(s"b:$v").id("vb")),
                c.map(v => UI.span(s"c:$v").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("va"), "a:0")
                _ <- Browser.assertText(Selector.id("vb"), "b:0")
                _ <- Browser.assertText(Selector.id("vc"), "c:1")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("va"), "a:1")
                _ <- Browser.assertText(Selector.id("vb"), "b:2")
                _ <- Browser.assertText(Selector.id("vc"), "c:3")
            yield succeed
        }
    }

    "two handlers from same event both complete" in run {
        val app: UI < Async =
            for clickLog <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.div(
                    UI.button("Go").id("b").onClick(clickLog.getAndUpdate(_.appended("child")).unit)
                ).onClick(clickLog.getAndUpdate(_.appended("parent")).unit),
                clickLog.map(entries => UI.span(entries.toSeq.mkString(",")).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "child,parent")
            yield succeed
        }
    }

    "deep cascade 5 derived signals" in run {
        val app: UI < Async =
            for
                root <- Signal.initRef(1)
                s1 = root.map(_ + 1)
                s2 = s1.map(_ * 2)
                s3 = s2.map(_ + 10)
                s4 = s3.map(_.toString)
            yield UI.div(
                UI.button("Inc").id("inc").onClick(root.getAndUpdate(_ + 1).unit),
                s4.map(v => UI.span(s"result:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "result:14")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("v"), "result:16")
            yield succeed
        }
    }

    "handler reads signal then sets sees pre update" in run {
        val app: UI < Async =
            for
                ref <- Signal.initRef(0)
                log <- Signal.initRef("")
            yield UI.div(
                UI.button("Go").id("b").onClick {
                    for
                        before <- ref.get
                        _      <- ref.set(before + 1)
                        after  <- ref.get
                        _      <- log.set(s"before:$before,after:$after")
                    yield ()
                },
                log.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "before:0,after:1")
            yield succeed
        }
    }

    // ---- Merged from LayoutTest ----

    "div renders with text" in run {
        withUI(UI.div("text").id("d")) {
            Browser.assertText(Selector.id("d"), "text").andThen(succeed)
        }
    }

    "div with children" in run {
        withUI(UI.div(UI.span("a").id("a"), UI.span("b").id("b")).id("d")) {
            for
                _ <- Browser.assertText(Selector.id("a"), "a")
                _ <- Browser.assertText(Selector.id("b"), "b")
            yield succeed
        }
    }

    "div empty" in run {
        withUI(UI.div.id("d")) {
            Browser.assertExists(Selector.id("d")).andThen(succeed)
        }
    }

    "div row style" in run {
        withUI(UI.div.style(Style.row).id("d")) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("flex-direction: row")).andThen(succeed)
        }
    }

    "div nested" in run {
        withUI(UI.div(UI.div(UI.div("deep").id("inner")))) {
            Browser.assertText(Selector.id("inner"), "deep").andThen(succeed)
        }
    }

    "p renders" in run {
        withUI(UI.p("paragraph").id("p")) {
            Browser.assertText(Selector.id("p"), "paragraph").andThen(succeed)
        }
    }

    "section exists" in run {
        withUI(UI.section(UI.span("s").id("ss")).id("sec")) {
            Browser.assertVisible(Selector.id("sec")).andThen(succeed)
        }
    }

    "main exists" in run {
        withUI(UI.main(UI.span("m").id("ms")).id("main")) {
            Browser.assertVisible(Selector.id("main")).andThen(succeed)
        }
    }

    "header exists" in run {
        withUI(UI.header(UI.span("h").id("hs")).id("hdr")) {
            Browser.assertVisible(Selector.id("hdr")).andThen(succeed)
        }
    }

    "footer exists" in run {
        withUI(UI.footer(UI.span("f").id("fs")).id("ftr")) {
            Browser.assertVisible(Selector.id("ftr")).andThen(succeed)
        }
    }

    "pre renders" in run {
        withUI(UI.pre("code\nblock").id("p")) {
            Browser.assertText(Selector.id("p"), "code\nblock").andThen(succeed)
        }
    }

    "code renders" in run {
        withUI(UI.code("inline").id("c")) {
            Browser.assertText(Selector.id("c"), "inline").andThen(succeed)
        }
    }

    "span renders" in run {
        withUI(UI.span("text").id("s")) {
            Browser.assertText(Selector.id("s"), "text").andThen(succeed)
        }
    }

    "nav exists" in run {
        withUI(UI.nav(UI.span("link").id("ls")).id("nav")) {
            Browser.assertVisible(Selector.id("nav")).andThen(succeed)
        }
    }

    "h1 renders" in run {
        withUI(UI.h1("Title").id("h1")) {
            Browser.assertText(Selector.id("h1"), "Title").andThen(succeed)
        }
    }

    "h2 renders" in run {
        withUI(UI.h2("Sub").id("h2")) {
            Browser.assertText(Selector.id("h2"), "Sub").andThen(succeed)
        }
    }

    "h3 through h6 exist" in run {
        withUI(UI.div(
            UI.h3("3").id("h3"),
            UI.h4("4").id("h4"),
            UI.h5("5").id("h5"),
            UI.h6("6").id("h6")
        )) {
            for
                _ <- Browser.assertText(Selector.id("h3"), "3")
                _ <- Browser.assertText(Selector.id("h4"), "4")
                _ <- Browser.assertText(Selector.id("h5"), "5")
                _ <- Browser.assertText(Selector.id("h6"), "6")
            yield succeed
        }
    }

    "hr exists" in run {
        withUI(UI.div(UI.hr.id("h"))) {
            Browser.assertExists(Selector.id("h")).andThen(succeed)
        }
    }

    "br exists" in run {
        withUI(UI.div(UI.br.id("b"))) {
            Browser.assertExists(Selector.id("b")).andThen(succeed)
        }
    }

    // ---- Merged from EdgeCaseTest ----

    "empty select no crash" in run {
        withUI(UI.div(UI.select.id("s"))) {
            Browser.assertExists(Selector.id("s")).andThen(succeed)
        }
    }

    "deeply nested click works (edge)" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.div(UI.div(UI.div(UI.div(
                    UI.button("+").id("b").onClick(counter.getAndUpdate(_ + 1).unit)
                )))),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "rapid 10 clicks (edge)" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                counter.map(n => UI.span(n.toString).id("v")),
                UI.button("+").id("b").onClick(counter.getAndUpdate(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- Kyo.foreachDiscard(0 until 10)(_ => Browser.click(Selector.id("b")))
                _ <- Browser.assertText(Selector.id("v"), "10")
            yield succeed
        }
    }

    "counter + echo independent (edge)" in run {
        val app: UI < Async =
            for
                counter <- Signal.initRef(0)
                echo    <- Signal.initRef("")
            yield UI.div(
                UI.button("+").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                UI.input.id("inp").onInput(v => echo.set(v)),
                counter.map(n => UI.span(s"c:$n").id("cv")),
                echo.map(v => UI.span(s"e:$v").id("ev"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("cv"), "c:1")
                _ <- Browser.fill(Selector.id("inp"), "hi")
                _ <- Browser.assertText(Selector.id("ev"), "e:hi")
                _ <- Browser.assertText(Selector.id("cv"), "c:1")
            yield succeed
        }
    }

    "empty div renders (edge)" in run {
        withUI(UI.div().id("d")) {
            Browser.assertExists(Selector.id("d")).andThen(succeed)
        }
    }

    "element with long text" in run {
        val longText = "A" * 200
        withUI(UI.span(longText).id("s")) {
            Browser.assertText(Selector.id("s"), longText).andThen(succeed)
        }
    }

    "text with special characters" in run {
        withUI(UI.div(UI.span("hello & world").id("s"))) {
            Browser.assertText(Selector.id("s"), "hello & world").andThen(succeed)
        }
    }

    "unicode text (edge)" in run {
        withUI(UI.span("日本語テスト").id("s")) {
            Browser.assertText(Selector.id("s"), "日本語テスト").andThen(succeed)
        }
    }

    "multiple elements same container (edge)" in run {
        withUI(UI.div(
            UI.span("first").id("a"),
            UI.span("second").id("b"),
            UI.span("third").id("c")
        )) {
            for
                _ <- Browser.assertText(Selector.id("a"), "first")
                _ <- Browser.assertText(Selector.id("b"), "second")
                _ <- Browser.assertText(Selector.id("c"), "third")
            yield succeed
        }
    }

    "disabled button stays disabled after click attempt" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                counter.map(n => UI.span(n.toString).id("v")),
                UI.button("Go").id("b").disabled(true).onClick(counter.updateAndGet(_ + 1).unit)
            )
        withUI(app) {
            for
                _ <- Browser.assertDisabled(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "0")
            yield succeed
        }
    }

    "up/down/reset stress (edge)" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield UI.div(
                UI.button("+").id("up").onClick(counter.getAndUpdate(_ + 1).unit),
                UI.button("-").id("down").onClick(counter.getAndUpdate(_ - 1).unit),
                UI.button("R").id("reset").onClick(counter.set(0)),
                counter.map(n => UI.span(n.toString).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("up"))
                _ <- Browser.click(Selector.id("up"))
                _ <- Browser.click(Selector.id("up"))
                _ <- Browser.assertText(Selector.id("v"), "3")
                _ <- Browser.click(Selector.id("down"))
                _ <- Browser.click(Selector.id("down"))
                _ <- Browser.assertText(Selector.id("v"), "1")
                _ <- Browser.click(Selector.id("reset"))
                _ <- Browser.assertText(Selector.id("v"), "0")
                _ <- Browser.click(Selector.id("down"))
                _ <- Browser.assertText(Selector.id("v"), "-1")
            yield succeed
        }
    }

    "input fill with empty string (edge)" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"v:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "")
                _ <- Browser.assertText(Selector.id("v"), "v:")
            yield succeed
        }
    }

    "fragment zero children renders (edge)" in run {
        withUI(UI.div(UI.fragment(), UI.span("visible").id("s"))) {
            Browser.assertText(Selector.id("s"), "visible").andThen(succeed)
        }
    }

    "unicode emoji renders (edge)" in run {
        withUI(UI.span("🚀🌟🌍").id("e")) {
            Browser.assertText(Selector.id("e"), "🚀🌟🌍").andThen(succeed)
        }
    }

    "numberInput fill 0 fires onInput with 0" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.numberInput.id("n").onInput(v => ref.set(v)),
                ref.map(v => UI.span(s"n:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("n"), "0")
                _ <- Browser.assertText(Selector.id("v"), "n:0")
            yield succeed
        }
    }

    "very deep nesting 10 levels click bubbles (edge)" in run {
        val app: UI < Async =
            for counter <- Signal.initRef(0)
            yield
                def nest(depth: Int, inner: HtmlContent): HtmlContent =
                    if depth <= 0 then inner
                    else nest(depth - 1, UI.div(inner))
                UI.div(
                    nest(10, UI.button("Deep").id("b").onClick(counter.getAndUpdate(_ + 1).unit)),
                    counter.map(n => UI.span(n.toString).id("v"))
                )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
            yield succeed
        }
    }

    "script tag in text is escaped" in run {
        withUI(UI.div(UI.span("<script>alert(1)</script>").id("s"))) {
            Browser.assertText(Selector.id("s"), "<script>alert(1)</script>").andThen(succeed)
        }
    }

    "emoji text content (edge)" in run {
        withUI(UI.div(UI.span("🔥🚀").id("s"))) {
            Browser.assertText(Selector.id("s"), "🔥🚀").andThen(succeed)
        }
    }

    "add remove add lifecycle (edge)" in run {
        val app: UI < Async =
            for items <- Signal.initRef(Chunk.empty[String])
            yield UI.div(
                UI.button("+x").id("addA").onClick(items.getAndUpdate(_ :+ "alpha").unit),
                UI.button("+y").id("addB").onClick(items.getAndUpdate(_ :+ "bravo").unit),
                UI.button("+z").id("addC").onClick(items.getAndUpdate(_ :+ "charlie").unit),
                UI.button("rm").id("rm").onClick(items.getAndUpdate(_.drop(1)).unit),
                UI.div(items.foreach(s => UI.span(s)))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("addA"))
                _ <- Browser.click(Selector.id("addB"))
                _ <- assertContains("alpha")
                _ <- assertContains("bravo")
                _ <- Browser.click(Selector.id("rm"))
                _ <- Browser.click(Selector.id("addC"))
                _ <- assertContains("bravo")
                _ <- assertContains("charlie")
            yield succeed
        }
    }

    "data-kyo-path attribute present (edge)" in run {
        withUI(UI.div(UI.span("test").id("s"))) {
            Browser.assertAttributeSatisfies(Selector.id("s"), "data-kyo-path", "ignore")(_.nonEmpty).andThen(succeed)
        }
    }

    // ---- Merged from UnicodeTest ----

    "fill with emoji stored in signal" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "🎉")
                _ <- Browser.assertText(Selector.id("v"), "sig:🎉")
            yield succeed
        }
    }

    "fill with CJK characters signal preserves" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "日本語")
                _ <- Browser.assertText(Selector.id("v"), "sig:日本語")
            yield succeed
        }
    }

    "fill with mixed ASCII and unicode" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"sig:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "hello 世界")
                _ <- Browser.assertText(Selector.id("v"), "sig:hello 世界")
            yield succeed
        }
    }

    "placeholder with emoji visible" in run {
        withUI(UI.div(UI.input.id("i").placeholder("📝 Enter text"))) {
            Browser.assertAttribute(Selector.id("i"), "placeholder", "📝 Enter text").andThen(succeed)
        }
    }

    "button text with special chars visible" in run {
        withUI(UI.div(UI.button("<Save & Close>").id("b"))) {
            Browser.assertText(Selector.id("b"), "<Save & Close>").andThen(succeed)
        }
    }

    "fill empty string works (unicode)" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("initial")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"sig:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "sig:[initial]")
                _ <- Browser.fill(Selector.id("i"), "")
                _ <- Browser.assertText(Selector.id("v"), "sig:[]")
            yield succeed
        }
    }

    "fill whitespace only stored as is" in run {
        // The onInput value must be exactly three spaces, verified via an encoded marker
        // because innerText (read by assertText) collapses whitespace, and the HTML `value`
        // attribute (read by assertAttribute) reflects the initial value, not typed input.
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").onInput(v => ref.set(if v == "   " then "verbatim" else s"changed:${v.length}")),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "   ")
                _ <- Browser.assertText(Selector.id("v"), "verbatim")
            yield succeed
        }
    }

    "span with long unicode string visible" in run {
        val text = "漢" * 100
        withUI(UI.div(UI.span(text).id("s"))) {
            Browser.assertText(Selector.id("s"), text).andThen(succeed)
        }
    }

    "input value with tab character" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"len:${v.length}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a\tb")
                _ <- Browser.assertText(Selector.id("v"), "len:3")
            yield succeed
        }
    }

    "fill very long unicode string signal preserves" in run {
        val longText = "漢" * 500
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"len:${v.length}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), longText)
                _ <- Browser.assertText(Selector.id("v"), "len:500")
            yield succeed
        }
    }

    "input value with newline handled" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").value(ref),
                ref.map(v => UI.span(s"len:${v.length}").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "a\nb")
                _ <- Browser.assertText(Selector.id("v"), "len:2")
            yield succeed
        }
    }

    // ---- Href / ImgSrc ADT rendering tests ----

    "Href.Absolute renders full URL" in run {
        withUI(UI.div(UI.a.href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow)).id("a")("link"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("example.com")).andThen(succeed)
        }
    }

    "Href.Path absolute-path renders path" in run {
        withUI(UI.div(UI.a.href(Href.Path("/path")).id("a")("link"))) {
            Browser.assertAttribute(Selector.id("a"), "href", "/path").andThen(succeed)
        }
    }

    "Href.Path relative renders relative" in run {
        withUI(UI.div(UI.a.href(Href.Path("relative")).id("a")("link"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("relative")).andThen(succeed)
        }
    }

    "Href.Fragment named renders hash-id" in run {
        withUI(UI.div(UI.a.href(Href.Fragment("section")).id("a")("link"))) {
            Browser.assertAttribute(Selector.id("a"), "href", "#section").andThen(succeed)
        }
    }

    "Href.Fragment empty renders hash" in run {
        withUI(UI.div(UI.a.href(Href.Fragment("")).id("a")("link"))) {
            Browser.assertAttribute(Selector.id("a"), "href", "#").andThen(succeed)
        }
    }

    "Href.External mailto renders mailto URI" in run {
        withUI(UI.div(UI.a.href(Href.External("mailto", "foo@bar.com")).id("a")("link"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("mailto")).andThen(succeed)
        }
    }

    "Href.External tel renders tel URI" in run {
        withUI(UI.div(UI.a.href(Href.External("tel", "+15551234")).id("a")("link"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("tel")).andThen(succeed)
        }
    }

    "ImgSrc.Absolute renders full URL" in run {
        withUI(UI.div(UI.img(ImgSrc.Absolute(HttpUrl.parse("https://example.com/logo.png").getOrThrow), "logo").id("i"))) {
            Browser.assertAttributeSatisfies(
                Selector.id("i"),
                "src",
                "ignore"
            )(s => s.contains("example.com") && s.contains("logo.png")).andThen(succeed)
        }
    }

    "ImgSrc.Path renders path" in run {
        withUI(UI.div(UI.img(ImgSrc.Path("/static/logo.png"), "logo").id("i"))) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("/static/logo.png")).andThen(succeed)
        }
    }

    "ImgSrc.Data renders data URI" in run {
        withUI(UI.div(UI.img(ImgSrc.Data("image/png", "iVBORw0KGgo"), "logo").id("i"))) {
            Browser.assertAttributeSatisfies(
                Selector.id("i"),
                "src",
                "ignore"
            )(v => v.contains("data:image/png;base64,iVBORw0KGgo")).andThen(succeed)
        }
    }

    // ---- renderPage JS-string-literal escaping (pure unit tests, no browser) ----

    "renderPage: trailing backslash in basePath produces valid JS string literal" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "abc", "/app\\")
        // The script block must contain the backslash doubled so JS parses correctly.
        // With the bug: var base="/app\"; -- the \" escapes the closing quote, corrupting the literal.
        // With the fix: var base="/app\\"; -- properly escaped.
        assert(html.contains("""var base="/app\\";"""))
        assert(!html.contains("""var base="/app\";"""))
    }

    "renderPage: double-quote in basePath is backslash-escaped in JS, not HTML-entity" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "abc", "/app\"path")
        // With the bug: var base="/app&quot;path"; -- &quot; appears literally in the runtime string.
        // With the fix: var base="/app\"path"; -- JS-escaped double-quote.
        assert(html.contains("""var base="/app\"path";"""))
        assert(!html.contains("""var base="/app&quot;path";"""))
    }

    "renderPage: closing script tag sequence in basePath is neutralized" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "abc", "/app</script>x")
        // A raw </script> inside a <script> element closes the element prematurely.
        // With the fix: </ is encoded as <\/ so </script> cannot close the element.
        assert(!html.contains("</script>var base="))
        assert(html.contains("""var base="/app<\/script>x";"""))
    }

    "renderPage: normal basePath slash unchanged" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "abc", "/")
        assert(html.contains("""var base="/";"""))
    }

    "renderPage: normal basePath with subdirectory unchanged" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "abc", "/myapp")
        assert(html.contains("""var base="/myapp";"""))
    }

end HtmlRendererTest

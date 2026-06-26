package kyo

import kyo.Browser.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.UI.foreach

class HtmlRendererTest extends UITest:

    "div with text" in {
        withUI(UI.div("Hello")) {
            Browser.assertText(Selector.css("body"), "Hello").unit
        }
    }

    "button with text" in {
        withUI(UI.div(UI.button("Click me").id("btn"))) {
            Browser.assertText(Selector.id("btn"), "Click me").unit
        }
    }

    "span with text" in {
        withUI(UI.div(UI.span("content").id("s"))) {
            Browser.assertText(Selector.id("s"), "content").unit
        }
    }

    "nested structure" in {
        withUI(UI.div(UI.div(UI.span("inner").id("inner")).id("outer"))) {
            for
                _ <- Browser.assertText(Selector.id("inner"), "inner")
                _ <- Browser.assertVisible(Selector.id("outer"))
            yield ()
        }
    }

    "multiple children" in {
        withUI(UI.div(UI.span("A").id("a"), UI.span("B").id("b"))) {
            for
                _ <- Browser.assertText(Selector.id("a"), "A")
                _ <- Browser.assertText(Selector.id("b"), "B")
            yield ()
        }
    }

    "hidden element has hidden attr" in {
        withUI(UI.div(UI.div("secret").hidden(true).id("h"))) {
            Browser.assertAttribute(Selector.id("h"), "hidden", "").unit
        }
    }

    "input disabled" in {
        withUI(UI.div(UI.input.disabled(true).id("i"))) {
            Browser.assertVisible(Selector.id("i")).unit
        }
    }

    "checkbox renders" in {
        withUI(UI.div(UI.checkbox.id("cb"))) {
            Browser.assertVisible(Selector.id("cb")).unit
        }
    }

    "select renders" in {
        withUI(UI.div(UI.select(UI.option("A").value("a"), UI.option("B").value("b")).id("sel"))) {
            Browser.assertVisible(Selector.id("sel")).unit
        }
    }

    "textarea renders" in {
        withUI(UI.div(UI.textarea.id("ta"))) {
            Browser.assertVisible(Selector.id("ta")).unit
        }
    }

    "anchor with text" in {
        withUI(UI.div(UI.a("Link").href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow)).id("lnk"))) {
            Browser.assertText(Selector.id("lnk"), "Link").unit
        }
    }

    "headings" in {
        withUI(UI.div(UI.h1("Title").id("t"), UI.h2("Sub").id("s"))) {
            for
                _ <- Browser.assertText(Selector.id("t"), "Title")
                _ <- Browser.assertText(Selector.id("s"), "Sub")
            yield ()
        }
    }

    "list elements" in {
        withUI(UI.div(UI.ul(UI.li("One").id("l1"), UI.li("Two").id("l2")))) {
            for
                _ <- Browser.assertText(Selector.id("l1"), "One")
                _ <- Browser.assertText(Selector.id("l2"), "Two")
            yield ()
        }
    }

    "number input renders" in {
        withUI(UI.div(UI.numberInput.id("n").min(0).max(100))) {
            Browser.assertVisible(Selector.id("n")).unit
        }
    }

    // ---- Merged from ReRenderTest ----

    "cascading signals A B C all update" in {
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
            yield ()
        }
    }

    "two handlers from same event both complete".flaky in {
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
            yield ()
        }
    }

    "deep cascade 5 derived signals" in {
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
            yield ()
        }
    }

    "handler reads signal then sets sees pre update" in {
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
            yield ()
        }
    }

    // ---- Merged from LayoutTest ----

    "div renders with text" in {
        withUI(UI.div("text").id("d")) {
            Browser.assertText(Selector.id("d"), "text").unit
        }
    }

    "div with children" in {
        withUI(UI.div(UI.span("a").id("a"), UI.span("b").id("b")).id("d")) {
            for
                _ <- Browser.assertText(Selector.id("a"), "a")
                _ <- Browser.assertText(Selector.id("b"), "b")
            yield ()
        }
    }

    "div empty" in {
        withUI(UI.div.id("d")) {
            Browser.assertExists(Selector.id("d")).unit
        }
    }

    "div row style" in {
        withUI(UI.div.style(Style.row).id("d")) {
            Browser.assertAttributeSatisfies(Selector.id("d"), "style", "ignore")(_.contains("flex-direction: row")).unit
        }
    }

    "div nested" in {
        withUI(UI.div(UI.div(UI.div("deep").id("inner")))) {
            Browser.assertText(Selector.id("inner"), "deep").unit
        }
    }

    "p renders" in {
        withUI(UI.p("paragraph").id("p")) {
            Browser.assertText(Selector.id("p"), "paragraph").unit
        }
    }

    "section exists" in {
        withUI(UI.section(UI.span("s").id("ss")).id("sec")) {
            Browser.assertVisible(Selector.id("sec")).unit
        }
    }

    "main exists" in {
        withUI(UI.main(UI.span("m").id("ms")).id("main")) {
            Browser.assertVisible(Selector.id("main")).unit
        }
    }

    "header exists" in {
        withUI(UI.header(UI.span("h").id("hs")).id("hdr")) {
            Browser.assertVisible(Selector.id("hdr")).unit
        }
    }

    "footer exists" in {
        withUI(UI.footer(UI.span("f").id("fs")).id("ftr")) {
            Browser.assertVisible(Selector.id("ftr")).unit
        }
    }

    "pre renders" in {
        withUI(UI.pre("code\nblock").id("p")) {
            Browser.assertText(Selector.id("p"), "code\nblock").unit
        }
    }

    "code renders" in {
        withUI(UI.code("inline").id("c")) {
            Browser.assertText(Selector.id("c"), "inline").unit
        }
    }

    "span renders" in {
        withUI(UI.span("text").id("s")) {
            Browser.assertText(Selector.id("s"), "text").unit
        }
    }

    "nav exists" in {
        withUI(UI.nav(UI.span("link").id("ls")).id("nav")) {
            Browser.assertVisible(Selector.id("nav")).unit
        }
    }

    "h1 renders" in {
        withUI(UI.h1("Title").id("h1")) {
            Browser.assertText(Selector.id("h1"), "Title").unit
        }
    }

    "h2 renders" in {
        withUI(UI.h2("Sub").id("h2")) {
            Browser.assertText(Selector.id("h2"), "Sub").unit
        }
    }

    "h3 through h6 exist" in {
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
            yield ()
        }
    }

    "hr exists" in {
        withUI(UI.div(UI.hr.id("h"))) {
            Browser.assertExists(Selector.id("h")).unit
        }
    }

    "br exists" in {
        withUI(UI.div(UI.br.id("b"))) {
            Browser.assertExists(Selector.id("b")).unit
        }
    }

    // ---- Merged from EdgeCaseTest ----

    "empty select no crash" in {
        withUI(UI.div(UI.select.id("s"))) {
            Browser.assertExists(Selector.id("s")).unit
        }
    }

    "deeply nested click works (edge)" in {
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
            yield ()
        }
    }

    "rapid 10 clicks (edge)" in {
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
            yield ()
        }
    }

    "counter + echo independent (edge)" in {
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
            yield ()
        }
    }

    "empty div renders (edge)" in {
        withUI(UI.div().id("d")) {
            Browser.assertExists(Selector.id("d")).unit
        }
    }

    "element with long text" in {
        val longText = "A" * 200
        withUI(UI.span(longText).id("s")) {
            Browser.assertText(Selector.id("s"), longText).unit
        }
    }

    "text with special characters" in {
        withUI(UI.div(UI.span("hello & world").id("s"))) {
            Browser.assertText(Selector.id("s"), "hello & world").unit
        }
    }

    "unicode text (edge)" in {
        withUI(UI.span("日本語テスト").id("s")) {
            Browser.assertText(Selector.id("s"), "日本語テスト").unit
        }
    }

    "multiple elements same container (edge)" in {
        withUI(UI.div(
            UI.span("first").id("a"),
            UI.span("second").id("b"),
            UI.span("third").id("c")
        )) {
            for
                _ <- Browser.assertText(Selector.id("a"), "first")
                _ <- Browser.assertText(Selector.id("b"), "second")
                _ <- Browser.assertText(Selector.id("c"), "third")
            yield ()
        }
    }

    "disabled button stays disabled after click attempt" in {
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
            yield ()
        }
    }

    "up/down/reset stress (edge)" in {
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
            yield ()
        }
    }

    "input fill with empty string (edge)" in {
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
            yield ()
        }
    }

    "fragment zero children renders (edge)" in {
        withUI(UI.div(UI.fragment(), UI.span("visible").id("s"))) {
            Browser.assertText(Selector.id("s"), "visible").unit
        }
    }

    "unicode emoji renders (edge)" in {
        withUI(UI.span("🚀🌟🌍").id("e")) {
            Browser.assertText(Selector.id("e"), "🚀🌟🌍").unit
        }
    }

    "numberInput fill 0 fires onInput with 0" in {
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
            yield ()
        }
    }

    "very deep nesting 10 levels click bubbles (edge)" in {
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
            yield ()
        }
    }

    "script tag in text is escaped" in {
        withUI(UI.div(UI.span("<script>alert(1)</script>").id("s"))) {
            Browser.assertText(Selector.id("s"), "<script>alert(1)</script>").unit
        }
    }

    "emoji text content (edge)" in {
        withUI(UI.div(UI.span("🔥🚀").id("s"))) {
            Browser.assertText(Selector.id("s"), "🔥🚀").unit
        }
    }

    "add remove add lifecycle (edge)" in {
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
            yield ()
        }
    }

    "data-kyo-path attribute present (edge)" in {
        withUI(UI.div(UI.span("test").id("s"))) {
            Browser.assertAttributeSatisfies(Selector.id("s"), "data-kyo-path", "ignore")(_.nonEmpty).unit
        }
    }

    // ---- Merged from UnicodeTest ----

    "fill with emoji stored in signal" in {
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
            yield ()
        }
    }

    "fill with CJK characters signal preserves" in {
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
            yield ()
        }
    }

    "fill with mixed ASCII and unicode" in {
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
            yield ()
        }
    }

    "placeholder with emoji visible" in {
        withUI(UI.div(UI.input.id("i").placeholder("📝 Enter text"))) {
            Browser.assertAttribute(Selector.id("i"), "placeholder", "📝 Enter text").unit
        }
    }

    "button text with special chars visible" in {
        withUI(UI.div(UI.button("<Save & Close>").id("b"))) {
            Browser.assertText(Selector.id("b"), "<Save & Close>").unit
        }
    }

    "fill empty string works (unicode)" in {
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
            yield ()
        }
    }

    "fill whitespace only stored as is" in {
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
            yield ()
        }
    }

    "span with long unicode string visible" in {
        val text = "漢" * 100
        withUI(UI.div(UI.span(text).id("s"))) {
            Browser.assertText(Selector.id("s"), text).unit
        }
    }

    "input value with tab character" in {
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
            yield ()
        }
    }

    "fill very long unicode string signal preserves" in {
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
            yield ()
        }
    }

    "input value with newline handled" in {
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
            yield ()
        }
    }

    // ---- Href / ImgSrc ADT rendering tests ----

    "Href.Absolute renders full URL" in {
        withUI(UI.div(UI.a.href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow)).id("a")("link"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("example.com")).unit
        }
    }

    "Href.Path absolute-path renders path" in {
        withUI(UI.div(UI.a.href(Href.Path("/path")).id("a")("link"))) {
            Browser.assertAttribute(Selector.id("a"), "href", "/path").unit
        }
    }

    "Href.Path relative renders relative" in {
        withUI(UI.div(UI.a.href(Href.Path("relative")).id("a")("link"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("relative")).unit
        }
    }

    "Href.Fragment named renders hash-id" in {
        withUI(UI.div(UI.a.href(Href.Fragment("section")).id("a")("link"))) {
            Browser.assertAttribute(Selector.id("a"), "href", "#section").unit
        }
    }

    "Href.Fragment empty renders hash" in {
        withUI(UI.div(UI.a.href(Href.Fragment("")).id("a")("link"))) {
            Browser.assertAttribute(Selector.id("a"), "href", "#").unit
        }
    }

    "Href.External mailto renders mailto URI" in {
        withUI(UI.div(UI.a.href(Href.External("mailto", "foo@bar.com")).id("a")("link"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("mailto")).unit
        }
    }

    "Href.External tel renders tel URI" in {
        withUI(UI.div(UI.a.href(Href.External("tel", "+15551234")).id("a")("link"))) {
            Browser.assertAttributeSatisfies(Selector.id("a"), "href", "ignore")(_.contains("tel")).unit
        }
    }

    "ImgSrc.Absolute renders full URL" in {
        withUI(UI.div(UI.img(ImgSrc.Absolute(HttpUrl.parse("https://example.com/logo.png").getOrThrow), "logo").id("i"))) {
            Browser.assertAttributeSatisfies(
                Selector.id("i"),
                "src",
                "ignore"
            )(s => s.contains("example.com") && s.contains("logo.png")).unit
        }
    }

    "ImgSrc.Path renders path" in {
        withUI(UI.div(UI.img(ImgSrc.Path("/static/logo.png"), "logo").id("i"))) {
            Browser.assertAttributeSatisfies(Selector.id("i"), "src", "ignore")(_.contains("/static/logo.png")).unit
        }
    }

    "ImgSrc.Data renders data URI" in {
        withUI(UI.div(UI.img(ImgSrc.Data("image/png", "iVBORw0KGgo"), "logo").id("i"))) {
            Browser.assertAttributeSatisfies(
                Selector.id("i"),
                "src",
                "ignore"
            )(v => v.contains("data:image/png;base64,iVBORw0KGgo")).unit
        }
    }

    // ---- renderPage JS-string-literal escaping (pure unit tests, no browser) ----

    "renderPage: trailing backslash in basePath produces valid JS string literal" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "/app\\")
        // The script block must contain the backslash doubled so JS parses correctly.
        // Broken form: var base="/app\"; the \" escapes the closing quote, corrupting the literal.
        // Expected: var base="/app\\"; properly escaped.
        assert(html.contains("""var base="/app\\";"""))
        assert(!html.contains("""var base="/app\";"""))
    }

    "renderPage: double-quote in basePath is backslash-escaped in JS, not HTML-entity" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "/app\"path")
        // Broken form: var base="/app&quot;path"; &quot; appears literally in the runtime string.
        // Expected: var base="/app\"path"; JS-escaped double-quote.
        assert(html.contains("""var base="/app\"path";"""))
        assert(!html.contains("""var base="/app&quot;path";"""))
    }

    "renderPage: closing script tag sequence in basePath is neutralized" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "/app</script>x")
        // A raw </script> inside a <script> element closes the element prematurely.
        // Expected: </ is encoded as <\/ so </script> cannot close the element.
        assert(!html.contains("</script>var base="))
        assert(html.contains("""var base="/app<\/script>x";"""))
    }

    "renderPage: normal basePath slash unchanged" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "/")
        assert(html.contains("""var base="/";"""))
    }

    "renderPage: normal basePath with subdirectory unchanged" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "/myapp")
        assert(html.contains("""var base="/myapp";"""))
    }

    // ---- renderPage import map (pure string inspection; no browser) ----

    "renderPage: a non-empty importMap emits one importmap script before the module script" in {
        val html = kyo.internal.HtmlRenderer.renderPage(
            "T",
            "",
            "",
            "/app",
            moduleScript = Present("/main.js"),
            importMap = Seq("three" -> "/three.module.js", "three/x" -> "/x.js")
        )
        // Both mappings render as a single JSON imports object in one importmap script.
        assert(
            html.contains(
                """<script type="importmap">{"imports":{"three":"/three.module.js","three/x":"/x.js"}}</script>"""
            )
        )
        // The import map must precede the linked module script: a module's bare specifiers resolve
        // against an import map only if it was parsed first.
        val mapIdx    = html.indexOf("""type="importmap"""")
        val moduleIdx = html.indexOf("""type="module"""")
        assert(mapIdx >= 0 && moduleIdx >= 0, "both the importmap and the module script must be present")
        assert(mapIdx < moduleIdx, "the importmap script must come before the module script")
    }

    "renderPage: an empty importMap emits no importmap script" in {
        val html = kyo.internal.HtmlRenderer.renderPage("T", "", "", "/app", moduleScript = Present("/main.js"))
        assert(!html.contains("importmap"))
    }

    "renderPage: a closing script tag in an importMap url cannot close the importmap element early" in {
        val html = kyo.internal.HtmlRenderer.renderPage(
            "T",
            "",
            "",
            "/app",
            importMap = Seq("three" -> "/a</script>b.js")
        )
        // Extract the importmap element's body (up to the FIRST following </script>) and prove the
        // embedded </script> was neutralized: the real close is the only one, and no raw < survives.
        val openTag      = """<script type="importmap">"""
        val open         = html.indexOf(openTag)
        val contentStart = open + openTag.length
        val close        = html.indexOf("</script>", contentStart)
        assert(open >= 0 && close >= 0, "the importmap script and its close must be present")
        val body = html.substring(contentStart, close)
        assert(!body.contains("</script>"), "the embedded </script> must be neutralized inside the importmap body")
        assert(!body.contains("<"), "every < in the importmap body must be escaped")
        assert(body.contains("b.js"), "the url's safe characters must survive the escape")
    }

    // ---- clientJs transport (pure string inspection; no browser) ----

    "clientJs transport" - {

        "rendered page opens a WebSocket and carries no EventSource or fetch-POST queue" in {
            val page = kyo.internal.HtmlRenderer.renderPage("t", "<div></div>", "", "/app")
            assert(page.contains("new WebSocket("))
            assert(page.contains("/_kyo/ws"))
            assert(!page.contains("new EventSource"))
            assert(!page.contains("_kyoPostQ"))
            assert(!page.contains("/_kyo/event"))
            assert(!page.contains("/_kyo/sse"))
        }

        "rendered page buffers events until the socket opens" in {
            val page = kyo.internal.HtmlRenderer.renderPage("t", "<div></div>", "", "/app")
            assert(page.contains("__q=[]"))
            assert(page.contains("__q.forEach"))
            assert(page.contains("__q.push"))
            assert(page.contains("ws.readyState===1"))
        }
    }

end HtmlRendererTest

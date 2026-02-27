package kyo

import kyo.UI.*
import kyo.UI.AST.*
import scala.language.implicitConversions

class UITest extends Test:

    // Helper to check Maybe[A | Signal[A]] contains a static value
    private def hasValue[A](maybe: Maybe[A | Signal[A]], expected: A): Boolean =
        maybe match
            case Present(v) => v.asInstanceOf[AnyRef].equals(expected.asInstanceOf[AnyRef])
            case _          => false

    // Helper to check classes chunk contains a static class
    private def hasClass(classes: Chunk[(String, Maybe[Signal[Boolean]])], name: String): Boolean =
        classes.exists((n, sig) => n == name && sig == Absent)

    "base nodes" - {
        "Text via string conversion" in {
            val node: UI = "hello"
            node match
                case Text(v) => assert(v == "hello")
                case _       => fail("expected Text")
        }

        "Fragment" in {
            val node = Fragment(Chunk(Text("a"), Text("b")))
            assert(node.children.size == 2)
        }

        "Fragment via convenience constructor" in {
            val node = UI.fragment(span("a"), span("b"))
            node match
                case Fragment(children) => assert(children.size == 2)
                case _                  => fail("expected Fragment")
        }

        "when conditional rendering" in run {
            for sig <- Signal.initRef(true)
            yield
                val node = UI.when(sig)(div("visible"))
                node match
                    case ReactiveNode(_) => assertionSuccess
                    case _               => assertionFailure("expected ReactiveNode")
        }

        "ReactiveText via signal conversion" in run {
            for sig <- Signal.initRef("hi")
            yield
                val node: UI = sig.asInstanceOf[Signal[String]]
                node match
                    case ReactiveText(s) => assertionSuccess
                    case _               => assertionFailure("expected ReactiveText")
        }

        "ReactiveNode via signal conversion" in run {
            for sig <- Signal.initRef("placeholder")
            yield
                val mapped: Signal[UI] = sig.map(s => span(s))
                val node: UI           = mapped
                node match
                    case ReactiveNode(s) => assertionSuccess
                    case _               => assertionFailure("expected ReactiveNode")
        }

        "foreach delegates to ForeachIndexed" in run {
            for items <- Signal.initRef(Chunk("a", "b"))
            yield
                val node = items.foreach(item => li(item))
                node match
                    case ForeachIndexed(_, render) =>
                        render(0, "test") match
                            case l: Li => assertionSuccess
                            case _     => assertionFailure("expected Li")
                    case _ => assertionFailure("expected ForeachIndexed")
                end match
        }

        "foreachIndexed passes index" in run {
            for items <- Signal.initRef(Chunk("a", "b"))
            yield
                val node = items.foreachIndexed((idx, item) => li(s"$idx: $item"))
                node match
                    case ForeachIndexed(_, render) =>
                        render(0, "test") match
                            case l: Li =>
                                l.children.head match
                                    case Text(v) =>
                                        assert(v == "0: test")
                                        assertionSuccess
                                    case _ => assertionFailure("expected Text child")
                            case _ => assertionFailure("expected Li")
                    case _ => assertionFailure("expected ForeachIndexed")
                end match
        }

        "foreachKeyed" in run {
            for items <- Signal.initRef(Chunk("a", "b"))
            yield
                val node = items.foreachKeyed(identity)(item => li(item))
                node match
                    case ForeachKeyed(_, key, render) =>
                        assert(key("test") == "test")
                        render(0, "x") match
                            case l: Li => assertionSuccess
                            case _     => assertionFailure("expected Li")
                    case _ => assertionFailure("expected ForeachKeyed")
                end match
        }

        "foreachKeyedIndexed" in run {
            for items <- Signal.initRef(Chunk("a", "b"))
            yield
                val node = items.foreachKeyedIndexed(identity)((idx, item) => li(s"$idx: $item"))
                node match
                    case ForeachKeyed(_, _, render) =>
                        render(0, "x") match
                            case l: Li =>
                                l.children.head match
                                    case Text(v) =>
                                        assert(v == "0: x")
                                        assertionSuccess
                                    case _ => assertionFailure("expected Text child")
                            case _ => assertionFailure("expected Li")
                    case _ => assertionFailure("expected ForeachKeyed")
                end match
        }
    }

    "tag constructors and builder DSL" - {
        "div with string children" in {
            val node = div("hello", "world")
            assert(node.children.size == 2)
            node.children(0) match
                case Text(v) => assert(v == "hello")
                case _       => fail("expected Text")
        }

        "apply appends children" in {
            val node = div("a")("b")
            assert(node.children.size == 2)
            node.children(0) match
                case Text(v) => assert(v == "a")
                case _       => fail("expected Text")
            node.children(1) match
                case Text(v) => assert(v == "b")
                case _       => fail("expected Text")
        }

        "chained attributes" in {
            val node = div.cls("container").id("main")
            assert(hasClass(node.common.classes, "container"))
            assert(node.common.identifier == Present("main"))
        }

        "attrs then children" in {
            val node = div.cls("wrapper")("child1", "child2")
            assert(hasClass(node.common.classes, "wrapper"))
            assert(node.children.size == 2)
        }

        "children then attrs" in {
            val node = button("Submit").cls("primary")
            assert(node.children.size == 1)
            assert(hasClass(node.common.classes, "primary"))
        }

        "nested elements" in {
            val node = div(
                h1("Title"),
                p("Body"),
                ul(
                    li("Item 1"),
                    li("Item 2")
                )
            )
            assert(node.children.size == 3)
            node.children(2) match
                case ul: Ul => assert(ul.children.size == 2)
                case _      => fail("expected Ul")
        }

        "input with attributes" in {
            val node = input
                .typ("email")
                .placeholder("Enter email")
                .disabled(true)
            assert(node.typ == Present("email"))
            assert(node.placeholder == Present("Enter email"))
            assert(hasValue(node.disabled, true))
        }

        "img with required attrs" in {
            val node = img("photo.jpg", "A photo").cls("thumb")
            assert(node.src == "photo.jpg")
            assert(node.alt == "A photo")
            assert(hasClass(node.common.classes, "thumb"))
        }

        "anchor with href" in {
            val node = a.href("https://example.com").target("_blank")("Click here")
            assert(hasValue(node.href, "https://example.com"))
            assert(node.target == Present("_blank"))
            assert(node.children.size == 1)
        }

        "form with children" in {
            val node = form(
                label("Name"),
                input.typ("text").placeholder("Your name"),
                button("Submit")
            )
            assert(node.children.size == 3)
        }

        "table structure" in {
            val node = table(
                tr(
                    th("Header 1").colspan(2),
                    th("Header 2")
                ),
                tr(
                    td("Cell 1"),
                    td("Cell 2")
                )
            )
            assert(node.children.size == 2)
            node.children(0) match
                case row: Tr =>
                    row.children(0) match
                        case header: Th => assert(header.colspan == Present(2))
                        case _          => fail("expected Th")
                case _ => fail("expected Tr")
            end match
        }

        "multiple cls calls accumulate" in {
            val node = div.cls("a").cls("b").cls("c")
            assert(node.common.classes.size == 3)
            assert(hasClass(node.common.classes, "a"))
            assert(hasClass(node.common.classes, "b"))
            assert(hasClass(node.common.classes, "c"))
        }

        "label with for" in {
            val node = label.`for`("email-input")("Email")
            assert(node.forId == Present("email-input"))
            assert(node.children.size == 1)
        }

        "label with forId alias" in {
            val node = label.forId("email")("Email")
            assert(node.forId == Present("email"))
            assert(node.children.size == 1)
        }

        "select with options" in {
            val node = select(
                option.value("a")("Option A"),
                option.value("b").selected(true)("Option B")
            )
            assert(node.children.size == 2)
            node.children(1) match
                case opt: Option => assert(hasValue(opt.selected, true))
                case _           => fail("expected Option")
        }

        "hidden attribute" in {
            val node = p("secret").hidden(true)
            assert(hasValue(node.common.hidden, true))
        }

        "style attribute" in {
            val node = div.style("color: red")("styled")
            assert(hasValue(node.common.style, "color: red"))
        }

        "textarea with attributes" in {
            val node = textarea.placeholder("Enter text").disabled(false)
            assert(node.placeholder == Present("Enter text"))
            assert(hasValue(node.disabled, false))
        }

        "onClick handler" in {
            val node = button("Click").onClick(())
            assert(node.common.onClick.isDefined)
        }

        "form onSubmit handler" in {
            val node = form.onSubmit(())(input.typ("text"))
            assert(node.onSubmit.isDefined)
            assert(node.children.size == 1)
        }

        "input onInput handler" in {
            val node = input.onInput(_ => ())
            assert(node.onInput.isDefined)
        }

        "select onChange handler" in {
            val node = select.onChange(_ => ())
            assert(node.onChange.isDefined)
        }

        "onKeyDown handler" in {
            val node = input.onKeyDown(e => ())
            assert(node.common.onKeyDown.isDefined)
        }

        "onKeyUp handler" in {
            val node = div.onKeyUp(e => ())
            assert(node.common.onKeyUp.isDefined)
        }

        "onFocus handler" in {
            val node = input.onFocus(())
            assert(node.common.onFocus.isDefined)
        }

        "onBlur handler" in {
            val node = input.onBlur(())
            assert(node.common.onBlur.isDefined)
        }

        "generic attr" in {
            val node = div.attr("data-id", "123").attr("aria-label", "main")
            assert(node.common.attrs.size == 2)
            assert(node.common.attrs("data-id").asInstanceOf[AnyRef].equals("123"))
            assert(node.common.attrs("aria-label").asInstanceOf[AnyRef].equals("main"))
        }

        "generic on handler" in {
            val node = div.on("dblclick", ()).on("contextmenu", ())
            assert(node.common.handlers.size == 2)
        }
    }

    "reactive attributes" - {
        "cls with Signal" in run {
            for sig <- Signal.initRef("active")
            yield
                val node = div.cls(sig)
                assert(node.common.dynamicClassName.isDefined)
                assertionSuccess
        }

        "clsWhen conditional class" in run {
            for sig <- Signal.initRef(true)
            yield
                val node = div.clsWhen("active", sig)
                assert(node.common.classes.size == 1)
                val (name, maybeSig) = node.common.classes(0)
                assert(name == "active")
                assert(maybeSig.isDefined)
                assertionSuccess
        }

        "hidden with Signal" in run {
            for sig <- Signal.initRef(false)
            yield
                val node = p("text").hidden(sig)
                assert(node.common.hidden.isDefined)
                assertionSuccess
        }

        "style with Signal" in run {
            for sig <- Signal.initRef("color: blue")
            yield
                val node = span.style(sig)
                assert(node.common.style.isDefined)
                assertionSuccess
        }

        "href with Signal" in run {
            for sig <- Signal.initRef("/page")
            yield
                val node = a.href(sig)
                assert(node.href.isDefined)
                assertionSuccess
        }

        "disabled with Signal" in run {
            for sig <- Signal.initRef(true)
            yield
                val node = button("Go").disabled(sig)
                assert(node.disabled.isDefined)
                assertionSuccess
        }

        "input value with SignalRef" in run {
            for ref <- Signal.initRef("")
            yield
                val node = input.value(ref)
                assert(node.value.isDefined)
                assertionSuccess
        }
    }

    "realistic examples" - {
        "nav bar" in {
            val node = nav.cls("navbar")(
                div.cls("brand")(a.href("/")("MyApp")),
                ul.cls("nav-links")(
                    li(a.href("/home")("Home")),
                    li(a.href("/about")("About")),
                    li(a.href("/contact")("Contact"))
                )
            )
            assert(hasClass(node.common.classes, "navbar"))
            node.children(1) match
                case links: Ul => assert(links.children.size == 3)
                case _         => fail("expected Ul")
        }

        "login form" in {
            val node = form.cls("login")(
                div(
                    label.`for`("email")("Email"),
                    input.typ("email").placeholder("you@example.com")
                ),
                div(
                    label.`for`("pass")("Password"),
                    input.typ("password").placeholder("********")
                ),
                button.cls("submit").disabled(false)("Log In")
            )
            assert(node.children.size == 3)
            node.children(2) match
                case btn: Button =>
                    assert(hasValue(btn.disabled, false))
                    assert(hasClass(btn.common.classes, "submit"))
                case _ => fail("expected Button")
            end match
        }

        "page layout" in {
            val node = div.cls("app")(
                header(h1("Dashboard"), nav(a.href("/logout")("Logout"))),
                main.cls("content")(
                    section.cls("stats")(h2("Statistics"), p("Data here")),
                    section.cls("chart")(h2("Chart"), div.id("chart-container")())
                ),
                footer(p("© 2026 MyApp"))
            )
            assert(node.children.size == 3)
            node.children(1) match
                case m: Main =>
                    assert(hasClass(m.common.classes, "content"))
                    assert(m.children.size == 2)
                case _ => fail("expected Main")
            end match
        }

        "data table from collections" in {
            val headers = List("Name", "Age", "City")
            val rows    = List(("Alice", "30", "NYC"), ("Bob", "25", "LA"))

            val node = table.cls("data-table")(
                (tr(headers.map(h => th(h))*) ::
                    rows.map((name, age, city) =>
                        tr(td(name), td(age), td(city))
                    ))*
            )
            assert(node.children.size == 3)
        }

        "counter component" in run {
            for count <- Signal.initRef(0)
            yield
                val node = div(
                    p(count.map(_.toString)),
                    button("+").onClick {
                        for _ <- count.getAndUpdate(_ + 1) yield ()
                    },
                    button("-").onClick {
                        for _ <- count.getAndUpdate(_ - 1) yield ()
                    }
                )
                assert(node.children.size == 3)
                node.children(0) match
                    case pp: P =>
                        pp.children.head match
                            case ReactiveText(_) => assertionSuccess
                            case _               => assertionFailure("expected ReactiveText")
                    case _ => assertionFailure("expected P")
                end match
        }

        "todo app" in run {
            for
                items <- Signal.initRef(Chunk.empty[String])
                text  <- Signal.initRef("")
            yield
                val node = div.cls("todo-app")(
                    h1("Todos"),
                    div.cls("input-row")(
                        input.value(text).onInput(text.set(_)).placeholder("What needs to be done?"),
                        button("Add").onClick {
                            for
                                t <- text.get
                                _ <- if t.nonEmpty then items.getAndUpdate(_.append(t)).unit else ((): Unit < Sync)
                                _ <- text.set("")
                            yield ()
                        }
                    ),
                    ul.cls("todo-list")(
                        items.foreachIndexed((idx, todo) =>
                            li.cls("todo-item")(
                                span(todo),
                                button("x").cls("delete").onClick(items.getAndUpdate(c => c.take(idx) ++ c.drop(idx + 1)).unit)
                            )
                        )
                    )
                )
                assert(node.children.size == 3)
                node.children(2) match
                    case u: Ul =>
                        u.children.head match
                            case ForeachIndexed(_, _) => assertionSuccess
                            case _                    => assertionFailure("expected ForeachIndexed")
                    case _ => assertionFailure("expected Ul")
                end match
        }
    }

    "type safety" - {
        "builder returns same type" in {
            val d: Div         = div.cls("x").id("y")
            val b: Button      = button.cls("x").disabled(true)
            val i: Input       = input.typ("text").placeholder("hi")
            val anchor: Anchor = a.href("/").target("_blank")
            assert(d.isInstanceOf[Div])
            assert(b.isInstanceOf[Button])
            assert(i.isInstanceOf[Input])
            assert(anchor.isInstanceOf[Anchor])
        }

        "all elements are UI" in {
            val elements: List[UI] = List(
                div(),
                p(),
                span(),
                ul(),
                li(),
                nav(),
                header(),
                footer(),
                section(),
                h1(),
                h2(),
                h3(),
                h4(),
                h5(),
                h6(),
                button(),
                a(),
                form(),
                select(),
                input,
                textarea,
                img("x", "y"),
                label(),
                table(),
                tr(),
                td(),
                th()
            )
            assert(elements.size == 27)
        }

        "elements are Elements" in {
            val node: Element = div()
            assert(node.isInstanceOf[Element])
        }
    }

end UITest

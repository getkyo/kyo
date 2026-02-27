package kyo

import kyo.UI.*
import kyo.UI.AST.*
import org.scalajs.dom
import org.scalajs.dom.document

class DomBackendTest extends Test:

    private val backend = new DomBackend

    private def clearBody(): Unit =
        document.body.innerHTML = ""

    end clearBody

    private def rendered(ui: UI)(using Frame): UISession < (Async & Scope) =
        clearBody()
        backend.render(ui)

    "static rendering" - {

        "text node" in run {
            Scope.run {
                for r <- rendered(div("hello"))
                yield assert(document.body.innerHTML == "<div>hello</div>")
            }
        }

        "nested elements" in run {
            Scope.run {
                for r <- rendered(
                        div(
                            h1("Title"),
                            p("Body")
                        )
                    )
                yield assert(document.body.innerHTML == "<div><h1>Title</h1><p>Body</p></div>")
            }
        }

        "element with class and id" in run {
            Scope.run {
                for r <- rendered(div.cls("container").id("main")("content"))
                yield
                    val el = document.getElementById("main")
                    assert(el != null)
                    assert(el.getAttribute("class") == "container")
                    assert(el.textContent == "content")
            }
        }

        "input with attributes" in run {
            Scope.run {
                for r <- rendered(input.typ("email").placeholder("Enter email"))
                yield
                    val el = document.body.querySelector("input")
                    assert(el != null)
                    assert(el.getAttribute("type") == "email")
                    assert(el.getAttribute("placeholder") == "Enter email")
            }
        }

        "img with src and alt" in run {
            Scope.run {
                for r <- rendered(img("photo.jpg", "A photo").cls("thumb"))
                yield
                    val el = document.body.querySelector("img")
                    assert(el != null)
                    assert(el.getAttribute("src") == "photo.jpg")
                    assert(el.getAttribute("alt") == "A photo")
                    assert(el.getAttribute("class") == "thumb")
            }
        }

        "anchor with href and target" in run {
            Scope.run {
                for r <- rendered(a.href("https://example.com").target("_blank")("Click"))
                yield
                    val el = document.body.querySelector("a")
                    assert(el != null)
                    assert(el.getAttribute("href") == "https://example.com")
                    assert(el.getAttribute("target") == "_blank")
                    assert(el.textContent == "Click")
            }
        }

        "button disabled" in run {
            Scope.run {
                for r <- rendered(button("Go").disabled(true))
                yield
                    val el = document.body.querySelector("button")
                    assert(el != null)
                    assert(el.asInstanceOf[dom.html.Button].disabled)
            }
        }

        "table with colspan" in run {
            Scope.run {
                for r <- rendered(
                        table(
                            tr(th("Header").colspan(2)),
                            tr(td("A"), td("B"))
                        )
                    )
                yield
                    val el = document.body.querySelector("th")
                    assert(el != null)
                    assert(el.getAttribute("colspan") == "2")
            }
        }

        "label with for" in run {
            Scope.run {
                for r <- rendered(label.`for`("email")("Email"))
                yield
                    val el = document.body.querySelector("label")
                    assert(el != null)
                    assert(el.getAttribute("for") == "email")
            }
        }

        "hidden element" in run {
            Scope.run {
                for r <- rendered(p("secret").hidden(true))
                yield
                    val el = document.body.querySelector("p")
                    assert(el != null)
                    assert(el.asInstanceOf[scalajs.js.Dynamic].hidden.asInstanceOf[Boolean])
            }
        }

        "style attribute" in run {
            Scope.run {
                for r <- rendered(div.style("color: red")("styled"))
                yield
                    val el = document.body.querySelector("div")
                    assert(el != null)
                    assert(el.getAttribute("style") == "color: red")
            }
        }

        "generic attr" in run {
            Scope.run {
                for r <- rendered(div.attr("data-id", "123").attr("aria-label", "main")())
                yield
                    val el = document.body.querySelector("div")
                    assert(el != null)
                    assert(el.getAttribute("data-id") == "123")
                    assert(el.getAttribute("aria-label") == "main")
            }
        }

        "fragment renders children" in run {
            Scope.run {
                for r <- rendered(Fragment(Chunk(span("a"), span("b"))))
                yield
                    val spans = document.body.querySelectorAll("span")
                    assert(spans.length == 2)
                    assert(spans(0).textContent == "a")
                    assert(spans(1).textContent == "b")
            }
        }

        "select with options" in run {
            Scope.run {
                for r <- rendered(
                        select(
                            option.value("a")("Option A"),
                            option.value("b")("Option B")
                        )
                    )
                yield
                    val opts = document.body.querySelectorAll("option")
                    assert(opts.length == 2)
                    assert(opts(0).getAttribute("value") == "a")
                    assert(opts(1).getAttribute("value") == "b")
            }
        }

        "form structure" in run {
            Scope.run {
                for r <- rendered(
                        form(
                            label("Name"),
                            input.typ("text"),
                            button("Submit")
                        )
                    )
                yield
                    assert(document.body.querySelector("form") != null)
                    assert(document.body.querySelector("label") != null)
                    assert(document.body.querySelector("input") != null)
                    assert(document.body.querySelector("button") != null)
            }
        }

        "all tag names render correctly" in run {
            Scope.run {
                for r <- rendered(
                        Fragment(Chunk(
                            div(),
                            p(),
                            span(),
                            ul(),
                            ol(),
                            li(),
                            nav(),
                            header(),
                            footer(),
                            section(),
                            main(),
                            pre(),
                            code(),
                            table(),
                            tr(),
                            td(),
                            th(),
                            h1(),
                            h2(),
                            h3(),
                            h4(),
                            h5(),
                            h6(),
                            hr,
                            br,
                            button(),
                            a(),
                            form(),
                            select(),
                            input,
                            textarea
                        ))
                    )
                yield
                    val tags = Seq(
                        "div",
                        "p",
                        "span",
                        "ul",
                        "ol",
                        "li",
                        "nav",
                        "header",
                        "footer",
                        "section",
                        "main",
                        "pre",
                        "code",
                        "table",
                        "tr",
                        "td",
                        "th",
                        "h1",
                        "h2",
                        "h3",
                        "h4",
                        "h5",
                        "h6",
                        "hr",
                        "br",
                        "button",
                        "a",
                        "form",
                        "select",
                        "input",
                        "textarea"
                    )
                    tags.foreach { tag =>
                        assert(document.body.querySelector(tag) != null, s"$tag not found")
                    }
                    assertionSuccess
            }
        }

        "page layout" in run {
            Scope.run {
                for r <- rendered(
                        div.cls("app")(
                            header(h1("Dashboard")),
                            main.cls("content")(
                                section(h2("Stats")),
                                section(h2("Chart"))
                            ),
                            footer(p("© 2026"))
                        )
                    )
                yield
                    assert(document.body.querySelector(".app") != null)
                    assert(document.body.querySelector("header h1").textContent == "Dashboard")
                    assert(document.body.querySelector(".content") != null)
                    assert(document.body.querySelectorAll("section").length == 2)
                    assert(document.body.querySelector("footer p").textContent == "© 2026")
            }
        }
    }

    "reactive rendering" - {
        "signal updates text" in run {
            clearBody()
            Scope.run {
                for
                    ref <- Signal.initRef("initial")
                    r   <- backend.render(div(ref.asInstanceOf[Signal[String]]))
                    _   <- Async.sleep(50.millis)
                    _ = assert(document.body.textContent.contains("initial"), s"expected 'initial' but got '${document.body.textContent}'")
                    _ <- ref.set("updated")
                    _ <- Async.sleep(50.millis)
                yield assert(document.body.textContent.contains("updated"), s"expected 'updated' but got '${document.body.textContent}'")
            }
        }

        "click handler updates signal" in run {
            clearBody()
            Scope.run {
                for
                    count <- Signal.initRef(0)
                    r <- backend.render(
                        div(
                            span.cls("value")(count.map(_.toString)),
                            button.cls("inc")("inc").onClick(count.getAndUpdate(_ + 1).unit)
                        )
                    )
                    _ <- Async.sleep(50.millis)
                    _ = assert(document.body.querySelector(".value").textContent == "0")
                    _ = document.body.querySelector(".inc").asInstanceOf[dom.html.Button].click()
                    _ <- Async.sleep(50.millis)
                yield assert(
                    document.body.querySelector(".value").textContent == "1",
                    s"expected '1' but got '${document.body.querySelector(".value").textContent}'"
                )
            }
        }
        "when does not rebuild DOM when condition source changes but condition stays true" in run {
            clearBody()
            Scope.run {
                for
                    items <- Signal.initRef(Chunk(1, 2, 3))
                    doneCount = items.map(_.count(_ > 1))
                    r <- backend.render(
                        div(
                            when(doneCount.map(_ > 0))(
                                button.cls("clear")("Clear").onClick(items.getAndUpdate(_.filter(_ <= 1)).unit)
                            )
                        )
                    )
                    _ <- Async.sleep(50.millis)
                    btn1 = document.body.querySelector(".clear")
                    _    = assert(btn1 != null, "button should exist initially")

                    // Change items but keep doneCount > 0 (still 2 items > 1)
                    _ <- items.set(Chunk(1, 2, 3, 4))
                    _ <- Async.sleep(50.millis)
                    btn2 = document.body.querySelector(".clear")
                    _    = assert(btn2 != null, "button should still exist")
                yield
                    // The DOM node should be the SAME reference — not rebuilt
                    assert(
                        btn1 eq btn2,
                        "when() should not rebuild DOM when the rendered UI is structurally unchanged"
                    )
            }
        }
    }

end DomBackendTest

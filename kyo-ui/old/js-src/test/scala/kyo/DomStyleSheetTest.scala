package kyo

import kyo.internal.DomStyleSheet
import org.scalajs.dom
import org.scalajs.dom.document
import scala.language.implicitConversions

class DomStyleSheetTest extends Test:

    private def createElement(): dom.Element =
        document.createElement("div")

    "empty style" - {
        "no inline style when absent" in {
            val el     = createElement()
            val result = DomStyleSheet(el, Style.empty, Absent)
            assert(result == Absent)
            assert(!el.hasAttribute("style"))
        }

        "preserves inline style string" in {
            val el     = createElement()
            val result = DomStyleSheet(el, Style.empty, Present("color: red"))
            assert(result == Absent)
            assert(el.getAttribute("style") == "color: red")
        }
    }

    "base styles without pseudo-states" - {
        "applies as inline style" in {
            val el     = createElement()
            val s      = Style.bg("#fff").bold
            val result = DomStyleSheet(el, s, Absent)
            assert(result == Absent)
            val style = el.getAttribute("style")
            assert(style.contains("background-color: #fff;"))
            assert(style.contains("font-weight: bold;"))
        }

        "merges with inline style string" in {
            val el     = createElement()
            val s      = Style.bg("#fff")
            val result = DomStyleSheet(el, s, Present("display: flex;"))
            assert(result == Absent)
            val style = el.getAttribute("style")
            assert(style.contains("display: flex;"))
            assert(style.contains("background-color: #fff;"))
        }
    }

    "pseudo-states" - {
        "hover injects CSS rule and adds class" in {
            val el     = createElement()
            val s      = Style.bg("#fff").hover(Style.bg("#eee"))
            val result = DomStyleSheet(el, s, Absent)
            result match
                case Present(cls) => assert(el.classList.contains(cls))
                case _            => fail("expected class name")
        }

        "focus injects CSS rule" in {
            val el     = createElement()
            val s      = Style.bg("#fff").focus(Style.border(2, "#00f"))
            val result = DomStyleSheet(el, s, Absent)
            assert(result.nonEmpty)
        }

        "active injects CSS rule" in {
            val el     = createElement()
            val s      = Style.bg("#fff").active(Style.bg("#ccc"))
            val result = DomStyleSheet(el, s, Absent)
            assert(result.nonEmpty)
        }

        "preserves inline style string with pseudo-states" in {
            val el = createElement()
            val s  = Style.hover(Style.bg("#eee"))
            val _  = DomStyleSheet(el, s, Present("display: flex;"))
            assert(el.getAttribute("style") == "display: flex;")
        }

        "generates unique class names" in {
            val el1 = createElement()
            val el2 = createElement()
            val s   = Style.hover(Style.bg("#eee"))
            val r1  = DomStyleSheet(el1, s, Absent)
            val r2  = DomStyleSheet(el2, s, Absent)
            (r1, r2) match
                case (Present(c1), Present(c2)) => assert(c1 != c2)
                case _                          => fail("expected class names")
        }
    }

    "DomBackend integration" - {
        "style(Style) renders inline style on element" in run {
            Scope.run {
                val backend = new DomBackend
                document.body.innerHTML = ""
                for session <- backend.render(
                        UI.div.style(Style.bg("#ff0000").padding(10))("hello")
                    )
                yield
                    val el    = document.body.querySelector("div")
                    val style = el.getAttribute("style")
                    assert(style.contains("background-color: #ff0000;"))
                    assert(style.contains("padding:"))
                end for
            }
        }

        "style(Style) with pseudo-states adds class" in run {
            Scope.run {
                val backend = new DomBackend
                document.body.innerHTML = ""
                for session <- backend.render(
                        UI.div.style(Style.bg("#fff").hover(Style.bg("#eee")))("hello")
                    )
                yield
                    val el = document.body.querySelector("div")
                    assert(el.classList.length > 0)
                end for
            }
        }

        "style(String) and style(Style) coexist" in run {
            Scope.run {
                val backend = new DomBackend
                document.body.innerHTML = ""
                for session <- backend.render(
                        UI.div.style("display: flex").style(Style.bg("#fff"))("hello")
                    )
                yield
                    val el    = document.body.querySelector("div")
                    val style = el.getAttribute("style")
                    assert(style.contains("display: flex"))
                    assert(style.contains("background-color: #fff;"))
                end for
            }
        }
    }

end DomStyleSheetTest

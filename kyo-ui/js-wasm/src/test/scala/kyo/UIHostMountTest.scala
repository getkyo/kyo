package kyo

import kyo.internal.HtmlRenderer
import org.scalajs.dom

class UIHostMountTest extends kyo.test.Test[Any]:

    "the arity-2 UI.host(tag)(mount) factory yields a Host carrying a Present DomHostMount" in {
        val mounted = UI.host("div") { (_: dom.Element) => Kyo.unit }
        assert(mounted.mount.isDefined)
        mounted.mount match
            case Present(_: DomHostMount) => succeed
            case _                        => assert(false, "expected Present(DomHostMount)")
    }

    "the arity-2 factory carries the given hostTag through to the host" in {
        val a = UI.host("section") { (_: dom.Element) => Kyo.unit }
        val b = UI.host("canvas") { (_: dom.Element) => Kyo.unit }
        assert(a.hostTag == "section")
        assert(b.hostTag == "canvas")
    }

    "an arity-2 host renders the same <canvas data-kyo-path> string as a bare host" in {
        val tree = UI.div(UI.host("canvas") { (_: dom.Element) => Kyo.unit })
        for html <- HtmlRenderer.render(tree, Seq.empty)
        yield assert(html.contains("<canvas data-kyo-path=\"0\"></canvas>"))
        end for
    }

end UIHostMountTest

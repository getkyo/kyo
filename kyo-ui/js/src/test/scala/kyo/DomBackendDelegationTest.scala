package kyo

import kyo.internal.DomBackend
import org.scalajs.dom

/** Isolated tests for [[DomBackend.declaredInChain]], the SPA event-delegation forwarding gate.
  *
  * These run against a real jsdom document (see [[DomTestEnv]]), so the traversal is exercised through the actual DOM
  * API: `createElement`, attribute reads, `parentNode` links, and the `instanceof Element` check in the walk.
  */
class DomBackendDelegationTest extends kyo.test.Test[Any]:

    DomTestEnv.install

    private def el(parent: dom.Node, ev: String = null): dom.Element =
        val e = dom.document.createElement("div")
        if ev != null then e.setAttribute("data-kyo-ev", ev)
        if parent != null then discard(parent.appendChild(e))
        e
    end el

    "forwards when the target itself declares the type" in {
        val target = el(dom.document.body, ev = "keydown")
        assert(DomBackend.declaredInChain(target, "keydown"))
    }

    "forwards when only an ancestor declares the type" in {
        // The regression this PR fixes: a keydown declared on an ancestor panel must be forwarded even
        // though the event target carries no data-kyo-ev of its own.
        val ancestor = el(dom.document.body, ev = "keydown")
        val mid      = el(ancestor)
        val target   = el(mid)
        assert(DomBackend.declaredInChain(target, "keydown"))
    }

    "does not forward when no element in the chain declares the type" in {
        val ancestor = el(dom.document.body, ev = "click")
        val target   = el(ancestor)
        assert(!DomBackend.declaredInChain(target, "keydown"))
    }

    "matches individual entries of a comma-separated declaration" in {
        val target = el(dom.document.body, ev = "click,keydown")
        assert(DomBackend.declaredInChain(target, "keydown"))
        assert(DomBackend.declaredInChain(target, "click"))
        assert(!DomBackend.declaredInChain(target, "key"))
    }

    "does not consult a declaration on document.body itself" in {
        dom.document.body.setAttribute("data-kyo-ev", "click")
        val target = el(dom.document.body)
        val result = DomBackend.declaredInChain(target, "click")
        dom.document.body.removeAttribute("data-kyo-ev")
        assert(!result)
    }

    "stops at a non-element parent without crashing" in {
        // documentElement's parent is the document node, which is not an Element, so the walk must end there.
        assert(!DomBackend.declaredInChain(dom.document.documentElement, "click"))
    }

    "returns false for a detached element" in {
        val target = el(null, ev = null)
        assert(!DomBackend.declaredInChain(target, "click"))
    }

end DomBackendDelegationTest

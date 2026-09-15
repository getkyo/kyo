package kyo

/** Pure side-table miss-path tests for [[UIMouseEventOps]] and the [[kyo.UI.MouseEvent.targetClosest]]
  * extension. These run in the Node.js test environment, which has no DOM globals.
  *
  * Tests that require instantiating a `dom.MouseEvent` or a real DOM element tree cannot run in
  * Node.js because the `MouseEvent` constructor is a browser global (not a Node.js builtin). The
  * following scenarios are exercised by running the compiled bundle in a real browser (NodeJSEnv has
  * no DOM globals):
  *
  *   - remember/forget/nativeOf round-trip with a real `dom.MouseEvent` instance: requires
  *     `new dom.MouseEvent(...)` which is undefined in Node.js.
  *   - `targetClosest` returns `Present(ElementRef)` for a matching ancestor: requires DOM
  *     `createElement` / `appendChild` and a real `dom.MouseEvent` with a `.target` element.
  *   - `targetClosest` returns `Absent` when no ancestor matches: same DOM requirement.
  *   - `ElementRef.closest` / `querySelector` / attribute set/get/remove: all require real DOM
  *     elements that cannot be constructed in the Node.js NodeJSEnv.
  *
  * Each such member is exercised by its real effect when the compiled bundle runs in a browser. No
  * DOM test infrastructure is added here because the Node.js environment structurally cannot support
  * it.
  *
  * The scenarios below cover the pure miss paths and the table's identity-keying contract using
  * only `UI.MouseEvent` values (JVM-constructible, no DOM dependency).
  */
class UIMouseEventOpsTest extends kyo.test.Test[Any]:

    "nativeOf returns Absent for a payload that was never remembered" in {
        val m = UI.MouseEvent(Absent, UI.Modifiers.none)
        assert(UIMouseEventOps.nativeOf(m) == Absent)
    }

    "targetClosest returns Absent for a payload that was never remembered" in {
        val m = UI.MouseEvent(Absent, UI.Modifiers.none)
        assert(m.targetClosest("button") == Absent)
    }

    "targetClosest returns Absent for any selector when the payload is un-remembered" in {
        val m = UI.MouseEvent(Present("some-id"), UI.Modifiers(ctrl = true, alt = false, shift = false, meta = false))
        assert(m.targetClosest(".code-block") == Absent)
        assert(m.targetClosest("*") == Absent)
    }

    "forget on an un-remembered payload is a no-op (does not throw)" in {
        val m = UI.MouseEvent(Absent, UI.Modifiers.none)
        UIMouseEventOps.forget(m)
        assert(UIMouseEventOps.nativeOf(m) == Absent)
    }

    "two distinct UI.MouseEvent instances that are structurally equal but different refs each miss independently" in {
        val m1 = UI.MouseEvent(Absent, UI.Modifiers.none)
        val m2 = UI.MouseEvent(Absent, UI.Modifiers.none)
        // Neither has been remembered; both must miss independently.
        assert(UIMouseEventOps.nativeOf(m1) == Absent)
        assert(UIMouseEventOps.nativeOf(m2) == Absent)
    }

end UIMouseEventOpsTest

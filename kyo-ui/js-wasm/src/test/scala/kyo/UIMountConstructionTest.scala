package kyo

/** Construction-purity guards for the JS+Wasm `UI.runMount` entries in `UIMount.scala`.
  *
  * Building a mount effect must be pure: it reads the DOM only when the effect runs, never at the
  * call site. These run under `NodeJSEnv`, where there is no `document`, so a mount entry that read
  * the DOM eagerly would throw a `ReferenceError` while merely constructing the value.
  */
class UIMountConstructionTest extends kyo.test.Test[Any]:

    "UI.runMount(tree) builds as a pure value without reading the DOM" in {
        val tree = UI.div(UI.span("hi"))
        // The no-selector mount must read `document.body` only inside the effect, not as a
        // by-value argument, so constructing it under Node (no `document`) does not throw.
        val built = scala.util.Try(UI.runMount(tree))
        assert(built.isSuccess, s"runMount(tree) construction must not touch the DOM, got $built")
    }

    "UI.runMount(tree, selector) builds as a pure value without reading the DOM" in {
        val tree  = UI.div(UI.span("hi"))
        val built = scala.util.Try(UI.runMount(tree, "#app"))
        assert(built.isSuccess, s"runMount(tree, selector) construction must not touch the DOM, got $built")
    }

end UIMountConstructionTest

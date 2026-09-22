package kyo.ffi.internal

import kyo.*
import kyo.ffi.Test
import scala.scalajs.js as sjs

/** The constructed-require branch is asserted directly rather than through [[NodeRequire.find]].
  *
  * `find` prefers a global `require` and this runner exposes one, so going through it would exercise the global
  * branch and report green while the branch an ESModule bundle depends on is broken.
  */
class NodeRequireTest extends Test:

    "builds a require from node:module" in {
        assert(NodeRequire.fromNodeModule().isDefined)
    }

    "the constructed require loads a builtin" in {
        val loaded = NodeRequire.fromNodeModule().map { req =>
            val path = req.asInstanceOf[sjs.Function1[String, sjs.Dynamic]]("node:path")
            !sjs.isUndefined(path) && path != null
        }
        assert(loaded == Some(true))
    }

    "the constructed require exposes resolve" in {
        val resolved = NodeRequire.fromNodeModule().map { req =>
            val r = req.applyDynamic("resolve")("node:path")
            !sjs.isUndefined(r) && r != null
        }
        assert(resolved == Some(true))
    }

    "find answers on this runtime" in {
        assert(NodeRequire.find().isDefined)
    }

    /** The shape of an ESModule application, which has no `require` global. This runner puts one on `globalThis`
      * for an ESModule bundle, so the leaf hides it; a CommonJS bundle's `require` is module-scoped and cannot be
      * hidden, so there the leaf cancels rather than pass through the global.
      */
    "find answers with no require global" in {
        val global = sjs.Dynamic.global.globalThis
        val had    = global.hasOwnProperty("require").asInstanceOf[Boolean]
        val saved  = global.selectDynamic("require")
        global.updateDynamic("require")(sjs.undefined)
        try
            if sjs.typeOf(sjs.Dynamic.global.selectDynamic("require")) != "undefined" then
                cancel("require is module-scoped on this module kind and cannot be hidden")
            else assert(NodeRequire.find().isDefined)
        finally
            if had then global.updateDynamic("require")(saved)
            else sjs.special.delete(global, "require")
        end try
    }

    /** Resolution walks up from the anchor, so anchoring at the working directory makes a package next to the
      * bundle reachable or not depending on where the process was started. Asserted on the anchor rather than on a
      * load, because a load succeeds under either anchor whenever both trees happen to reach the package.
      *
      * Driven by a stub rather than by this process, whose argv carries no entry script: an assertion against
      * whatever the runner happens to expose pins nothing, and reads as green on a runtime that cannot show the
      * difference.
      */
    private def stubProc(argv: sjs.Array[String], cwd: String): sjs.Dynamic =
        sjs.Dynamic.literal(argv = argv, cwd = (() => cwd): sjs.Function0[String])

    "anchors at the entry script when there is one" in {
        assert(NodeRequire.anchor(stubProc(sjs.Array("node", "/app/main.mjs"), "/elsewhere")) == "/app/main.mjs")
    }

    "anchors at the working directory when there is no entry script" in {
        assert(NodeRequire.anchor(stubProc(sjs.Array("node"), "/elsewhere")) == "/elsewhere/")
    }

end NodeRequireTest

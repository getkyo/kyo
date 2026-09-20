package kyo.ffi.internal

import kyo.*
import kyo.ffi.Test
import scala.scalajs.js as sjs

/** The constructed-require branch is asserted directly rather than through [[NodeRequire.find]].
  *
  * `find` prefers a global `require` and this runner exposes one, so going through it would exercise the global
  * branch and report green while the branch an ESModule bundle actually depends on stayed broken. That is the
  * shape of the failure this suite exists for: the koffi gate reached only for the global, answered `false` on
  * every ESModule bundle, and the runtime reported a missing native instead of an unreachable require.
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

    /** Resolution walks up from the anchor, so anchoring at the working directory makes a package next to the
      * bundle reachable or not depending on where the process was started. Asserted on the anchor rather than on a
      * load, because a load succeeds under either one whenever both trees happen to reach the package.
      */
    "anchors at the entry script rather than the working directory" in {
        val proc  = sjs.Dynamic.global.selectDynamic("process")
        val entry = proc.selectDynamic("argv").asInstanceOf[sjs.Array[String]](1)
        assert(entry.nonEmpty)
        assert(NodeRequire.anchor(proc) == entry)
    }

end NodeRequireTest

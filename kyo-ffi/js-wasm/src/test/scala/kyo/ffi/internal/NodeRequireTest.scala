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

end NodeRequireTest

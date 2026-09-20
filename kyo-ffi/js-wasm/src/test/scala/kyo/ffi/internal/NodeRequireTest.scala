package kyo.ffi.internal

import kyo.*
import kyo.ffi.Test
import scala.scalajs.js as sjs

/** kyo-ffi's js axis links as an ESModule, so this suite runs in the module kind that has no global `require`.
  * That is the whole point of it: a reach for the global alone answers "no require here", every presence gate
  * built on it answers "absent", and a consumer degrades to a slower transport with a message naming the OS
  * rather than the loader. Asserting a require is reachable on Node regardless of module kind is what keeps that
  * from returning silently.
  */
class NodeRequireTest extends Test:

    "finds a require on Node whatever the module kind" in {
        assert(NodeRequire.find().isDefined)
    }

    "the require it finds can load a builtin" in {
        val loaded = NodeRequire.find().map { req =>
            val path = req.asInstanceOf[sjs.Function1[String, sjs.Dynamic]]("node:path")
            !sjs.isUndefined(path) && path != null
        }
        assert(loaded == Some(true))
    }

    "the require it finds exposes resolve" in {
        val resolved = NodeRequire.find().map { req =>
            val r = req.applyDynamic("resolve")("node:path")
            !sjs.isUndefined(r) && r != null
        }
        assert(resolved == Some(true))
    }

end NodeRequireTest

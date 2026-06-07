package kyo.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Facades for the Node.js built-in modules kyo-http uses.
  *
  * `@JSImport` compiles to `require(...)` under CommonJS and to `import` under ESModule, so the
  * same source works on both the JS and the WebAssembly backends. A `js.Dynamic.global.require`
  * call would not: `require` is not a global in a Node ES module, which is the module kind the
  * experimental WASM backend mandates. The members are typed as `js.Object` and accessed via
  * `asInstanceOf[js.Dynamic]` at the call sites, matching the existing dynamic usage.
  *
  * Names are Http-prefixed to avoid clashing with the similar facades in kyo-core's `kyo.internal`.
  * The module ids use the `node:` scheme: under ESModule a namespace import of a bare builtin
  * ("path") does not reliably expose its named members (e.g. path.join), while "node:path" does.
  */
@js.native
@JSImport("node:net", JSImport.Namespace)
private[kyo] object HttpNet extends js.Object

@js.native
@JSImport("node:tls", JSImport.Namespace)
private[kyo] object HttpTls extends js.Object

@js.native
@JSImport("node:fs", JSImport.Namespace)
private[kyo] object HttpFs extends js.Object

@js.native
@JSImport("node:os", JSImport.Namespace)
private[kyo] object HttpOs extends js.Object:
    def tmpdir(): String  = js.native
    def homedir(): String = js.native
end HttpOs

@js.native
@JSImport("node:path", JSImport.Namespace)
private[kyo] object HttpNodePath extends js.Object:
    def join(parts: String*): String  = js.native
    def dirname(path: String): String = js.native
end HttpNodePath

@js.native
@JSImport("node:child_process", JSImport.Namespace)
private[kyo] object HttpChildProcess extends js.Object

package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Node built-in facades for kyo-pod's container-runtime test helper.
  *
  * Declared (typed) members rather than `js.Dynamic.global.require`: `@JSImport` compiles to
  * require under CommonJS and to import under ESModule (the kind the WASM backend mandates),
  * and a declared member access is what makes the Scala.js linker emit the import. Names are
  * Pod-prefixed so they do not clash with the `kyo.*` namespace.
  */
@js.native
@JSImport("node:child_process", JSImport.Namespace)
private[kyo] object PodNodeChildProcess extends js.Object:
    def execSync(command: String): js.Dynamic                  = js.native
    def execSync(command: String, options: js.Any): js.Dynamic = js.native
end PodNodeChildProcess

@js.native
@JSImport("node:os", JSImport.Namespace)
private[kyo] object PodNodeOs extends js.Object:
    def homedir(): String = js.native
end PodNodeOs

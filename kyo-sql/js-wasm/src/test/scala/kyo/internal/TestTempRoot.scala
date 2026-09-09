package kyo.internal

import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*

// Node's os module, reached through a namespace import for the reason TestProcessId records for
// node:process: @JSImport compiles to require() under CommonJS and to import under ESModule, so one
// facade serves both the JS and the WebAssembly backend.
@js.native
@JSImport("node:os", JSImport.Namespace)
private object TestNodeOs extends js.Object:
    def tmpdir(): String = js.native
end TestNodeOs

/** The system temporary-directory root, for [[SqlTestContainers]]'s co-owner registry.
  *
  * The registry is a cross-process rendezvous: every test process on the machine must resolve the SAME directory, so this is the platform's
  * temp root itself rather than `Path.tempDir`, which mints a fresh private directory per call. Scala.js does not surface `java.io.tmpdir`
  * as a system property, so the root comes from Node's `os.tmpdir()` instead. `Absent` means the platform has no temp root, which disables
  * the registry.
  */
private[kyo] object TestTempRoot:

    def get(using Frame): Maybe[String] < Sync =
        Sync.defer(Maybe(TestNodeOs.tmpdir()).filter(_.nonEmpty))

end TestTempRoot

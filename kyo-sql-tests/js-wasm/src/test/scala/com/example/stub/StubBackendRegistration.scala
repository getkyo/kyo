package com.example.stub

import kyo.db.Backend
import scala.scalajs.js.annotation.JSExportTopLevel

/** JS and Wasm registration for the stub backend, the run-time counterpart of this module's `META-INF/services/kyo.db.Backend` entry.
  *
  * Neither Scala.js nor the Wasm backend reads a services file at run time, so the entry alone reaches only the JVM and Native. This object
  * supplies the other half by calling `kyo.db.Backend.register` at module load. `@JSExportTopLevel` keeps the initializer alive against
  * linker dead-code elimination, exactly as the shipping backends' registration objects do.
  */
object StubBackendRegistration:

    @JSExportTopLevel("__kyo_sql_stub_init")
    val init: Boolean =
        Backend.register(new StubBackend())
        true
    end init

end StubBackendRegistration

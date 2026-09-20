package kyo.internal.dolt

import kyo.DoltServer
import scala.scalajs.js.annotation.JSExportTopLevel

/** JS and Wasm registration for the Dolt backend factory, the run-time counterpart of this module's
  * `META-INF/services/kyo.db.Backend` entry, which neither platform reads at run time.
  *
  * The `@JSExportTopLevel` annotation is load-bearing: nothing in the program references this object, so linker dead-code elimination would
  * drop the initializer and discovery would quietly find no Dolt backend.
  */
object DoltBackendRegistration:

    @JSExportTopLevel("__kyo_sql_dolt_init")
    val init: Boolean =
        DoltServer.register()
        true
    end init

end DoltBackendRegistration

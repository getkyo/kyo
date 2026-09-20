package kyo.internal.doltlite

import kyo.DoltLite
import scala.scalajs.js.annotation.JSExportTopLevel

/** Registers the DoltLite backend factory at module load, since neither Scala.js nor the WebAssembly backend reads this
  * module's `META-INF/services/kyo.db.Backend` entry at run time.
  *
  * `@JSExportTopLevel` is load-bearing: nothing in the program references this object, so linker dead-code elimination
  * would drop the initializer and discovery would quietly find no DoltLite backend.
  */
object DoltLiteBackendRegistration:

    @JSExportTopLevel("__kyo_sql_doltlite_init")
    val init: Boolean =
        DoltLite.register()
        true
    end init

end DoltLiteBackendRegistration

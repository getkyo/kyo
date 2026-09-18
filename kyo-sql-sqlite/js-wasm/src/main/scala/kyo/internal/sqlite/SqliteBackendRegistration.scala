package kyo.internal.sqlite

import kyo.SqliteClient
import scala.scalajs.js.annotation.JSExportTopLevel

/** JS and Wasm registration for the SQLite backend factory, the run-time counterpart of this module's
  * `META-INF/services/kyo.db.Backend` entry, since neither backend reads a services file at run time. The `@JSExportTopLevel` annotation is
  * load-bearing: nothing in the program references this object, so linker dead-code elimination would drop the initializer and discovery
  * would quietly find no SQLite backend.
  */
object SqliteBackendRegistration:

    @JSExportTopLevel("__kyo_sql_sqlite_init")
    val init: Boolean =
        SqliteClient.register()
        true
    end init

end SqliteBackendRegistration

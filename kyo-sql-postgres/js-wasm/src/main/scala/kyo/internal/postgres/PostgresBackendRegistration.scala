package kyo.internal.postgres

import kyo.PostgresClient
import scala.scalajs.js.annotation.JSExportTopLevel

/** JS and Wasm registration for the Postgres backend factory, the run-time counterpart of this module's
  * `META-INF/services/kyo.db.Backend` entry.
  *
  * Neither Scala.js nor the WebAssembly backend reads a services file at run time, so the entry alone reaches only the compile-time
  * derivation. This object supplies the other half by calling `kyo.db.Backend.register` at module load, which keys the factory on
  * `classOf[Backend].getName`, the same expression `kyo.internal.SqlBackendDiscovery` reads it back under.
  *
  * The `@JSExportTopLevel` annotation is what makes it happen. Nothing in the program references this object, so linker dead-code
  * elimination would drop the initializer and discovery would quietly find no Postgres backend. Mirrors `kyo-stats-otlp`'s
  * `OTLPRegistration` and `kyo-stats-machine`'s `MachineRegistration`.
  */
object PostgresBackendRegistration:

    @JSExportTopLevel("__kyo_sql_postgres_init")
    val init: Boolean =
        PostgresClient.register()
        true
    end init

end PostgresBackendRegistration

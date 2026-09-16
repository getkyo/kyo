package kyo

import kyo.ffi.internal.WasmFacadeRegistry
import kyo.internal.sqlite.SqliteWasmFacade
import scala.scalajs.js

/** Backs this engine with the WebAssembly build of `@dolthub/doltlite-wasm`, which is how `doltlite://` URLs work in a
  * runtime that cannot load a native library.
  *
  * [[init]] MUST complete before any connection is opened. Instantiating a WebAssembly module is asynchronous, while a
  * generated FFI binding builds its dispatch table synchronously in a `val` the first time it is loaded, so the two are
  * ordered rather than reconciled.
  *
  * {{{
  * DoltLiteWasm.init.andThen {
  *     SqlClient.init("doltlite://app.db", SqlConfig(maxConnections = 1)).map { client =>
  *         DB.run(client)(DoltClient.use(_.query("SELECT dolt_version()")))
  *     }
  * }
  * }}}
  *
  * A database opened this way lives in MEMORY and is gone at reload. Persisting one means naming the `opfs` VFS through
  * [[SqliteVfs]], which carries two browser requirements: the page must be cross-origin isolated, and the VFS is
  * reachable only from a WORKER. A database on the main thread gets memory storage whatever VFS it names.
  * `kyo-sql-doltlite/scripts/browser-check.sh` runs the working shape end to end.
  */
object DoltLiteWasm:

    /** Must match the `library` name of the DoltLite FFI binding's `Ffi.Config`. */
    private val LibraryId = "kyo_doltlite"

    /** Registers an already-initialized Emscripten SQLite module as this engine's WebAssembly backing. `sqlite3` is what
      * the package's `initModule()` resolves to: the object carrying `wasm` and `capi`. Use this rather than [[init]]
      * under a bundler that rewrites imports, or when the application already holds an initialized module.
      */
    def register(sqlite3: js.Dynamic)(using Frame): Unit < Sync =
        Sync.defer(WasmFacadeRegistry.register(LibraryId, SqliteWasmFacade.provider(sqlite3)))

    /** Whether this engine already has a WebAssembly backing registered. */
    def isRegistered(using Frame): Boolean < Sync =
        Sync.defer(WasmFacadeRegistry.isRegistered(LibraryId))

    /** Imports and instantiates the published module, then registers it. Idempotent: a second call re-registers over the
      * first. The entry point differs between Node and a browser, picked here by whether a `process` global exists.
      */
    def init(using Frame): Unit < (Async & Abort[DoltLiteWasmUnavailableException]) =
        Async.fromFuture {
            import scala.concurrent.ExecutionContext.Implicits.global
            val entry =
                if hasProcess() then "@dolthub/doltlite-wasm/sqlite3-node.mjs"
                else "@dolthub/doltlite-wasm/sqlite3.mjs"
            importModule(entry).toFuture.flatMap { module =>
                val initModule = module.selectDynamic("default")
                initModule.asInstanceOf[js.Function0[js.Promise[js.Dynamic]]]().toFuture
            }
        }.map(sqlite3 => Sync.defer(WasmFacadeRegistry.register(LibraryId, SqliteWasmFacade.provider(sqlite3))))
            .handle(Abort.recover[Throwable](e => Abort.fail(DoltLiteWasmUnavailableException(e))))

    /** A dynamic `import()` reached through the global rather than through `js.dynamicImport`, which would tie the call
      * to the emitted module system: the driver is linked as CommonJS while the package ships ES modules, and only a
      * runtime `import()` bridges the two.
      */
    private def importModule(specifier: String): js.Promise[js.Dynamic] =
        js.Dynamic.global
            .applyDynamic("eval")("(s) => import(s)")
            .asInstanceOf[js.Function1[String, js.Promise[js.Dynamic]]](specifier)

    private def hasProcess(): Boolean =
        js.typeOf(js.Dynamic.global.selectDynamic("process")) != "undefined"

end DoltLiteWasm

/** The WebAssembly build of this engine could not be loaded. */
case class DoltLiteWasmUnavailableException(cause: Throwable)(using Frame)
    extends KyoException(
        "Could not load the DoltLite WebAssembly module. Install '@dolthub/doltlite-wasm', or hand an " +
            "already-initialized module to DoltLiteWasm.register.",
        cause
    )

package kyo

import kyo.internal.FileJournalCore
import kyo.internal.NodeSegmentStore
import scala.scalajs.js

// True when the runtime is Node.js: `process` is defined and carries a `versions.node`
// field that browsers lack. Named so FileJournalNodeRuntimeTest can drive both arms.
private[kyo] def isNodeRuntime: Boolean =
    js.typeOf(js.Dynamic.global.process) != "undefined" &&
        js.typeOf(js.Dynamic.global.process.versions) != "undefined" &&
        !js.isUndefined(js.Dynamic.global.process.versions.node)

/** Opens (or creates) a file-backed journal rooted at `dir`. Requires a Node.js runtime; on
  * a browser runtime (no `node:fs`) the call fails immediately with a typed
  * [[JournalStorageError]] rather than at first I/O (per design A.6). Available on JS and
  * Wasm when executed under NodeJSEnv or a WasmGC-on-Node host.
  *
  * @see
  *   [[FileJournal.Config]] for the durability and rotation knobs
  */
extension (backend: Journal.Backend.type)
    def file(dir: Path, config: FileJournal.Config = FileJournal.Config.default)(using
        Frame
    )
        : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        if !isNodeRuntime then
            Abort.fail(JournalStorageError(
                "FileJournal requires a Node.js runtime; browser persistence is not yet supported",
                Absent
            ))
        else
            FileJournalCore.open(dir, config, new NodeSegmentStore)
end extension

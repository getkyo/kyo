package kyo

import kyo.internal.ClaimSeam
import kyo.internal.FileJournalCore
import kyo.internal.FlushStrategy
import kyo.internal.GroupCommitCoordinator
import kyo.internal.NodeAsyncJournalStore
import kyo.internal.NodeSegmentStore
import kyo.internal.StoreSeam
import scala.scalajs.js

// True when the runtime is Node.js: `process` is defined and carries a `versions.node`
// field that browsers lack. Named so FileJournalNodeRuntimeTest can drive both arms.
private[kyo] def isNodeRuntime: Boolean =
    js.typeOf(js.Dynamic.global.process) != "undefined" &&
        js.typeOf(js.Dynamic.global.process.versions) != "undefined" &&
        !js.isUndefined(js.Dynamic.global.process.versions.node)

/** Opens (or creates) a file-backed journal rooted at `dir`. Requires a Node.js runtime; on
  * a browser runtime (no `node:fs`) the call fails immediately with a typed
  * [[JournalStorageError]] rather than at first I/O. Available on JS and
  * Wasm when executed under NodeJSEnv or a WasmGC-on-Node host.
  *
  * @see
  *   [[FileJournal.Config]] for the durability and rotation knobs
  */
extension (backend: Journal.Backend.type)
    def file(
        dir: Path,
        config: FileJournal.Config = FileJournal.Config.default,
        payloadCodec: EventPayloadCodec = EventPayloadCodec.bytes
    )(using
        Frame
    )
        : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        if !isNodeRuntime then
            Abort.fail(JournalStorageError(
                "FileJournal requires a Node.js runtime; no browser persistence backend exists",
                Absent
            ))
        else
            FileJournalCore.open(dir, config, StoreSeam.sync(new NodeSegmentStore), payloadCodec, ClaimSeam.sync, FlushStrategy.inline)

    /** Opens (or creates) an Async-flavored file-backed journal rooted at `dir`. Requires a
      * Node.js runtime; on a browser runtime the call fails immediately with a typed
      * [[JournalStorageError]] rather than at first I/O, matching [[file]]. Store operations run
      * entirely on `node:fs/promises`; concurrent appenders to the same stream coalesce their
      * durability flushes into one `fsync` per round.
      *
      * @see
      *   [[FileJournal.Config]] for the durability and rotation knobs
      */
    def fileAsync(
        dir: Path,
        config: FileJournal.Config = FileJournal.Config.default,
        payloadCodec: EventPayloadCodec = EventPayloadCodec.bytes
    )(using
        Frame
    )
        : Journal.Backend[Async] < (Sync & Scope & Abort[JournalStorageError]) =
        if !isNodeRuntime then
            Abort.fail(JournalStorageError(
                "FileJournal requires a Node.js runtime; no browser persistence backend exists",
                Absent
            ))
        else
            // Unsafe: bootstraps in-process claim permits and group-commit coordinator maps.
            Sync.Unsafe.defer {
                val coordinator = new GroupCommitCoordinator
                (ClaimSeam.async(), (fsync: FileJournal.Fsync) => FlushStrategy.groupCommit(fsync, coordinator))
            }.flatMap { (claim, flushFor) =>
                FileJournalCore.open(
                    dir,
                    config,
                    new NodeAsyncJournalStore,
                    payloadCodec,
                    claim,
                    flushFor
                )
            }
    end fileAsync
end extension

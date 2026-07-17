package kyo

import kyo.internal.NodeAsyncJournalStore
import kyo.internal.NodeSegmentStore
import kyo.internal.SegmentStore
import kyo.internal.StoreSeam
import scala.scalajs.js

// True when the runtime is Node.js: `process` is defined and carries a `versions.node`
// field that browsers lack. Named so FileJournalNodeRuntimeTest can drive both arms.
private[kyo] def isNodeRuntime: Boolean =
    js.typeOf(js.Dynamic.global.process) != "undefined" &&
        js.typeOf(js.Dynamic.global.process.versions) != "undefined" &&
        !js.isUndefined(js.Dynamic.global.process.versions.node)

private def requireNode[A, E](body: => A < (E & Abort[JournalStorageError]))(using Frame): A < (E & Abort[JournalStorageError]) =
    if !isNodeRuntime then
        Abort.fail(JournalStorageError(
            "FileJournal requires a Node.js runtime; no browser persistence backend exists",
            Absent
        ))
    else body

/** Wraps a [[StoreSeam]] so `open` and `acquireLock` fail immediately with a typed
  * [[JournalStorageError]] on a non-Node runtime instead of attempting real I/O. `syncDir` is not
  * separately guarded: it never runs before `open` or `acquireLock` has already succeeded on this
  * same seam (segment/directory creation only follows a successful root open), so the guard on
  * those two calls is sufficient to fail loud before any I/O is attempted.
  */
private def requireNodeSeam[S](inner: StoreSeam[S]): StoreSeam[S] = new StoreSeam[S]:
    override def readOnly: Boolean = inner.readOnly
    def open(path: Path)(using Frame): StoreSeam.Handle[S] < (S & Abort[JournalStorageError]) =
        requireNode(inner.open(path))
    def acquireLock(root: Path)(using Frame): SegmentStore.Lock < (Sync & Abort[JournalStorageError]) =
        requireNode(inner.acquireLock(root))
    def syncDir(dir: Path)(using Frame): Unit < S =
        inner.syncDir(dir)
end requireNodeSeam

/** The JS/Wasm platform synchronous [[StoreSeam]], resolved by `FileJournalCore.openSync` /
  * `FileJournalCore.openReader` (package `kyo.internal`) so the shared `Journal.Backend.file` /
  * `Journal.Reader.file` in `Journal.scala` compile identically on every platform while each
  * resolves its own concrete store. Requires a Node.js runtime; on a browser runtime (no
  * `node:fs`) every call fails immediately with a typed [[JournalStorageError]] rather than at
  * first I/O.
  */
private[kyo] def platformSyncStore(isReadOnly: Boolean = false): StoreSeam[Sync] =
    requireNodeSeam(StoreSeam.sync(new NodeSegmentStore, isReadOnly))

/** The JS/Wasm platform asynchronous [[StoreSeam]], resolved by `FileJournalCore.openAsync`.
  * Same Node.js requirement and browser-runtime failure behavior as [[platformSyncStore]].
  */
private[kyo] def platformAsyncStore(isReadOnly: Boolean = false): StoreSeam[Async] =
    requireNodeSeam(new NodeAsyncJournalStore(isReadOnly))

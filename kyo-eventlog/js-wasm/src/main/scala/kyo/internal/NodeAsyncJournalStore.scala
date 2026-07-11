package kyo.internal

import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

// node:fs/promises bindings for the segment handle's positional read/write path and the
// directory-durability sync. Modeled after NodeFsSync (NodeJournalStore.scala) but every
// operation returns a Promise, so the Async backend never blocks the event loop on a
// per-record I/O call.
@js.native
@JSImport("node:fs/promises", JSImport.Namespace)
private[kyo] object NodeFsPromises extends js.Object:
    def open(path: String, flags: String): js.Promise[NodeFileHandle] = js.native
end NodeFsPromises

@js.native
private[kyo] trait NodeFileHandle extends js.Object:
    def read(buffer: Uint8Array, offset: Int, length: Int, position: Double): js.Promise[NodeReadResult]   = js.native
    def write(buffer: Uint8Array, offset: Int, length: Int, position: Double): js.Promise[NodeWriteResult] = js.native
    def datasync(): js.Promise[Unit]                                                                       = js.native
    def sync(): js.Promise[Unit]                                                                           = js.native
    def truncate(len: Double): js.Promise[Unit]                                                            = js.native
    def stat(): js.Promise[NodeJournalStat]                                                                = js.native
    def close(): js.Promise[Unit]                                                                          = js.native
end NodeFileHandle

@js.native
private[kyo] trait NodeReadResult extends js.Object:
    def bytesRead: Int     = js.native
    def buffer: Uint8Array = js.native
end NodeReadResult

@js.native
private[kyo] trait NodeWriteResult extends js.Object:
    def bytesWritten: Int = js.native
end NodeWriteResult

/** [[StoreSeam]]`[Async]` for JS/Wasm on a Node.js runtime, backed by `node:fs/promises`: no
  * synchronous fs call sits on the per-record path. The cross-process `LOCK` file protocol is
  * the [[NodeFileLock]] O_EXCL primitive, acquired once at open time (not per record);
  * every other operation bridges a `node:fs/promises` `Promise` into `Async` via [[fromPromise]].
  */
final private[kyo] class NodeAsyncJournalStore extends StoreSeam[Async]:

    def open(path: Path)(using Frame): StoreSeam.Handle[Async] < (Async & Abort[JournalStorageError]) =
        Abort.catching[Exception](e => JournalStorageError(s"Cannot open segment '${path.unsafe.show}'", Present(e))) {
            val pathStr = path.unsafe.show
            // Unsafe: existence check to pick create-vs-reopen flags, deferred through Sync so it
            // widens into the Async row rather than requiring AllowUnsafe on this method.
            Sync.Unsafe.defer(path.unsafe.exists()).map { exists =>
                val flags = if exists then "r+" else "w+"
                fromPromise(NodeFsPromises.open(pathStr, flags)).map(fh => new NodeAsyncHandle(fh))
            }
        }

    def acquireLock(root: Path)(using Frame): SegmentStore.Lock < (Sync & Abort[JournalStorageError]) =
        Sync.Unsafe.defer(Abort.get(NodeFileLock.acquire(root)))

    // Best-effort: opens the directory fd and forces it so a newly created child (a stream
    // directory or a segment file) survives a crash. Mirrors NodeSegmentStore.syncDir's
    // tolerance for a runtime with no directory-fd concept (the open, or the sync, rejection is
    // silently swallowed); a directory that was actually opened is always closed, even when the
    // sync itself fails.
    def syncDir(dir: Path)(using Frame): Unit < Async =
        Abort.run(Abort.catching[Throwable] {
            fromPromise(NodeFsPromises.open(dir.unsafe.show, "r")).map { fh =>
                Abort.run(Abort.catching[Throwable](fromPromise(fh.sync()))).map { syncResult =>
                    fromPromise(fh.close()).map { _ =>
                        syncResult match
                            case Result.Success(_) => ()
                            case Result.Failure(e) => throw e
                            case Result.Panic(e)   => throw e
                    }
                }
            }
        }).map(_ => ())
    end syncDir

end NodeAsyncJournalStore

// Positioned I/O handle for one open segment file. Every operation is a `node:fs/promises`
// call bridged into `Async`; a rejected promise surfaces as a panic (see `fromPromise`),
// caught and converted to a typed `JournalStorageError` by `FileJournalCore.catchStorageError`
// at the call site, the same contract the Sync `NodeHandle` throws to.
final private[kyo] class NodeAsyncHandle(fh: NodeFileHandle) extends StoreSeam.Handle[Async]:

    def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < Async =
        val buf = new Uint8Array(len)
        fromPromise(fh.read(buf, 0, len, pos.toDouble)).map { result =>
            val n   = result.bytesRead
            val out = new Array[Byte](n)
            var i   = 0
            while i < n do
                out(i) = buf(i).toByte
                i += 1
            end while
            out
        }
    end readAt

    // Loops in case a single write call resolves with fewer bytes written than requested
    // (unreachable on regular files in practice, but required for correctness parity with the
    // jvm-native and Sync NodeHandle write loops).
    def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < Async =
        val uint8 = bytesToUint8Array(bytes)
        Loop(0) { off =>
            if off >= bytes.length then Loop.done(())
            else
                fromPromise(fh.write(uint8, off, bytes.length - off, (pos + off).toDouble)).map { result =>
                    Loop.continue(off + result.bytesWritten)
                }
        }
    end writeAt

    def sync()(using Frame): Unit < Async               = fromPromise(fh.datasync()).map(_ => ())
    def truncate(size: Long)(using Frame): Unit < Async = fromPromise(fh.truncate(size.toDouble)).map(_ => ())
    def size()(using Frame): Long < Async               = fromPromise(fh.stat()).map(s => s.size.toLong)
    def close()(using Frame): Unit < Async              = fromPromise(fh.close()).map(_ => ())

end NodeAsyncHandle

/** Bridges a `node:fs/promises` `Promise` into `Async`: registers a `.then` callback that
  * completes a [[Promise.Unsafe]], panicking the fiber on rejection. Mirrors kyo-core's
  * jvm-native `CompletionStage` bridge (`AsyncPlatformSpecific.fromCompletionStage`). Handle-
  * level I/O failures are not modeled as typed `Abort` here: the caller (`FileJournalCore`)
  * converts any thrown exception, including one surfaced through this panic path, into the
  * typed `JournalStorageError` at the call site, the same contract the Sync store's throwing
  * handle methods rely on.
  */
private[kyo] def fromPromise[A](p: js.Promise[A])(using Frame): A < Async =
    Sync.Unsafe.defer {
        val promise = Promise.Unsafe.init[A, Any]()
        discard(p.`then`[Unit](
            (value: A) => discard(promise.completeDiscard(Result.succeed(value))),
            (error: scala.Any) => discard(promise.completeDiscard(Result.panic(toThrowable(error))))
        ))
        promise.safe.get
    }

private[kyo] def toThrowable(error: scala.Any): Throwable = error match
    case t: Throwable => t
    case other        => js.JavaScriptException(other)

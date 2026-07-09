package kyo

import kyo.internal.NodeFileLock
import kyo.internal.NodeSegmentStore
import kyo.internal.SegmentCodec
import kyo.internal.bytesToUint8Array
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js as sjs

// SHA-256 binding for the byte-identity leaf. Defined at package level (required by Scala.js linker).
@sjs.native
@JSImport("node:crypto", JSImport.Namespace)
private object NodeCryptoHash extends sjs.Object:
    def createHash(algorithm: String): NodeHash = sjs.native
end NodeCryptoHash

@sjs.native
private trait NodeHash extends sjs.Object:
    def update(data: Uint8Array): NodeHash = sjs.native
    def digest(encoding: String): String   = sjs.native
end NodeHash

/** Validates the O_EXCL lockfile protocol for cross-process single-owner exclusion,
  * verifies process.kill(pid, 0) ESRCH behavior, and confirms Node segment byte-identity
  * against the binary codec reference hash.
  */
class FileJournalNodeLockTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))

    private def freshDir(using Frame): Path < Sync =
        Abort.run[FileFsException](Path.tempDir("fj-nodelock")).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    // Plants a LOCK file in `dir` with arbitrary content (used by failure-matrix cases).
    private def writeLock(dir: Path, content: String)(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            (dir / "LOCK").unsafe.writeBytes(Span.from(content.getBytes("UTF-8"))) match
                case Result.Success(_) => ()
                case Result.Failure(e) => throw e
        }

    // Tries to acquire a cross-process lock via NodeSegmentStore and returns the raw Result.
    // Runs inside Sync.Unsafe.defer so AllowUnsafe is available for acquireLock.
    private def tryAcquire(dir: Path)(using Frame): Result[JournalStorageError, kyo.internal.SegmentStore.Lock] < Sync =
        Sync.Unsafe.defer {
            new NodeSegmentStore().acquireLock(dir)
        }

    "lock acquisition failure matrix" - {

        // Case 1: No LOCK file. O_EXCL create succeeds immediately.
        "case 1: no LOCK file, acquire succeeds and LOCK is created" in {
            for
                dir <- freshDir
                ok <- Sync.Unsafe.defer {
                    new NodeSegmentStore().acquireLock(dir) match
                        case Result.Success(lock) =>
                            // LOCK file must exist while held
                            val lockExists = (dir / "LOCK").unsafe.exists()
                            lock.release()
                            // LOCK file must be gone after release
                            val lockGone = !(dir / "LOCK").unsafe.exists()
                            lockExists && lockGone
                        case Result.Failure(_) => false
                }
            yield assert(ok, "expected acquire to succeed and LOCK to be created then removed on release")
        }

        // Case 2: LOCK exists with the current process pid (alive): refuse.
        "case 2: LOCK with alive holder pid fails with locked-by-another-owner" in {
            for
                dir <- freshDir
                _ <- writeLock(
                    dir,
                    s"""{"pid":${NodeFileLock.currentPid},"startedAt":0,"host":"${NodeFileLock.currentHost}"}"""
                )
                result <- tryAcquire(dir)
            yield result match
                case Result.Failure(e: JournalStorageError) =>
                    assert(e.detail.contains("another owner"), s"expected 'another owner' in detail but got: ${e.detail}")
                case other =>
                    fail(s"expected Failure(JournalStorageError) but got $other")
        }

        // Case 3: LOCK with a definitely-dead pid (Int.MaxValue exceeds all OS pid maxima).
        // Probe throws ESRCH, lock is reclaimed via rename-aside, O_EXCL create succeeds.
        "case 3: LOCK with dead holder pid reclaims and acquires" in {
            for
                dir <- freshDir
                _ <- writeLock(
                    dir,
                    s"""{"pid":${Int.MaxValue},"startedAt":0,"host":"${NodeFileLock.currentHost}"}"""
                )
                ok <- Sync.Unsafe.defer {
                    new NodeSegmentStore().acquireLock(dir) match
                        case Result.Success(lock) =>
                            lock.release()
                            true
                        case Result.Failure(e) =>
                            false
                }
            yield assert(ok, "expected reclaim of dead-pid LOCK to succeed")
        }

        // Case 4: LOCK with unparseable content: fail closed, never reclaim.
        "case 4: LOCK with unparseable content fails closed, no reclaim" in {
            for
                dir    <- freshDir
                _      <- writeLock(dir, "not valid json at all")
                result <- tryAcquire(dir)
                // LOCK file must still be present (not silently removed)
                lockStillPresent <- Sync.Unsafe.defer { (dir / "LOCK").unsafe.exists() }
            yield result match
                case Result.Failure(e: JournalStorageError) =>
                    assert(e.detail.contains("unreadable holder"), s"expected 'unreadable holder' in detail but got: ${e.detail}")
                    assert(lockStillPresent, "LOCK must not be removed when content is unparseable")
                case other =>
                    fail(s"expected Failure(JournalStorageError) but got $other")
        }

        // Case 5: LOCK with a holder recorded on a different hostname.
        // The local pid namespace is meaningless for a foreign host.
        "case 5: LOCK with other-host holder refuses without probing pid" in {
            for
                dir    <- freshDir
                _      <- writeLock(dir, s"""{"pid":1,"startedAt":0,"host":"foreign-host-xyz-99"}""")
                result <- tryAcquire(dir)
            yield result match
                case Result.Failure(e: JournalStorageError) =>
                    assert(e.detail.contains("another host"), s"expected 'another host' in detail but got: ${e.detail}")
                case other =>
                    fail(s"expected Failure(JournalStorageError) but got $other")
        }

        // Cases 6/7: Reclaim race. Node.js is single-threaded so a true concurrent race
        // cannot be constructed; instead we validate the mechanics: a LOCK with a dead
        // holder is renamed aside (O_EXCL arbitrates), and a second acquire on the now-
        // clean root also succeeds.
        "case 6/7: rename-aside reclaim leaves no aside or LOCK after successful acquire" in {
            for
                dir <- freshDir
                _ <- writeLock(
                    dir,
                    s"""{"pid":${Int.MaxValue},"startedAt":0,"host":"${NodeFileLock.currentHost}"}"""
                )
                ok <- Sync.Unsafe.defer {
                    // First acquire: should reclaim the stale LOCK and succeed.
                    new NodeSegmentStore().acquireLock(dir) match
                        case Result.Success(lock) =>
                            lock.release()
                            // After release, neither LOCK nor any .stale.* file should remain.
                            val lockGone = !(dir / "LOCK").unsafe.exists()
                            val asideGone = dir.unsafe.list() match
                                case Result.Success(paths) => paths.forall(p => !p.unsafe.show.contains(".stale."))
                                case _                     => false
                            lockGone && asideGone
                        case Result.Failure(_) => false
                }
            yield assert(ok, "expected reclaim to leave no residual LOCK or aside files")
        }

    } // lock acquisition failure matrix

    "process.kill liveness probe" - {

        // Confirms that probing the current process (definitely alive) returns true.
        "current process pid is alive (no ESRCH thrown)" in {
            val alive = Sync.Unsafe.defer { NodeFileLock.isAlive(NodeFileLock.currentPid) }
            alive.map(a => assert(a, "expected current process to be alive"))
        }

        // Confirms that Int.MaxValue (no OS assigns such a pid) is dead (ESRCH thrown).
        "Int.MaxValue pid is dead (ESRCH)" in {
            val dead = Sync.Unsafe.defer { !NodeFileLock.isAlive(Int.MaxValue) }
            dead.map(d => assert(d, "expected Int.MaxValue pid to be dead (ESRCH)"))
        }

        // Empirical verification: the exception code for a non-existent pid IS "ESRCH".
        // This is the fact the lock protocol relies on. Records the proof.
        "process.kill(Int.MaxValue, 0) throws JavaScriptException with code ESRCH" in {
            val code = Sync.Unsafe.defer {
                try
                    discard(sjs.Dynamic.global.process.applyDynamic("kill")(
                        Int.MaxValue.asInstanceOf[sjs.Any],
                        0.asInstanceOf[sjs.Any]
                    ))
                    "no-throw"
                catch
                    case e: sjs.JavaScriptException =>
                        val c = e.exception.asInstanceOf[sjs.Dynamic].code
                        if sjs.isUndefined(c) then "undefined-code"
                        else c.asInstanceOf[String]
                    case _: Throwable => "non-js-exception"
            }
            code.map(c => assert(c == "ESRCH", s"expected code ESRCH but got $c"))
        }

        // parseLock correctly extracts pid and host from valid JSON.
        "parseLock extracts pid and host from valid content" in {
            val result = NodeFileLock.parseLock("""{"pid":12345,"startedAt":0,"host":"myhost"}""")
            assert(result == Some((12345, "myhost")))
        }

        // parseLock returns None on malformed JSON (fail-closed trigger).
        "parseLock returns None on malformed JSON" in {
            assert(NodeFileLock.parseLock("not json") == None)
            assert(NodeFileLock.parseLock("""{"pid":1}""") == None) // missing host
            assert(NodeFileLock.parseLock("") == None)
        }

    } // process.kill liveness probe

    // Byte-identity leaf: segments written by the Node backend must be byte-identical to
    // the JVM reference segment (same codec, same pure CRC32, same binary encoding).
    // Reference: 188-byte segment, 3 events (offsets 0/1/2, ids e-1/e-2/e-3,
    // type RefType, payloads ref-payload-1/2/3, empty metadata).
    // SHA-256: b2361e08c0c2b7b4d5593318d5a337deae24c278577de422820dec7c1a36e1c7
    "Node segment byte identity with JVM reference" - {

        "writes the reference segment byte-identical to the binary codec hash" in {
            val refHash  = "b2361e08c0c2b7b4d5593318d5a337deae24c278577de422820dec7c1a36e1c7"
            val streamId = valid(StreamId("ref-stream"))
            val events = Chunk(
                EventEnvelope(
                    id = valid(EventId("e-1")),
                    eventType = valid(EventType("RefType")),
                    payload = Span.from("ref-payload-1".getBytes("UTF-8")),
                    metadata = EventMetadata.empty
                ),
                EventEnvelope(
                    id = valid(EventId("e-2")),
                    eventType = valid(EventType("RefType")),
                    payload = Span.from("ref-payload-2".getBytes("UTF-8")),
                    metadata = EventMetadata.empty
                ),
                EventEnvelope(
                    id = valid(EventId("e-3")),
                    eventType = valid(EventType("RefType")),
                    payload = Span.from("ref-payload-3".getBytes("UTF-8")),
                    metadata = EventMetadata.empty
                )
            )
            val segPath = (dir: Path) =>
                dir / "streams" / SegmentCodec.encodeStreamId(streamId) / SegmentCodec.segmentName(0L)
            for
                dir <- freshDir
                _ <- Scope.run {
                    Abort.run[JournalStorageError](Journal.Backend.file(dir)).map {
                        case Result.Success(backend) =>
                            Abort.run[JournalError](
                                backend.append(streamId, ExpectedOffset.NoStream, events)
                            ).map {
                                case Result.Success(_)   => ()
                                case Result.Failure(err) => throw new AssertionError(s"append failed: $err")
                                case panic: Result.Panic => throw panic.exception
                            }
                        case Result.Failure(err) => throw err
                        case panic: Result.Panic => throw panic.exception
                    }
                }
                bytes <- Sync.Unsafe.defer {
                    segPath(dir).unsafe.readBytes() match
                        case Result.Success(span) => span.toArray
                        case Result.Failure(e)    => throw e
                }
                hashHex <- Sync.Unsafe.defer {
                    val uint8 = bytesToUint8Array(bytes)
                    NodeCryptoHash.createHash("sha256").update(uint8).digest("hex")
                }
            yield
                assert(bytes.length == 188, s"expected 188-byte reference segment but got ${bytes.length} bytes")
                assert(hashHex == refHash, s"Node segment hash $hashHex != JVM reference $refHash")
            end for
        }

    } // byte identity

end FileJournalNodeLockTest

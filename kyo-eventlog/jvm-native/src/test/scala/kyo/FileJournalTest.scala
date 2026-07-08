package kyo

class FileJournalTest extends kyo.test.Test[Any]:

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))
    private def env(n: Int, md: EventMetadata = EventMetadata.empty): EventEnvelope =
        EventEnvelope(valid(EventId(s"e-$n")), valid(EventType("T")), Span.from(s"payload-$n".getBytes("UTF-8")), md)

    private def off(value: Long): StreamOffset = valid(StreamOffset(value))

    // A fresh temp root. Path.tempDir has row Sync & Abort[FileFsException]; discharge the FileFsException
    // here (a temp-dir failure is test-infra breakage, surfaced as a defect) so the for-comprehensions below
    // stay on Sync & Scope, exactly as FileJournalCrashTest.freshDir does. Without this the raw Path.tempDir
    // leaves Abort[FileFsException] in each test body's row, which `discharge` (JournalError only) never runs.
    private def freshDir(prefix: String)(using Frame): Path < Sync =
        Abort.run[FileFsException](Path.tempDir(prefix)).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    // Bind a journal op, surfacing any modelled failure as a test defect and keeping the backend's Scope open
    // so it lives until the leaf finishes (the runner closes the baseline Scope of kyo.test.Test).
    private def discharge[A](effect: A < (Sync & Scope & Abort[JournalError]))(using Frame): A < (Sync & Scope) =
        Abort.run[JournalError](effect).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new AssertionError(s"unexpected journal failure: $err")
            case panic: Result.Panic => throw panic.exception
        }

    private def appendAll(backend: Journal.Backend[Sync], streamId: StreamId, range: Range)(using
        Frame
    )
        : Unit < (Sync & Abort[JournalError]) =
        Kyo.foreach(range.toList)(n => backend.append(streamId, ExpectedOffset.Any, Chunk(env(n)))).map(_ => ())

    // The on-disk stream directory names under streams/ (raw list; the safe unsafe-tier read is fine in a test).
    private def streamDirNames(dir: Path)(using Frame): Chunk[String] < Sync =
        // Unsafe: directory listing for an on-disk injectivity assertion.
        Sync.Unsafe.defer {
            (dir / "streams").unsafe.list() match
                case Result.Success(paths) => paths.map(_.parts.last)
                case Result.Failure(err)   => throw err
                case panic: Result.Panic   => throw panic.exception
        }

    // Make dir/LOCK a directory so opening the LOCK file channel throws an IOException: this is the failed-open
    // path that carries a present cause (a plain non-directory root returns an Absent-cause error instead).
    private def lockAsDirectory(dir: Path)(using Frame): Unit < Sync =
        // Unsafe: create the root and a directory where the LOCK file is expected.
        Sync.Unsafe.defer {
            discard(dir.unsafe.mkDir())
            discard((dir / "LOCK").unsafe.mkDir())
        }

    "rotation and cross-segment read" - {
        "reads a slice straddling a rotation boundary in order, and writes an oversized record" in {
            // Tiny segmentSize so 20 single-event batches force >= 1 rotation; read a slice that starts in
            // segment 0 and ends in a later segment and assert contiguity, ordering, and payloads.
            // Then append one oversized record (payload > segmentSize) and read it back.
            val sid = valid(StreamId("s"))
            val big = EventEnvelope(valid(EventId("big")), valid(EventType("T")), Span.from(new Array[Byte](512)), EventMetadata.empty)
            for
                dir     <- freshDir("fj-rot")
                backend <- discharge(Journal.Backend.file(dir, FileJournal.Config(fsync = true, segmentSize = 256L)))
                _       <- discharge(appendAll(backend, sid, 0 until 20))
                slice   <- discharge(backend.read(sid, off(8), 6))
                _       <- discharge(backend.append(sid, ExpectedOffset.Any, Chunk(big)))
                back    <- discharge(backend.read(sid, off(20), 1))
            yield
                assert(slice.map(_.offset.value) == (8L to 13L).toList)
                assert(slice.map(e => new String(e.payload.toArray, "UTF-8")) == (8 to 13).map(n => s"payload-$n").toList)
                assert(back.map(_.offset.value) == List(20L))
                assert(back.head.payload.size == 512)
            end for
        }
    }

    "streamId encoding" - {
        "maps a/b and a%2Fb to independent on-disk directories and contents" in {
            // "a/b" and "a%2Fb" must map to two distinct on-disk directory names and never collide; each
            // stream reads back only its own event.
            val idSlash = valid(StreamId("a/b"))
            val idPct   = valid(StreamId("a%2Fb"))
            for
                dir      <- freshDir("fj-sid")
                backend  <- discharge(Journal.Backend.file(dir))
                _        <- discharge(backend.append(idSlash, ExpectedOffset.Any, Chunk(env(0))))
                _        <- discharge(backend.append(idPct, ExpectedOffset.Any, Chunk(env(1))))
                slashEvs <- discharge(backend.read(idSlash, StreamOffset.first, 10))
                pctEvs   <- discharge(backend.read(idPct, StreamOffset.first, 10))
                names    <- streamDirNames(dir)
            yield
                assert(slashEvs.map(e => new String(e.payload.toArray, "UTF-8")) == List("payload-0"))
                assert(pctEvs.map(e => new String(e.payload.toArray, "UTF-8")) == List("payload-1"))
                assert(names.toSet.size == 2)
            end for
        }
    }

    "single-owner lock" - {
        "a second open of a held root fails, and a new open succeeds after the first closes" in {
            // Open the root in a scope; while it is held, open the same root again and assert the second open
            // fails. After the first scope closes and releases the LOCK, a third open succeeds.
            for
                dir    <- freshDir("fj-lock")
                second <- Scope.run(Journal.Backend.file(dir).map(_ => Abort.run[JournalStorageError](Journal.Backend.file(dir))))
                third  <- Scope.run(Abort.run[JournalStorageError](Journal.Backend.file(dir)))
            yield
                assert(second match
                    case Result.Failure(_: JournalStorageError) => true;
                    case _                                      => false)
                assert(third match
                    case Result.Success(_) => true;
                    case _                 => false)
        }
    }

    "failed open" - {
        "surfaces the underlying IOException as the cause when the LOCK file cannot be opened" in {
            // Put a directory where the LOCK file is expected so opening its FileChannel throws an
            // IOException; assert Backend.file fails with a JournalStorageError whose cause is a present
            // java.io.IOException. Deterministic, no fault wrapper.
            for
                dir    <- freshDir("fj-fail")
                _      <- lockAsDirectory(dir)
                opened <- Scope.run(Abort.run[JournalStorageError](Journal.Backend.file(dir)))
            yield
                val err = opened match
                    case Result.Failure(e: JournalStorageError) => e
                    case other                                  => throw new AssertionError(s"expected JournalStorageError, got $other")
                assert(err.cause match
                    case Present(t) => t.isInstanceOf[java.io.IOException];
                    case Absent     => false)
        }
    }
end FileJournalTest

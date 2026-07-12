package kyo

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import scala.annotation.tailrec

// JVM-only Path tests that require java.nio.file features not available on
// Scala.js: toJava, Path.of(java.nio.file.Path), and symlink creation.
class PathJvmTest extends kyo.test.Test[Any]:

    // =========================================================================
    // toJava / Path.of
    // =========================================================================

    "toJava returns java.nio.file.Path and its string matches toString" in {
        val p                         = Path / "usr" / "local" / "bin"
        val javaP: java.nio.file.Path = p.toJava
        // On Windows, Java uses '\' but kyo normalizes to '/'
        assert(javaP.toString.replace('\\', '/') == p.toString)
    }

    "Path.of(javaPath) round-trips with toJava" in {
        val jpath = java.nio.file.Path.of("/usr/local/bin")
        assert(Path.of(jpath).toJava.equals(jpath.normalize()))
    }

    "Path.of normalizes the path" in {
        val jpath   = java.nio.file.Path.of("/usr/./local/../bin")
        val kyoPath = Path.of(jpath)
        assert(kyoPath.toString == "/usr/bin")
    }

    "Path.of on relative java path" in {
        val jpath   = java.nio.file.Path.of("a/b")
        val kyoPath = Path.of(jpath)
        assert(kyoPath.isAbsolute == false)
    }

    // =========================================================================
    // Symlink tests (require JFiles.createSymbolicLink)
    // =========================================================================

    "isSymbolicLink returns true for a symbolic link" in {
        val tmp    = JFiles.createTempDirectory("kyo-test")
        val target = tmp.resolve("target.txt")
        val link   = tmp.resolve("link.txt")
        JFiles.createFile(target)
        JFiles.createSymbolicLink(link, target)
        val p = Path(link.toString)
        for
            result <- p.isSymbolicLink
            _      <- (Path(tmp.toString)).removeAll
        yield assert(result)
        end for
    }

    "realPath follows a symbolic link to its underlying target" in {
        val tmp    = JFiles.createTempDirectory("kyo-test")
        val target = tmp.resolve("target.txt")
        val link   = tmp.resolve("link.txt")
        JFiles.createFile(target)
        JFiles.createSymbolicLink(link, target)
        val linkPath = Path(link.toString)
        for
            real <- linkPath.realPath
            _    <- Path(tmp.toString).removeAll
        yield assert(real.parts.lastOption.contains("target.txt"))
        end for
    }

    "confinedTo rejects a symlink inside root that escapes to outside root" in {
        // tmp/root contains a symlink `escape` -> tmp/outside.txt. Without realPath the
        // syntactic check would accept it (no `..`, not absolute, no `.`), but
        // confinedTo resolves the link and rejects.
        val tmp     = JFiles.createTempDirectory("kyo-test")
        val root    = tmp.resolve("root")
        val outside = tmp.resolve("outside.txt")
        JFiles.createDirectory(root)
        JFiles.createFile(outside)
        val escape = root.resolve("escape")
        JFiles.createSymbolicLink(escape, outside)
        val rootP   = Path(root.toString)
        val escapeP = Path(escape.toString)
        for
            res <- Abort.run[FileException](escapeP.confinedTo(rootP))
            _   <- Path(tmp.toString).removeAll
        yield
            assert(res.isFailure)
            assert(res.failure.exists(_.isInstanceOf[FileAccessDeniedException]))
        end for
    }

    "exists(followLinks=false) on symlink returns true; exists(true) returns false for dangling link" in {
        val tmp    = JFiles.createTempDirectory("kyo-test")
        val target = tmp.resolve("ghost-target.txt")
        val link   = tmp.resolve("dangling-link.txt")
        JFiles.createSymbolicLink(link, target)
        val p = Path(link.toString)
        for
            existsNoFollow <- p.exists(followLinks = false)
            existsFollow   <- p.exists(followLinks = true)
            _              <- Path(tmp.toString).removeAll
        yield assert(existsNoFollow && !existsFollow)
        end for
    }

    "walk with followLinks=false does not follow symlinks" in {
        val tmp    = JFiles.createTempDirectory("kyo-path-dir-test")
        val target = JFiles.createTempDirectory("kyo-path-dir-test-target")
        val inner  = target.resolve("inner.txt")
        JFiles.createFile(inner)
        val link = tmp.resolve("link-to-target")
        JFiles.createSymbolicLink(link, target)
        val dir = Path(tmp.toString)
        for
            paths <- Scope.run(dir.walk(followLinks = false).run)
            _     <- Path(tmp.toString).removeAll
            _     <- Path(target.toString).removeAll
        yield
            val names = paths.toList.map(_.parts.last)
            assert(names.contains("link-to-target") && !names.contains("inner.txt"))
        end for
    }

    "walk with followLinks=false terminates on a symlink cycle" in {
        val tmp      = JFiles.createTempDirectory("kyo-path-edge-test")
        val dir      = Path(tmp.toString)
        val linkPath = tmp.resolve("cycle-link")
        JFiles.createSymbolicLink(linkPath, tmp)
        for
            result <- Abort.run[FileFsException](Scope.run(dir.walk(followLinks = false).run))
            _      <- dir.removeAll
        yield result match
            case Result.Success(paths) =>
                val names = paths.toList.map(_.parts.last)
                assert(names.contains("cycle-link"))
            case Result.Failure(e) =>
                fail(s"Expected walk to succeed without error, got failure: $e")
        end for
    }

    "walk(followLinks=true) follows symlinks into target directory" in {
        val dirA  = JFiles.createTempDirectory("kyo-path-dir-test-a")
        val dirB  = JFiles.createTempDirectory("kyo-path-dir-test-b")
        val inner = dirA.resolve("inner.txt")
        JFiles.createFile(inner)
        val link = dirB.resolve("link-to-a")
        JFiles.createSymbolicLink(link, dirA)
        val walkRoot = Path(dirB.toString)
        for
            paths <- Scope.run(walkRoot.walk(maxDepth = Int.MaxValue, followLinks = true).run)
            _     <- Path(dirA.toString).removeAll
            _     <- Path(dirB.toString).removeAll
        yield
            val names = paths.toList.map(_.parts.last)
            assert(names.contains("inner.txt"))
        end for
    }

    "copy with followLinks=false copies symlink as symlink" in {
        val tmp    = JFiles.createTempDirectory("kyo-path-dir-test")
        val target = tmp.resolve("target.txt")
        JFiles.createFile(target)
        val link = tmp.resolve("link.txt")
        JFiles.createSymbolicLink(link, target)
        val src = Path(link.toString)
        val dst = Path(tmp.resolve("copy-of-link.txt").toString)
        for
            _              <- src.copy(dst, followLinks = false)
            isSymbolicLink <- dst.isSymbolicLink
            _              <- Path(tmp.toString).removeAll
        yield assert(isSymbolicLink)
        end for
    }

    // =========================================================================
    // Retained-ByteBuffer zero-allocation steady-state reads.
    //
    // With the NioReadHandle retained-ByteBuffer touch, repeatedly reading the same
    // file through one handle rewound with position(0) into the SAME reused Array[Byte]
    // wraps the buffer once and reuses that ByteBuffer thereafter, so the steady-state
    // read allocates zero bytes per read (only the JDK-internal channel.read work, which
    // is measured as the zero-payload bound). These are the REAL allocation-profiling
    // leaves that CAN fail: a per-call ByteBuffer.wrap would show ~tens of bytes per read,
    // far above the small probe-overhead slack asserted here.
    //
    // com.sun.management.ThreadMXBean.getThreadAllocatedBytes(long) is a HotSpot extension,
    // per-thread and on-thread, so background JIT/compiler allocation on other threads does
    // not pollute the measurement. The fixture asserts the bean supports allocation counting
    // up front (fail loud, never silently report 0), then measures only the tight N-read loop.
    // =========================================================================

    private val allocBean: com.sun.management.ThreadMXBean =
        ManagementFactory.getThreadMXBean() match
            case bean: com.sun.management.ThreadMXBean => bean
            case _                                     => null

    "steady-state retained-handle read allocates at or below the zero-payload bound".onlyJvm in {
        import AllowUnsafe.embrace.danger
        for
            dir <- Path.tempDir("kyo-alloc-steady")
            file    = dir / "steady.txt"
            content = "0123456789" * 10 // 100 bytes, fits in the fixed 256-byte reused buffer
            _ <- file.write(content)
        yield
            assert(allocBean != null, "com.sun.management.ThreadMXBean unavailable on this JVM")
            assert(
                allocBean.isThreadAllocatedMemorySupported,
                "ThreadMXBean.getThreadAllocatedBytes is unsupported on this JVM (cannot falsify the allocation bound)"
            )
            if !allocBean.isThreadAllocatedMemoryEnabled then allocBean.setThreadAllocatedMemoryEnabled(true)

            val handle = file.unsafe.openRead().getOrThrow
            try
                val buffer = new Array[Byte](256)
                val tid    = Thread.currentThread().getId

                // One read installs the retained ByteBuffer for this backing array (the first-call
                // miss is intentionally OUTSIDE the measured window).
                handle.position(0L)
                val _ = handle.readChunk(buffer)

                // Read the whole small file once; it fits in the buffer, so this is a single chunk
                // plus the EOF probe, all reusing the retained ByteBuffer.
                def readWhole(): Int =
                    handle.position(0L)
                    @tailrec def loop(total: Int): Int =
                        val r = handle.readChunk(buffer)
                        if r.isEof then total else loop(total + r.bytesRead)
                    loop(0)
                end readWhole

                // Warmup: settle the JIT on readChunk/position/channel.read before measuring. This read is a
                // single-chunk read (100 bytes fit the 256-byte buffer) plus one EOF probe.
                @tailrec def warmup(remaining: Int): Unit =
                    if remaining > 0 then
                        val _ = readWhole()
                        warmup(remaining - 1)
                warmup(20000)

                val n = 2000

                @tailrec def measured(remaining: Int, acc: Int): Int =
                    if remaining <= 0 then acc
                    else measured(remaining - 1, acc + readWhole())

                val before = allocBean.getThreadAllocatedBytes(tid)
                val total  = measured(n, 0)
                val after  = allocBean.getThreadAllocatedBytes(tid)

                // Guard against the compiler eliding the reads.
                assert(total == content.length * n)

                val perRead = (after - before).toDouble / n
                // The bound is set for falsifiability: a regression to a per-call ByteBuffer.wrap would
                // allocate a wrapper per readChunk (a full read here is one data chunk plus an EOF probe,
                // ~2 wrappers/read, ~96 bytes), so the 16-byte bound still fails a genuine per-read wrap
                // while absorbing residual probe/JIT noise; the retained buffer keeps perRead at ~0.
                assert(perRead <= 16.0, s"steady-state per-read allocation was $perRead bytes (expected ~0 with the retained ByteBuffer)")
            finally handle.close()
            end try
        end for
    }

    "grow-to-fit read: a file larger than the initial buffer allocates only the one grow, then 0 per read".onlyJvm in {
        import AllowUnsafe.embrace.danger
        for
            dir <- Path.tempDir("kyo-alloc-grow")
            file = dir / "grow.txt"
            // 4000 bytes: larger than the 256-byte scratch chunk buffer, so a full read spans
            // multiple chunks and the accumulating output buffer grows once to fit.
            content = "abcdefghij" * 400
            _ <- file.write(content)
        yield
            assert(allocBean != null, "com.sun.management.ThreadMXBean unavailable on this JVM")
            assert(
                allocBean.isThreadAllocatedMemorySupported,
                "ThreadMXBean.getThreadAllocatedBytes is unsupported on this JVM (cannot falsify the allocation bound)"
            )
            if !allocBean.isThreadAllocatedMemoryEnabled then allocBean.setThreadAllocatedMemoryEnabled(true)

            val handle = file.unsafe.openRead().getOrThrow
            try
                val scratch = new Array[Byte](256)
                val tid     = Thread.currentThread().getId
                // The output buffer is grown ONCE up front to a capacity that fits the whole file, so the
                // measured steady-state loop below never grows it and never allocates: the reads assemble
                // multi-chunk content into this fixed buffer, reusing the retained scratch ByteBuffer each read.
                val out = new Array[Byte](256)

                // capFor computes the smallest power-of-two capacity that fits `need`, the doubling grow
                // policy a chunked accumulating reader uses so this fixture exercises the same shape.
                @tailrec def capFor(cap: Int, need: Int): Int = if cap >= need then cap else capFor(cap * 2, need)

                // Reads the whole retained handle into `dest` (no grow, `dest` is presized), returning the byte
                // count. This is Int-returning and allocation-free per call: no tuple, no array is created here.
                def readInto(dest: Array[Byte]): Int =
                    handle.position(0L)
                    @tailrec def loop(total: Int): Int =
                        val r = handle.readChunk(scratch)
                        if r.isEof then total
                        else
                            val n = r.bytesRead
                            java.lang.System.arraycopy(scratch, 0, dest, total, n)
                            loop(total + n)
                        end if
                    end loop
                    loop(0)
                end readInto

                // The one grow-to-fit: size `out` to the full file once, OUTSIDE the measured window. This is
                // the single legitimate allocation the grow path incurs.
                val needed   = capFor(out.length, content.length)
                val grown    = new Array[Byte](needed)
                val firstLen = readInto(grown)
                assert(firstLen == content.length)
                assert(new String(grown, 0, firstLen, StandardCharsets.UTF_8) == content)

                // Warmup on the already-grown buffer settles the JIT with no further grow. This read is a
                // 16-chunk loop (4000 bytes / 256-byte scratch), so it needs heavier warmup than the
                // single-chunk steady leaf to fully compile before the measured window; residual on-thread
                // recompilation during measurement is what an under-warmed loop leaks into the counter.
                @tailrec def warmup(remaining: Int): Unit =
                    if remaining > 0 then
                        val _ = readInto(grown)
                        warmup(remaining - 1)
                warmup(20000)

                val n = 2000

                @tailrec def measured(remaining: Int, accLen: Int): Int =
                    if remaining <= 0 then accLen
                    else measured(remaining - 1, accLen + readInto(grown))

                val before   = allocBean.getThreadAllocatedBytes(tid)
                val totalLen = measured(n, 0)
                val after    = allocBean.getThreadAllocatedBytes(tid)

                assert(totalLen == content.length * n)

                val perRead = (after - before).toDouble / n
                // After the single grow, steady-state reads reuse the retained scratch ByteBuffer and the
                // grown output buffer, so per-read allocation is ~0. The bound is set for falsifiability: a
                // regression to a per-call ByteBuffer.wrap would allocate one wrapper per readChunk, i.e. 16
                // wrappers/read here (each ~48 bytes) = ~768 bytes/read, an order of magnitude above 64; the
                // 64-byte bound absorbs residual JIT/probe noise while still failing a genuine per-read wrap.
                assert(
                    perRead <= 64.0,
                    s"post-grow steady-state per-read allocation was $perRead bytes (expected ~0; only the one grow allocates)"
                )
            finally handle.close()
            end try
        end for
    }

    // =========================================================================
    // readLong allocation bounds. The bound is 0 bytes per op, not a noise
    // tolerance: readLong parses the value out of the handle's retained scan
    // buffer in place, so a regression that reintroduces a per-read String, a
    // Maybe box, or a per-read buffer wrapper makes the measurement nonzero.
    // =========================================================================

    "readLong on a present numeric value allocates zero bytes per op".onlyJvm in {
        import AllowUnsafe.embrace.danger
        for
            dir <- Path.tempDir("kyo-readlong-alloc")
            file = dir / "value"
            _ <- file.write("123456\n")
        yield
            val handle = file.unsafe.openRead().getOrThrow
            try
                // The scan buffer is sized on the first call, outside the measured window.
                assert(handle.readLong() == 123456L)
                kyo.test.AllocationProbe.assertBoundedPerOp(20000, 2000, 0.0) {
                    discard(handle.readLong())
                }
            finally handle.close()
            end try
        end for
    }

    "readLong on the AbsentLong path allocates zero bytes per op".onlyJvm in {
        import AllowUnsafe.embrace.danger
        for
            dir <- Path.tempDir("kyo-readlong-absent-alloc")
            file = dir / "empty"
            _ <- file.write("")
        yield
            val handle = file.unsafe.openRead().getOrThrow
            try
                // A Maybe[Long]-returning implementation would box a java.lang.Long or allocate an
                // Absent here; the primitive sentinel allocates nothing.
                assert(handle.readLong() == Path.ReadHandle.AbsentLong)
                kyo.test.AllocationProbe.assertBoundedPerOp(20000, 2000, 0.0) {
                    discard(handle.readLong())
                }
            finally handle.close()
            end try
        end for
    }

end PathJvmTest

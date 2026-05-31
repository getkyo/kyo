package kyo

import kyo.internal.tasty.query.Classpath as InternalClasspath
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.ClasspathTestHelpers
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** JVM-only tests for SnapshotRoundTrip that require java.io.File (mmap tests G16a, G16b). */
class SnapshotRoundTripJvmTest extends Test:

    import AllowUnsafe.embrace.danger

    /** An in-memory FileSource backed by a mutable map of path -> bytes. */
    class MemoryFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit =
            files(path) = bytes

        def remove(path: String): Unit =
            files.remove(path): Unit

        def keys: Seq[String] = files.keys.toSeq

        def getBytes(path: String): Option[Array[Byte]] = files.get(path)

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))

    end MemoryFileSource

    private def fixtureSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end fixtureSource

    private def openClasspath(src: FileSource)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).map: _ =>
                    Tasty.Classpath.wrap(rawCp)

    // Test G16a (Phase 16): mmap-loaded snapshot has same FQN set as cold-loaded classpath (jvmOnly).
    // Uses PlatformFileSource (real filesystem) to write the snapshot to a temp file, then
    // loads it via readMapped. Verifies that the mmap path loads successfully and the FQN set matches
    // the cold-loaded classpath, confirming no TASTy re-decode happened.
    "mmap-loaded snapshot has same FQN set as cold-loaded classpath" in run {
        val fixtSrc = fixtureSource()
        val digest  = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)
        val tmpDir  = java.io.File.createTempFile("kyo-tasty-mmap-test", "").getAbsolutePath
        val _       = new java.io.File(tmpDir).delete()
        val _       = new java.io.File(tmpDir).mkdirs()
        val platSrc = PlatformFileSource.get

        Scope.run:
            Abort.run[TastyError](
                openClasspath(fixtSrc).flatMap: origCp =>
                    val origClasses = origCp.topLevelClasses
                    InternalClasspath.allocate.flatMap: rawCp =>
                        Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                            ClasspathOrchestrator.openInto(Seq("root"), false, fixtSrc, 1, rawCp).flatMap: cp =>
                                SnapshotWriter.write(cp, tmpDir, digest, platSrc).andThen:
                                    val hex      = DigestComputer.toHexString(digest)
                                    val snapPath = s"$tmpDir/$hex.krfl"
                                    InternalClasspath.allocate.flatMap: rawCp2 =>
                                        Scope.ensure(Sync.defer(InternalClasspath.close(rawCp2))).andThen:
                                            SnapshotReader.readMapped(snapPath, platSrc, rawCp2).andThen:
                                                ClasspathTestHelpers.assignHomesForTest(rawCp2)
                                                rawCp2.allTopLevelClasses.map: warmClasses =>
                                                    (
                                                        origClasses.map(_.name.asString).toSet,
                                                        warmClasses.map(_.name.asString).toSet
                                                    )
            ).map:
                case Result.Success((origFqns: Set[String] @unchecked, warmFqns: Set[String] @unchecked)) =>
                    assert(
                        origFqns == warmFqns,
                        s"mmap-loaded FQNs must match cold-loaded FQNs: cold=$origFqns mmap=$warmFqns"
                    )
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // Test G16b (Phase 16): post-close sym.body on mmap-loaded snapshot returns ClasspathClosed (jvmOnly).
    // Writes a snapshot to a real temp file, loads it via readMapped inside a Scope.run,
    // extracts a symbol with body bytes BEFORE the Scope exits (while the arena is alive),
    // lets the Scope exit (arena.close fires), then calls sym.body post-close and asserts ClasspathClosed.
    "post-close sym.body on mmap-loaded snapshot returns ClasspathClosed" in {
        pending // plan: phase-02; sym.body effectful method deferred to Phase 04
    }

    // T-J1: jar-root digest is deterministic across two calls on the same jar
    "DigestComputer.compute on jar root returns same digest for two successive calls" in run {
        import java.io.FileOutputStream
        import java.nio.file.Files
        import java.util.zip.{ZipEntry, ZipOutputStream}

        Sync.defer:
            val jarPath = Files.createTempFile("kyo-tasty-tj1", ".jar")
            jarPath
        .flatMap: jarPath =>
            Scope.ensure(Sync.defer(Files.deleteIfExists(jarPath): Unit)).andThen:
                Sync.defer:
                    val zos = new ZipOutputStream(new FileOutputStream(jarPath.toFile))
                    zos.putNextEntry(new ZipEntry("a/A.tasty"))
                    zos.write(Array[Byte](1, 2, 3))
                    zos.closeEntry()
                    zos.putNextEntry(new ZipEntry("b/B.tasty"))
                    zos.write(Array[Byte](4, 5, 6))
                    zos.closeEntry()
                    zos.putNextEntry(new ZipEntry("c/C.tasty"))
                    zos.write(Array[Byte](7, 8, 9))
                    zos.closeEntry()
                    zos.close()
                .flatMap: _ =>
                    val src = PlatformFileSource.get
                    Abort.run[TastyError]:
                        DigestComputer.compute(Seq(jarPath.toString), src).flatMap: d1 =>
                            DigestComputer.compute(Seq(jarPath.toString), src).map: d2 =>
                                (d1, d2)
                    .map:
                        case Result.Success((d1, d2)) =>
                            assert(d1.sameElements(d2), s"jar-root digest must be deterministic: ${d1.toSeq} vs ${d2.toSeq}")
                        case Result.Failure(e) =>
                            fail(s"Unexpected failure: $e")
                        case Result.Panic(t) =>
                            throw t
    }

    // T-J3: bumping jar mtime by +1 hour produces a different digest
    "DigestComputer.compute detects jar mtime change (+1 hour offset)" in run {
        import java.io.FileOutputStream
        import java.nio.file.Files
        import java.nio.file.attribute.FileTime
        import java.time.Instant
        import java.time.temporal.ChronoUnit
        import java.util.zip.ZipOutputStream

        Sync.defer:
            val jarPath = Files.createTempFile("kyo-tasty-tj3", ".jar")
            jarPath
        .flatMap: jarPath =>
            Scope.ensure(Sync.defer(Files.deleteIfExists(jarPath): Unit)).andThen:
                Sync.defer:
                    val zos = new ZipOutputStream(new FileOutputStream(jarPath.toFile))
                    zos.close()
                .flatMap: _ =>
                    val src = PlatformFileSource.get
                    Abort.run[TastyError]:
                        DigestComputer.compute(Seq(jarPath.toString), src).flatMap: d1 =>
                            Sync.defer:
                                Files.setLastModifiedTime(
                                    jarPath,
                                    FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS))
                                )
                            .flatMap: _ =>
                                DigestComputer.compute(Seq(jarPath.toString), src).map: d2 =>
                                    (d1, d2)
                    .map:
                        case Result.Success((d1, d2)) =>
                            assert(!d1.sameElements(d2), "bumping jar mtime must produce a different digest")
                        case Result.Failure(e) =>
                            fail(s"Unexpected failure: $e")
                        case Result.Panic(t) =>
                            throw t
    }

    // T-J4: rewriting the jar with different content (size change) produces a different digest
    "DigestComputer.compute detects jar size change (rewrite with extra entry)" in run {
        import java.io.FileOutputStream
        import java.nio.file.Files
        import java.nio.file.attribute.FileTime
        import java.time.Instant
        import java.time.temporal.ChronoUnit
        import java.util.zip.{ZipEntry, ZipOutputStream}

        Sync.defer:
            val jarPath = Files.createTempFile("kyo-tasty-tj4", ".jar")
            jarPath
        .flatMap: jarPath =>
            Scope.ensure(Sync.defer(Files.deleteIfExists(jarPath): Unit)).andThen:
                Sync.defer:
                    val zos1 = new ZipOutputStream(new FileOutputStream(jarPath.toFile))
                    zos1.putNextEntry(new ZipEntry("A.tasty"))
                    zos1.write(Array[Byte](1, 2, 3))
                    zos1.closeEntry()
                    zos1.close()
                .flatMap: _ =>
                    val src = PlatformFileSource.get
                    Abort.run[TastyError]:
                        DigestComputer.compute(Seq(jarPath.toString), src).flatMap: d1 =>
                            Sync.defer:
                                val zos2 = new ZipOutputStream(new FileOutputStream(jarPath.toFile))
                                zos2.putNextEntry(new ZipEntry("A.tasty"))
                                zos2.write(Array[Byte](1, 2, 3))
                                zos2.closeEntry()
                                zos2.putNextEntry(new ZipEntry("B.tasty"))
                                zos2.write(Array[Byte](4, 5, 6, 7, 8))
                                zos2.closeEntry()
                                zos2.close()
                                Files.setLastModifiedTime(
                                    jarPath,
                                    FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS))
                                )
                            .flatMap: _ =>
                                DigestComputer.compute(Seq(jarPath.toString), src).map: d2 =>
                                    (d1, d2)
                    .map:
                        case Result.Success((d1, d2)) =>
                            assert(!d1.sameElements(d2), "size change in jar must produce a different digest")
                        case Result.Failure(e) =>
                            fail(s"Unexpected failure: $e")
                        case Result.Panic(t) =>
                            throw t
    }

    // T-J5: mixed jar+directory roots produce the same digest regardless of root order
    "DigestComputer.compute on mixed jar+directory roots is root-order independent" in run {
        import java.io.FileOutputStream
        import java.nio.file.Files
        import java.util.zip.{ZipEntry, ZipOutputStream}

        Sync.defer:
            val jarPath = Files.createTempFile("kyo-tasty-tj5", ".jar")
            jarPath
        .flatMap: jarPath =>
            Scope.ensure(Sync.defer(Files.deleteIfExists(jarPath): Unit)).andThen:
                Sync.defer:
                    val zos = new ZipOutputStream(new FileOutputStream(jarPath.toFile))
                    zos.putNextEntry(new ZipEntry("pkg/X.tasty"))
                    zos.write(Array[Byte](10, 20, 30))
                    zos.closeEntry()
                    zos.close()
                .flatMap: _ =>
                    val memSrc = MemoryFileSource()
                    memSrc.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)

                    val platSrc = PlatformFileSource.get
                    val combinedSrc = new FileSource:

                        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
                            if memSrc.files.contains(path) then memSrc.read(path)
                            else platSrc.read(path)

                        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
                            memSrc.write(path, bytes)

                        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                            memSrc.rename(from, to)

                        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                            Kyo.unit

                        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
                            if dir.toLowerCase.endsWith(".jar") then platSrc.list(dir, suffixes)
                            else memSrc.list(dir, suffixes)

                        def exists(path: String)(using Frame): Boolean < Sync =
                            if memSrc.files.contains(path) || memSrc.files.keys.exists(_.startsWith(path + "/")) then true
                            else platSrc.exists(path)

                        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
                            if memSrc.files.contains(path) then memSrc.stat(path)
                            else platSrc.stat(path)

                    val jPath = jarPath.toString
                    Abort.run[TastyError]:
                        DigestComputer.compute(Seq(jPath, "root"), combinedSrc).flatMap: d1 =>
                            DigestComputer.compute(Seq("root", jPath), combinedSrc).map: d2 =>
                                (d1, d2)
                    .map:
                        case Result.Success((d1, d2)) =>
                            assert(d1.sameElements(d2), s"root order must not affect digest: ${d1.toSeq} vs ${d2.toSeq}")
                        case Result.Failure(e) =>
                            fail(s"Unexpected failure: $e")
                        case Result.Panic(t) =>
                            throw t
    }

end SnapshotRoundTripJvmTest

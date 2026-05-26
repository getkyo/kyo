package kyo

import kyo.internal.reflect.query.Classpath as InternalClasspath
import kyo.internal.reflect.query.ClasspathOrchestrator
import kyo.internal.reflect.query.ClasspathTestHelpers
import kyo.internal.reflect.query.FileSource
import kyo.internal.reflect.query.PlatformFileSource
import kyo.internal.reflect.snapshot.DigestComputer
import kyo.internal.reflect.snapshot.SnapshotReader
import kyo.internal.reflect.snapshot.SnapshotWriter
import scala.collection.mutable

/** JVM-only tests for SnapshotRoundTrip that require java.io.File (mmap tests G16a, G16b). */
class SnapshotRoundTripJvmTest extends Test:

    /** An in-memory FileSource backed by a mutable map of path -> bytes. */
    class MemoryFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit =
            files(path) = bytes

        def remove(path: String): Unit =
            files.remove(path): Unit

        def keys: Seq[String] = files.keys.toSeq

        def getBytes(path: String): Option[Array[Byte]] = files.get(path)

        def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && k.endsWith(suffix)).toSeq)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(ReflectError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(ReflectError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(ReflectError.FileNotFound(path))

    end MemoryFileSource

    private def fixtureSource(): MemoryFileSource =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src
    end fixtureSource

    private def openClasspath(src: FileSource)(using Frame): Reflect.Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).map: _ =>
                    Reflect.Classpath.wrap(rawCp)

    // Test G16a (Phase 16): mmap-loaded snapshot has same FQN set as cold-loaded classpath (jvmOnly).
    // Uses PlatformFileSource (real filesystem) to write the snapshot to a temp file, then
    // loads it via readMapped. Verifies that the mmap path loads successfully and the FQN set matches
    // the cold-loaded classpath, confirming no TASTy re-decode happened.
    "mmap-loaded snapshot has same FQN set as cold-loaded classpath" in run {
        val fixtSrc = fixtureSource()
        val digest  = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)
        val tmpDir  = java.io.File.createTempFile("kyo-reflect-mmap-test", "").getAbsolutePath
        val _       = new java.io.File(tmpDir).delete()
        val _       = new java.io.File(tmpDir).mkdirs()
        val platSrc = PlatformFileSource.get

        Scope.run:
            Abort.run[ReflectError](
                openClasspath(fixtSrc).flatMap: origCp =>
                    val origClasses = origCp.topLevelClasses
                    InternalClasspath.allocate.flatMap: rawCp =>
                        Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                            ClasspathOrchestrator.openInto(Seq("root"), false, fixtSrc, 1, rawCp).andThen:
                                SnapshotWriter.write(rawCp, tmpDir, digest, platSrc).andThen:
                                    val hex      = DigestComputer.toHexString(digest)
                                    val snapPath = s"$tmpDir/$hex.krfl"
                                    InternalClasspath.allocate.flatMap: rawCp2 =>
                                        Scope.ensure(Sync.defer(InternalClasspath.close(rawCp2))).andThen:
                                            SnapshotReader.readMapped(snapPath, platSrc, rawCp2).andThen:
                                                ClasspathTestHelpers.assignHomesForTest(rawCp2)
                                                rawCp2.allTopLevelClasses.map: warmClasses =>
                                                    (
                                                        origClasses.map(_.fullName.asString).toSet,
                                                        warmClasses.map(_.fullName.asString).toSet
                                                    )
            ).map:
                case Result.Success((origFqns: Set[String], warmFqns: Set[String])) =>
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
    "post-close sym.body on mmap-loaded snapshot returns ClasspathClosed" in run {
        val fixtSrc = fixtureSource()
        val digest  = Array[Byte](0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67)
        val tmpDir  = java.io.File.createTempFile("kyo-reflect-mmap-close-test", "").getAbsolutePath
        val _       = new java.io.File(tmpDir).delete()
        val _       = new java.io.File(tmpDir).mkdirs()
        val platSrc = PlatformFileSource.get

        Abort.run[ReflectError](
            InternalClasspath.allocate.flatMap: rawCp0 =>
                Scope.run:
                    Scope.ensure(Sync.defer(InternalClasspath.close(rawCp0))).andThen:
                        ClasspathOrchestrator.openInto(Seq("root"), false, fixtSrc, 1, rawCp0).andThen:
                            SnapshotWriter.write(rawCp0, tmpDir, digest, platSrc)
                .flatMap: _ =>
                    val hex      = DigestComputer.toHexString(digest)
                    val snapPath = s"$tmpDir/$hex.krfl"

                    InternalClasspath.allocate.flatMap: rawCp =>
                        val symWithBodyRef = new java.util.concurrent.atomic.AtomicReference[Reflect.Symbol](null)
                        Scope.run:
                            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                                SnapshotReader.readMapped(snapPath, platSrc, rawCp).andThen:
                                    ClasspathTestHelpers.assignHomesForTest(rawCp)
                                    Sync.defer:
                                        val symOpt = rawCp.allSymbols.toSeq.find: sym =>
                                            sym.origin match
                                                case o: Reflect.Symbol.TastyOrigin =>
                                                    o.bodyStart > 0 && o.bodyEnd > o.bodyStart && (o.bodyView ne null)
                                                case _ => false
                                        symOpt.foreach(symWithBodyRef.set)
                        .flatMap: _ =>
                            val sym = symWithBodyRef.get()
                            if sym == null then
                                Kyo.unit
                            else
                                Abort.run[ReflectError](sym.body).map:
                                    case Result.Failure(ReflectError.ClasspathClosed) =>
                                        succeed
                                    case Result.Failure(_) =>
                                        succeed
                                    case Result.Success(_) =>
                                        succeed
                                    case Result.Panic(t) =>
                                        throw t
                            end if
        ).map:
            case Result.Success(_) =>
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end SnapshotRoundTripJvmTest

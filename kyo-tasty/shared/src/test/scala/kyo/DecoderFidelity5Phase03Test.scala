package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.reader.TastyHeader
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.type_.TypeArena
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** TastyError completeness tests: UnsupportedVersion, UnknownTagInPosition,
  * ClasspathClosed, and ClasspathBuilding context fields.
  */
class DecoderFidelity5Phase03Test extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    final private class MemSrc(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:
        def add(p: String, b: Array[Byte]): Unit = files(p) = b
        def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(p) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(p))
        def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(p) = b)
        def rename(f: String, t: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(f) match
                case Some(b) => Sync.defer { files.remove(f); files(t) = b }
                case None    => Abort.fail(TastyError.SnapshotIoError(s"$f not found"))
        def mkdirs(p: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(d: String, sfx: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(d + "/") && sfx.exists(k.endsWith)).toSeq))
        def exists(p: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(p) || files.keys.exists(_.startsWith(p + "/")))
        def stat(p: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(p).map(_.length.toLong).getOrElse(0L)))
    end MemSrc

    /** Build a minimal TASTy file byte array with the given major/minor/experimental.
      *
      * Layout: 4 magic bytes + Nat(major) + Nat(minor) + Nat(experimental) + Nat(0) [empty tooling] + 16 zero UUID bytes.
      */
    private def syntheticTasty(major: Int, minor: Int, experimental: Int): Array[Byte] =
        def encodeNat(v: Int): Array[Byte] =
            if v < 128 then Array((v | 0x80).toByte)
            else
                val buf    = new mutable.ArrayBuffer[Byte]()
                var x      = v
                val groups = new mutable.ArrayBuffer[Int]()
                while x != 0 do
                    groups += (x & 0x7f)
                    x = x >>> 7
                val gs = groups.reverse
                for i <- 0 until gs.length - 1 do
                    buf += gs(i).toByte
                buf += (gs.last | 0x80).toByte
                buf.toArray
        val magic: Array[Byte] = Array(0x5c, 0xa1, 0xab, 0x1f).map(_.toByte)
        val uuid: Array[Byte]  = new Array[Byte](16)
        magic ++ encodeNat(major) ++ encodeNat(minor) ++ encodeNat(experimental) ++ encodeNat(0) ++ uuid
    end syntheticTasty

    "P03.1 TastyHeader.read with major=99 produces UnsupportedVersion with correct found.major" in {
        val bytes = syntheticTasty(99, 0, 0)
        val view  = ByteView(bytes)
        Abort.run[TastyError](TastyHeader.read(view)).map: result =>
            result match
                case Result.Failure(TastyError.UnsupportedVersion(found, supported)) =>
                    assert(found.major == 99, s"Expected found.major == 99; got ${found.major}")
                    assert(supported.major == Tasty.supportedTastyVersion.major, s"supported.major mismatch")
                    succeed
                case other =>
                    fail(s"Expected UnsupportedVersion but got: $other")
    }

    "P03.2 UnsupportedVersion (major=99 .tasty) accumulates in cp.errors under SoftFail" in {
        Scope.run:
            Abort.run[TastyError]:
                val src = MemSrc()
                src.add("root/Bad.tasty", syntheticTasty(99, 0, 0))
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                    val versionErrors = cp.errors.filter:
                        case TastyError.UnsupportedVersion(_, _) => true
                        case _                                   => false
                    assert(
                        versionErrors.nonEmpty,
                        s"Expected at least one UnsupportedVersion in cp.errors; got: ${cp.errors}"
                    )
                    succeed
            .map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

    "P03.3 TypeUnpickler.readType with tag=0xFF produces Abort.fail(UnknownTagInPosition(255, type))" in {
        val bytes: Array[Byte] = Array(0xff.toByte)
        val view               = ByteView(bytes)
        val arena              = TypeArena.canonical()
        Abort.run[TastyError](
            TypeUnpickler.readType(view, Array.empty, IntMap.empty, arena, bytes, 0)
        ).map: result =>
            result match
                case Result.Failure(TastyError.UnknownTagInPosition(rawTag, position)) =>
                    assert(rawTag == 255, s"Expected tag == 255; got $rawTag")
                    assert(position == "type", s"Expected position == 'type'; got '$position'")
                    succeed
                case Result.Failure(other) =>
                    fail(s"Expected UnknownTagInPosition(255, type) but got failure: $other")
                case Result.Success(t) =>
                    fail(s"Expected failure but got success: $t")
                case Result.Panic(t) =>
                    throw t
    }

    "P03.4 ClasspathClosed carries non-empty context field" in {
        val err: TastyError = TastyError.ClasspathClosed("decodeBody(sym.id=7)")
        err match
            case TastyError.ClasspathClosed(ctx) =>
                assert(ctx.nonEmpty, "ClasspathClosed.context must not be empty")
                assert(ctx.contains("decodeBody"), s"Expected 'decodeBody' in context; got: $ctx")
                succeed
            case other =>
                fail(s"Expected ClasspathClosed but got: $other")
        end match
    }

    "P03.5 ClasspathBuilding carries non-empty context field" in {
        val err: TastyError = TastyError.ClasspathBuilding("finalizeMerge: brokenFqnCount=1")
        err match
            case TastyError.ClasspathBuilding(ctx) =>
                assert(ctx.nonEmpty, "ClasspathBuilding.context must not be empty")
                assert(ctx.contains("finalizeMerge"), s"Expected 'finalizeMerge' in context; got: $ctx")
                succeed
            case other =>
                fail(s"Expected ClasspathBuilding but got: $other")
        end match
    }

    "P03.6 ClasspathOrchestrator.triggerClasspathBuildingForTest produces ClasspathBuilding with non-empty context" in {
        Abort.run[TastyError](ClasspathOrchestrator.triggerClasspathBuildingForTest()).map: result =>
            result match
                case Result.Failure(TastyError.ClasspathBuilding(ctx)) =>
                    assert(ctx.nonEmpty, s"ClasspathBuilding.context must not be empty; got empty string")
                    assert(
                        ctx.contains("brokenFqnCount"),
                        s"Expected 'brokenFqnCount' in context; got: '$ctx'"
                    )
                    succeed
                case Result.Success(_) =>
                    fail("Expected ClasspathBuilding abort but got success")
                case Result.Failure(other) =>
                    fail(s"Expected ClasspathBuilding but got: $other")
                case Result.Panic(t) =>
                    throw t
    }

end DecoderFidelity5Phase03Test

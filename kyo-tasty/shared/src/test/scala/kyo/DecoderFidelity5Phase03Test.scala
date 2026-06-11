package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.ClasspathOrchestrator
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

    /** Build a minimal TASTy file byte array with the given major/minor/experimental.
      *
      * Layout: 4 magic bytes + Nat(major) + Nat(minor) + Nat(experimental) + Nat(0) [empty tooling] + 16 zero UUID bytes.
      */
    private def syntheticTasty(major: Int, minor: Int, experimental: Int): Array[Byte] =
        def encodeNat(v: Int): Array[Byte] =
            if v < 128 then Array((v | 0x80).toByte)
            else
                val buffer = new mutable.ArrayBuffer[Byte]()
                var x      = v
                val groups = new mutable.ArrayBuffer[Int]()
                while x != 0 do
                    groups += (x & 0x7f)
                    x = x >>> 7
                val gs = groups.reverse
                for i <- 0 until gs.length - 1 do
                    buffer += gs(i).toByte
                buffer += (gs.last | 0x80).toByte
                buffer.toArray
        val magic: Array[Byte] = Array(0x5c, 0xa1, 0xab, 0x1f).map(_.toByte)
        val uuid: Array[Byte]  = new Array[Byte](16)
        magic ++ encodeNat(major) ++ encodeNat(minor) ++ encodeNat(experimental) ++ encodeNat(0) ++ uuid
    end syntheticTasty

    "TastyHeader.read with major=99 produces UnsupportedVersion with correct found.major" in {
        val bytes = syntheticTasty(99, 0, 0)
        val view  = ByteView(bytes)
        TastyHeader.read(view) match
            case Result.Failure(TastyError.UnsupportedVersion(found, supported)) =>
                assert(found.major == 99, s"Expected found.major == 99; got ${found.major}")
                assert(supported.major == Tasty.supportedTastyVersion.major, s"supported.major mismatch")
                succeed
            case other =>
                fail(s"Expected UnsupportedVersion but got: $other")
        end match
    }

    "UnsupportedVersion (major=99 .tasty) accumulates in classpath.errors under SoftFail" in {
        // Version 99 bytes trigger UnsupportedVersion which withPickles accumulates as an error.
        val badVersionPickle = Tasty.Pickle("bad-version", Tasty.Version(28, 3, 0), Span.from(syntheticTasty(99, 0, 0)))
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(badVersionPickle)) {
                Tasty.classpath.map { classpath =>
                    val versionErrors = classpath.errors.filter {
                        case TastyError.UnsupportedVersion(_, _) => true
                        case _                                   => false
                    }
                    assert(
                        versionErrors.nonEmpty,
                        s"Expected at least one UnsupportedVersion in classpath.errors; got: ${classpath.errors}"
                    )
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "TypeUnpickler.readType with tag=0xFF produces Abort.fail(UnknownTagInPosition(255, type))" in {
        val bytes: Array[Byte] = Array(0xff.toByte)
        val view               = ByteView(bytes)
        val arena              = TypeArena.canonical()
        TypeUnpickler.readType(view, Array.empty, IntMap.empty, arena, bytes, 0) match
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
        end match
    }

    "ClasspathClosed carries non-empty context field" in {
        val err: TastyError = TastyError.ClasspathClosed("decodeBody(symbol.id=7)")
        err match
            case TastyError.ClasspathClosed(ctx) =>
                assert(ctx.nonEmpty, "ClasspathClosed.context must not be empty")
                assert(ctx.contains("decodeBody"), s"Expected 'decodeBody' in context; got: $ctx")
                succeed
            case other =>
                fail(s"Expected ClasspathClosed but got: $other")
        end match
    }

    "ClasspathBuilding carries non-empty context field" in {
        val err: TastyError = TastyError.ClasspathBuilding("finalizeMerge: brokenFullNameCount=1")
        err match
            case TastyError.ClasspathBuilding(ctx) =>
                assert(ctx.nonEmpty, "ClasspathBuilding.context must not be empty")
                assert(ctx.contains("finalizeMerge"), s"Expected 'finalizeMerge' in context; got: $ctx")
                succeed
            case other =>
                fail(s"Expected ClasspathBuilding but got: $other")
        end match
    }

    "ClasspathOrchestrator.triggerClasspathBuildingForTest produces ClasspathBuilding with non-empty context" in {
        Abort.run[TastyError](ClasspathOrchestrator.triggerClasspathBuildingForTest()).map { result =>
            result match
                case Result.Failure(TastyError.ClasspathBuilding(ctx)) =>
                    assert(ctx.nonEmpty, s"ClasspathBuilding.context must not be empty; got empty string")
                    assert(
                        ctx.contains("brokenFullNameCount"),
                        s"Expected 'brokenFullNameCount' in context; got: '$ctx'"
                    )
                    succeed
                case Result.Success(_) =>
                    fail("Expected ClasspathBuilding abort but got success")
                case Result.Failure(other) =>
                    fail(s"Expected ClasspathBuilding but got: $other")
                case Result.Panic(t) =>
                    throw t
        }
    }

end DecoderFidelity5Phase03Test

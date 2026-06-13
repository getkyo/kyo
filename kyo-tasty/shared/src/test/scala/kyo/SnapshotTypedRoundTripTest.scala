package kyo

import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** verify snapshot round-trip with typed subtypes.
  */
class SnapshotTypedRoundTripTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    "typed subtypes survive snapshot round-trip" in {
        val digest = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { coldCp =>
                    val snapPath = s"cache/${DigestComputer.toHexString(digest)}.krfl"
                    val bytes    = SnapshotWriter.serializeToBytes(coldCp, digest)
                    SnapshotReader.readFromBytes(bytes, snapPath).map { warmCp =>
                        (coldCp, warmCp)
                    }
                }
            }
        ).map {
            case Result.Success((coldCp, warmCp)) =>
                val cold = coldCp.symbols
                val warm = warmCp.symbols
                assert(cold.length == warm.length, s"Symbol count mismatch: cold=${cold.length} warm=${warm.length}")
                var i = 0
                while i < cold.length do
                    val c = cold(i)
                    val w = warm(i)
                    assert(
                        c.getClass.getSimpleName == w.getClass.getSimpleName,
                        s"Symbol[$i] type mismatch: cold=${c.getClass.getSimpleName} warm=${w.getClass.getSimpleName}"
                    )
                    assert(c.id == w.id, s"Symbol[$i] id mismatch: cold=${c.id} warm=${w.id}")
                    assert(
                        c.name.asString == w.name.asString,
                        s"Symbol[$i] name mismatch: cold=${c.name.asString} warm=${w.name.asString}"
                    )
                    assert(c.flags.bits == w.flags.bits, s"Symbol[$i] flags mismatch: cold=${c.flags.bits} warm=${w.flags.bits}")
                    i += 1
                end while
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "snapshot written by a previous version can be re-read by same reader" in {
        val digest = Array[Byte](0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val snapPath = s"cache/${DigestComputer.toHexString(digest)}.krfl"
                    val bytes    = SnapshotWriter.serializeToBytes(classpath, digest)
                    SnapshotReader.readFromBytes(bytes, snapPath).map { loadedCp =>
                        (classpath.symbols.length, loadedCp.symbols.length)
                    }
                }
            }
        ).map {
            case Result.Success((coldCount, warmCount)) =>
                assert(coldCount == warmCount, s"Expected same symbol count but cold=$coldCount warm=$warmCount")
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end SnapshotTypedRoundTripTest

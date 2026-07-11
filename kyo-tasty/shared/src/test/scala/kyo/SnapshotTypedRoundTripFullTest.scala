package kyo

import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** full snapshot round-trip on a multi-file fixture.
  *
  * Uses all available embedded fixture TASTy files to produce a larger classpath than the single-file test. Verifies that typed
  * subtype tags, ids, names, and flags all survive a write-then-read snapshot cycle.
  */
class SnapshotTypedRoundTripFullTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val multiFilePickles: Chunk[Tasty.Pickle] = Chunk(
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty)),
        Tasty.Pickle("some-object", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someObjectTasty)),
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty)),
        Tasty.Pickle("generic-box", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.genericBoxTasty)),
        Tasty.Pickle("outer", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.outerTasty)),
        Tasty.Pickle("some-case-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someCaseClassTasty)),
        Tasty.Pickle("color", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.colorTasty))
    )

    "full snapshot round-trip preserves typed subtypes on multi-file fixture" in {
        val digest = Array[Byte](0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7e)
        Abort.run[TastyError](
            Tasty.withPickles(multiFilePickles) {
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
                assert(
                    cold.length == warm.length,
                    s"Symbol count mismatch: cold=${cold.length} warm=${warm.length}"
                )
                // Exact count: 7 fixture TASTy files loaded (PlainClass, SomeObject, SomeTrait, GenericBox, Outer, SomeCaseClass, Color).
                // Each file's Pass 1 emits its own Package("") root plus a kyo.fixtures package partial (14 package partials total);
                // finalizeMerge's package dedup collapses these to the 2 canonical Package symbols, removing 12
                // duplicates (101 -> 89). The per-symbol round-trip loop below is the live guard that the deduped structure survives.
                assert(
                    cold.length == 89,
                    s"Round-trip symbol count mismatch (expected 89): got ${cold.length}"
                )
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
                    assert(
                        c.flags.bits == w.flags.bits,
                        s"Symbol[$i] flags mismatch: cold=${c.flags.bits} warm=${w.flags.bits}"
                    )
                    i += 1
                end while
                succeed
            case Result.Failure(e) => fail(s"Unexpected TastyError in round-trip test: $e")
            case Result.Panic(t)   => throw t
        }
    }

end SnapshotTypedRoundTripFullTest

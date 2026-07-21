package kyo.test.snapshot

import kyo.Base64
import kyo.Maybe
import kyo.Span
import kyo.test.snapshot.internal.SnapshotStore
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for the raw-bytes store path (`SnapshotStore.readBytes`/`writeBytes`).
  *
  * All assertions are synchronous (plain file I/O, no Sync/Async boundary); uses ScalaTest directly, mirroring
  * `SnapshotDiffTest`/`SnapshotUpdateModeTest`.
  *
  * Covers:
  *   1. Byte-fidelity round-trip, including 0x00, 0xFF, and a newline byte.
  *   2. No trailing-newline append and no base64 encoding of the stored content.
  *   3. Absent-file returns Maybe.Absent.
  */
class SnapshotStoreBytesTest extends AnyFunSuite with NonImplicitAssertions:

    private def tmpDir(): String =
        s"target/snap-bytes-test-${java.lang.System.nanoTime()}"

    test("raw-bytes round-trip is byte-identical including 0x00, 0xFF, and a newline byte") {
        val path     = s"${tmpDir()}/round-trip.bin"
        val original = Span[Byte](0x00.toByte, 0x01.toByte, 0xff.toByte, '\n'.toByte, 0x7f.toByte)

        SnapshotStore.writeBytes(path, original)
        val result = SnapshotStore.readBytes(path)

        result match
            case Maybe.Present(stored) =>
                assert(stored.size == original.size, s"Expected length ${original.size}, got ${stored.size}")
                assert(stored.is(original), s"Expected byte-identical content, got ${stored.toArrayUnsafe.toList}")
            case Maybe.Absent =>
                fail(s"Expected Maybe.Present, got Maybe.Absent for $path")
        end match
    }

    test("stored bytes are verbatim: no trailing newline appended and not base64-encoded") {
        val path    = s"${tmpDir()}/verbatim.bin"
        val content = Span[Byte](0x10.toByte, 0x20.toByte, 0x30.toByte, 0x40.toByte, 0x50.toByte)

        SnapshotStore.writeBytes(path, content)
        val result = SnapshotStore.readBytes(path)

        result match
            case Maybe.Present(stored) =>
                assert(stored.size == content.size, s"Expected no appended newline byte: length ${stored.size} != ${content.size}")
                assert(stored.is(content), s"Expected verbatim byte content, got ${stored.toArrayUnsafe.toList}")
                val encodedLength = Base64.encode(content).size
                assert(
                    stored.size != encodedLength,
                    s"Stored byte length (${stored.size}) must not equal the base64-encoded string length ($encodedLength)"
                )
            case Maybe.Absent =>
                fail(s"Expected Maybe.Present, got Maybe.Absent for $path")
        end match
    }

    test("readBytes on a missing file returns Absent") {
        val path   = s"${tmpDir()}/missing.bin"
        val result = SnapshotStore.readBytes(path)

        result match
            case Maybe.Absent     => succeed
            case Maybe.Present(_) => fail(s"Expected Maybe.Absent for a file never written: $path")
        end match
    }

end SnapshotStoreBytesTest

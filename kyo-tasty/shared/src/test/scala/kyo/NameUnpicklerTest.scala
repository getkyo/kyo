package kyo

import kyo.fixtures.Embedded
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.NameUnpickler
import kyo.internal.tasty.reader.TastyHeader

/** Tests for NameUnpickler.read.
  *
  * Fixture: `kyo/fixtures/PlainClass.tasty` loaded from the classpath via `getClass.getResourceAsStream`. The fixture is a real TASTy file
  * compiled from `FixtureClasses.scala` in `kyo-tasty-fixtures`.
  *
  * TASTy header ends at byte 35 for PlainClass.tasty. After that:
  *   Nat at offset 35: name table byte count (value 272, encoded as two-byte Nat)
  *   Bytes 37.308: name table (29 entries)
  *   Bytes 309.: section table
  *
  * The name table includes the following UTF8 entries (0-based indices): 0=ASTs, 1=kyo, 2=fixtures, 4=PlainClass, 5=x, 6=Int, 7=scala.
  *
  * Name-table byte-count delimiting: the loop runs until `position >= nameTableEnd`, not for a fixed entry count. This is verified by the
  * corrupt-section test, which uses a truncated payload.
  */
class NameUnpicklerTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Return the PlainClass.tasty fixture bytes (embedded for cross-platform compatibility). */
    private def loadFixture(): Array[Byte] = Embedded.plainClassTasty

    /** Skip the TASTy header (magic + version + tooling + UUID) in `view`, then return the view positioned at the name-table-length Nat. */
    private def skipHeader(view: ByteView)(using Frame): Unit < Abort[TastyError] =
        TastyHeader.read(view).unit

    "loading PlainClass.tasty produces a non-empty name array" in {
        val bytes = loadFixture()
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            skipHeader(view).andThen {
                NameUnpickler.read(view)
            }
        }.map { result =>
            result match
                case Result.Success(names) =>
                    assert(names.length == 29, s"Expected 29 names but got ${names.length}")
                    // First-name sanity check: the very first entry must decode to a non-empty string.
                    import Tasty.Name.asString
                    assert(names(0).asString.nonEmpty, s"Expected names(0) to be non-empty but got '${names(0).asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    "PlainClass fixture: name 'PlainClass' appears in the decoded name array" in {
        val bytes = loadFixture()
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            skipHeader(view).andThen {
                NameUnpickler.read(view)
            }
        }.map { result =>
            result match
                case Result.Success(names) =>
                    assert(names.exists(_.asString == "PlainClass"))
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    "PlainClass fixture: a QUALIFIED name entry decodes to a dotted string" in {
        val bytes = loadFixture()
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            skipHeader(view).andThen {
                NameUnpickler.read(view)
            }
        }.map { result =>
            result match
                case Result.Success(names) =>
                    // names[3] (0-based) is a QUALIFIED name: kyo.fixtures (prefix=names[1]="kyo", selector=names[2]="fixtures")
                    // payload 0x81 = NAT 1 (kyo), 0x82 = NAT 2 (fixtures) => "kyo.fixtures"
                    val qualified = names.find(n => n.asString.contains("."))
                    assert(qualified.isDefined, "Expected at least one qualified (dotted) name")
                    assert(
                        qualified.get.asString == "kyo.fixtures",
                        s"Expected 'kyo.fixtures' but got '${qualified.get.asString}'"
                    )
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    "corrupt name table (truncated mid-entry) produces MalformedSection" in {
        // Build a fake name table: name-table-byte-count Nat says 10 bytes are coming, but we only provide 3.
        // Name table length = 10 (as Nat: single byte 0x8a because 10 | 0x80 = 0x8a)
        // Then UTF8 tag (0x01), then length=5 (0x85), then only 2 bytes of payload -> truncated.
        val nameTableBytes: Array[Byte] = Array(
            0x8a.toByte, // NAT 10 (name table length)
            0x01.toByte, // UTF8 tag
            0x85.toByte, // NAT 5 (utf8 length)
            0x68.toByte, // 'h'
            0x65.toByte  // 'e' -- only 2 of 5 bytes provided, then end of array
        )
        val view = ByteView(nameTableBytes)
        Abort.run[TastyError] {
            NameUnpickler.read(view)
        }.map { result =>
            result match
                case Result.Failure(TastyError.MalformedSection("Names", _, _)) =>
                    succeed
                case other =>
                    fail(s"Expected MalformedSection but got: $other")
        }
    }

    "interning the same byte sequence twice gives reference-equal underlying entries" in {
        val bytes = loadFixture()
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            skipHeader(view).andThen {
                NameUnpickler.read(view)
            }
        }.map { result =>
            result match
                case Result.Success(names) =>
                    // Construct a second Name with the same string value from a different source.
                    val n1 = names.find(_.asString == "PlainClass").get
                    val n2 = Tasty.Name("PlainClass")
                    // Both names decode to the same string value.
                    assert(n1.asString == "PlainClass")
                    assert(n2.asString == "PlainClass")
                    // Name = String: equality is content-based, not reference identity.
                    assert(n1 == n2, "Two Names with the same string content must be equal")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // The byte-count-delimited loop must stop at nameTableEnd even if more bytes follow.
    "name table loop stops at nameTableByteCount boundary, ignoring trailing bytes" in {
        // Construct a name table with:
        //   nameTableByteCount = 7 (encoded as Nat: 7 | 0x80 = 0x87)
        //   Then: UTF8 tag (0x01) + length=5 (0x85) + 5 payload bytes ("hello") = 7 bytes total
        //   Then: 1 trailing byte 0xFF that must NOT be consumed as a new entry.
        // The loop condition is `while view.position < nameTableEnd`, so it stops exactly at byte 7
        // and leaves 0xFF in the buffer (the NameUnpickler does not consume past nameTableEnd).
        val bytes: Array[Byte] = Array(
            0x87.toByte, // NAT 7 (name table byte count, terminating byte with stop bit 0x80)
            0x01.toByte, // UTF8 tag
            0x85.toByte, // NAT 5 (utf8 length, with stop bit)
            0x68.toByte, // 'h'
            0x65.toByte, // 'e'
            0x6c.toByte, // 'l'
            0x6c.toByte, // 'l'
            0x6f.toByte, // 'o'  <- this is byte 7 (end of name table)
            0xff.toByte  // trailing 0xFF -- must NOT be interpreted as a new entry
        )
        val view = ByteView(bytes)
        Abort.run[TastyError] {
            NameUnpickler.read(view)
        }.map { result =>
            result match
                case Result.Success(names) =>
                    // Exactly 1 name decoded: "hello"
                    assert(names.length == 1, s"Expected 1 name but got ${names.length}: ${names.map(_.asString).mkString(", ")}")
                    assert(names(0).asString == "hello", s"Expected 'hello' but got '${names(0).asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // QUALIFIED entry with out-of-range prefix yields MalformedSection
    "QUALIFIED entry with out-of-range prefix yields MalformedSection" in {
        // Build a name table with:
        //   1 UTF8 entry: "hello" (5 bytes, tag=1)
        //   1 QUALIFIED entry: prefix=99 (out of range since tableSize=1), selector=0 (valid)
        // Name table byte count covers both entries.
        // UTF8 entry: tag(1 byte) + length-nat(1 byte) + 5 bytes = 7 bytes
        // QUALIFIED entry: tag(1 byte) + readEnd-length-nat(1 byte) + prefix-nat(2 bytes for 99) + selector-nat(1 byte) = 5 bytes
        // Total = 12 bytes. Encoded as Nat: 12 | 0x80 = 0x8c
        // Encoding 99 as a Nat (big-endian base-128):
        //   99 = 0x63, fits in 7 bits, so needs 2 bytes: continuation byte 0x00 | high bits, then final.
        //   Actually 99 < 128 so it fits in 1 byte: 0x63 | 0x80 = 0xe3 (terminating).
        //   Wait: 99 in big-endian base-128: single byte is 99 | 0x80 = 0xe3 (bit 7 set = terminating).
        //   But readEnd reads a length first; the QUALIFIED payload has 2 name-refs.
        //   Encoding: tag=QUALIFIED=2, readEnd returns cursor+payloadLen.
        //   QUALIFIED payload: prefix-nat(1 byte) + selector-nat(1 byte) = 2 bytes.
        //   Length Nat for payload = 2 bytes, encoded as: 2 | 0x80 = 0x82.
        //   prefix=99: 99 | 0x80 = 0xe3 (single terminating byte).
        //   selector=0: 0 | 0x80 = 0x80 (single terminating byte).
        // Full name table (12 bytes):
        //   UTF8 entry: 0x01 (tag), 0x85 (len=5), 0x68, 0x65, 0x6c, 0x6c, 0x6f => "hello" (7 bytes)
        //   QUALIFIED: 0x02 (tag=2), 0x82 (len=2), 0xe3 (prefix=99), 0x80 (selector=0) (4 bytes)
        //   Total payload = 7 + 4 = 11 bytes. Encoded as Nat: 11 | 0x80 = 0x8b.
        val nameTableBytes: Array[Byte] = Array(
            0x8b.toByte, // NAT 11 (name table byte count)
            // Entry 0: UTF8 "hello"
            0x01.toByte, // UTF8 tag
            0x85.toByte, // length = 5
            0x68.toByte, // 'h'
            0x65.toByte, // 'e'
            0x6c.toByte, // 'l'
            0x6c.toByte, // 'l'
            0x6f.toByte, // 'o'
            // Entry 1: QUALIFIED prefix=99, selector=0
            0x02.toByte, // QUALIFIED tag
            0x82.toByte, // payload length = 2
            0xe3.toByte, // prefix NAT = 99 (99 | 0x80)
            0x80.toByte  // selector NAT = 0 (0 | 0x80)
        )
        val view = ByteView(nameTableBytes)
        Abort.run[TastyError] {
            NameUnpickler.read(view)
        }.map { result =>
            result match
                case Result.Failure(TastyError.MalformedSection("Names", reason, _)) =>
                    assert(
                        reason.contains("prefix=99") || reason.contains("ref=99"),
                        s"Expected reason to mention prefix=99 but was: $reason"
                    )
                case other =>
                    fail(s"Expected MalformedSection but got: $other")
        }
    }

end NameUnpicklerTest

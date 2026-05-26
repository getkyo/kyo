package kyo

import kyo.fixtures.Embedded
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.tasty.NameUnpickler
import kyo.internal.reflect.tasty.TastyHeader

/** Tests for NameUnpickler.read.
  *
  * Fixture: `kyo/fixtures/PlainClass.tasty` loaded from the classpath via `getClass.getResourceAsStream`. The fixture is a real TASTy file
  * compiled from `FixtureClasses.scala` in `kyo-reflect-fixtures`.
  *
  * TASTy header ends at byte 35 for PlainClass.tasty. After that:
  *   - Nat at offset 35: name table byte count (value 272, encoded as two-byte Nat)
  *   - Bytes 37..308: name table (29 entries)
  *   - Bytes 309..: section table
  *
  * The name table includes the following UTF8 entries (0-based indices): 0=ASTs, 1=kyo, 2=fixtures, 4=PlainClass, 5=x, 6=Int, 7=scala, ...
  *
  * Name-table byte-count delimiting: the loop runs until `position >= nameTableEnd`, not for a fixed entry count. This is verified by the
  * corrupt-section test, which uses a truncated payload.
  */
class NameUnpicklerTest extends Test:

    /** Return the PlainClass.tasty fixture bytes (embedded for cross-platform compatibility). */
    private def loadFixture(): Array[Byte] = Embedded.plainClassTasty

    /** Skip the TASTy header (magic + version + tooling + UUID) in `view`, then return the view positioned at the name-table-length Nat. */
    private def skipHeader(view: ByteView)(using Frame): Unit < Abort[ReflectError] =
        TastyHeader.read(view).unit

    // Test 7: loading the fixture TASTy file: the Names section is present and non-empty.
    "loading PlainClass.tasty produces a non-empty name array" in run {
        val bytes    = loadFixture()
        val view     = ByteView(bytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        Abort.run[ReflectError] {
            skipHeader(view).andThen {
                NameUnpickler.read(view, interner)
            }
        }.map { result =>
            result match
                case Result.Success(names) =>
                    assert(names.nonEmpty)
                    assert(names.length == 29)
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 8: the name "PlainClass" is in the decoded name array (fixture top-level class name).
    "PlainClass fixture: name 'PlainClass' appears in the decoded name array" in run {
        val bytes    = loadFixture()
        val view     = ByteView(bytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        Abort.run[ReflectError] {
            skipHeader(view).andThen {
                NameUnpickler.read(view, interner)
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

    // Test 9: a QUALIFIED name entry (two simple-name parts joined by ".") decodes to a dotted string.
    "PlainClass fixture: a QUALIFIED name entry decodes to a dotted string" in run {
        val bytes    = loadFixture()
        val view     = ByteView(bytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        Abort.run[ReflectError] {
            skipHeader(view).andThen {
                NameUnpickler.read(view, interner)
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

    // Test 10: a corrupt section (truncated mid-name) produces Abort.fail(ReflectError.MalformedSection("Names", ...)).
    "corrupt name table (truncated mid-entry) produces MalformedSection" in run {
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
        val view     = ByteView(nameTableBytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        Abort.run[ReflectError] {
            NameUnpickler.read(view, interner)
        }.map { result =>
            result match
                case Result.Failure(ReflectError.MalformedSection("Names", _)) =>
                    succeed
                case other =>
                    fail(s"Expected MalformedSection but got: $other")
        }
    }

    // Test 11: all decoded names are interned: same bytes interned twice give reference-equal underlying entries.
    "interning the same byte sequence twice gives reference-equal underlying entries" in run {
        val bytes    = loadFixture()
        val view     = ByteView(bytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        Abort.run[ReflectError] {
            skipHeader(view).andThen {
                NameUnpickler.read(view, interner)
            }
        }.map { result =>
            result match
                case Result.Success(names) =>
                    // Intern "PlainClass" a second time independently, using the same interner.
                    val n1 = names.find(_.asString == "PlainClass").get
                    val n2 = Reflect.Name.wrap(
                        interner.intern("PlainClass".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, "PlainClass".length)
                    )
                    // Both names decode to the same string value.
                    assert(n1.asString == "PlainClass")
                    assert(n2.asString == "PlainClass")
                    // The cached string object inside n1 is the same reference (OnceCell caches).
                    assert(n1.asString eq n1.asString)
                    // Core interning invariant: n1 and n2 are the same underlying Entry (reference identity).
                    // This verifies that the interner, not just the OnceCell, provides deduplication.
                    // Name is an opaque type wrapping Interner.Entry; == uses AnyRef.equals which is reference equality.
                    assert(n1 == n2, "Interning the same bytes via the same Interner must return the same Entry reference")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 12 (P2-W5 STEERING directive): trailing padding bytes must NOT be interpreted as an extra entry.
    // The byte-count-delimited loop must stop at nameTableEnd even if more bytes follow.
    "name table loop stops at nameTableByteCount boundary, ignoring trailing bytes" in run {
        // Construct a name table with:
        //   nameTableByteCount = 7 (encoded as Nat: 7 | 0x80 = 0x87)
        //   Then: UTF8 tag (0x01) + length=5 (0x85) + 5 payload bytes ("hello") = 7 bytes total
        //   Then: 1 trailing byte 0xFF that must NOT be consumed as a new entry.
        //
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
        val view     = ByteView(bytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        Abort.run[ReflectError] {
            NameUnpickler.read(view, interner)
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

end NameUnpicklerTest

package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.tasty.snapshot.SnapshotFormat

/** Tests for SnapshotFormat version and section-name constants.
  *
  * INV-023: minorVersion == 3 after the TPARAMS_ add-only bump. INV-003: sectionNames is add-only (TPARAMS_ present, existing names
  * preserved).
  */
class SnapshotFormatTest extends Test:

    // Test 1 (INV-023): minorVersion reflects the TPARAMS_ add-only bump.
    "SnapshotFormat.minorVersion is 3 after TPARAMS_ section addition" in run {
        assert(SnapshotFormat.minorVersion == 3)
        succeed
    }

    // Test 2 (INV-023, INV-003 add-only): TPARAMS_ is present in sectionNames.
    "SnapshotFormat.sectionNames contains TPARAMS_" in run {
        assert(SnapshotFormat.sectionNames.contains("TPARAMS_"))
        succeed
    }

    // Test 3 (INV-003 add-only): all pre-existing section names are still present.
    "SnapshotFormat.sectionNames retains all pre-existing section names" in run {
        val required = List("NAMES", "SYMBOLS", "TYPES", "TYPESEXT", "PARENTS", "MEMBERS", "FILES", "BODYBYTE", "ERRORS")
        for name <- required do
            assert(SnapshotFormat.sectionNames.contains(name), s"Missing pre-existing section: $name")
        succeed
    }

    // Test 4 (INV-023): sectionTPARAMS constant equals the array entry.
    "SnapshotFormat.sectionTPARAMS constant matches the TPARAMS_ array entry" in run {
        assert(SnapshotFormat.sectionTPARAMS == "TPARAMS_")
        assert(SnapshotFormat.sectionNames.contains(SnapshotFormat.sectionTPARAMS))
        succeed
    }

    // Test 5 (T2): magic bytes decoded as US-ASCII equal "KRFL".
    //
    // Given: SnapshotFormat.magic (4-byte Array).
    // When: new String(magic, StandardCharsets.US_ASCII).
    // Then: equals "KRFL".
    // Pins: T2.
    "SnapshotFormat.magic bytes decoded as US-ASCII equal KRFL" in run {
        val decoded = new String(SnapshotFormat.magic, StandardCharsets.US_ASCII)
        assert(decoded == "KRFL", s"Expected magic to decode to KRFL but got: $decoded")
        succeed
    }

end SnapshotFormatTest

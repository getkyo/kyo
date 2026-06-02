package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.tasty.snapshot.SnapshotFormat

/** Tests for SnapshotFormat version and section-name constants.
  *
  * INV-023: minorVersion == 7 after the Phase 5.01b ERRORS typed-format bump (F-W2-5).
  * INV-023-prev: Phase 2.13 set minorVersion to 6 for FQNMAP__ section addition.
  * INV-003: sectionNames is add-only (TPARAMS_ present, existing names preserved).
  */
class SnapshotFormatTest extends Test:

    // Test 1 (INV-023, updated Phase 5.02): minorVersion reflects the Phase 5.02 SUBCIDX_/COMPIDX_ bump.
    // Phase 5.01b set minorVersion to 7 for ERRORS typed-format; Phase 5.02 sets it to 8 for index sections.
    "SnapshotFormat.minorVersion is 8 after Phase 5.02 SUBCIDX_/COMPIDX_ section addition" in run {
        assert(SnapshotFormat.minorVersion == 8)
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

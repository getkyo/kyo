package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.tasty.snapshot.SnapshotFormat

/** Tests for SnapshotFormat version and section-name constants.
  *
  * minorVersion == 7 after theb ERRORS typed-format bump.
  * prev: set minorVersion to 6 for FQNMAP__ section addition.
  * sectionNames is add-only (TPARAMS_ present, existing names preserved).
  */
class SnapshotFormatTest extends kyo.test.Test[Any]:

    // Test 1 (, updated): minorVersion reflects the current bump.
    // b set minorVersion to 7 for ERRORS typed-format; set it to 8 for index sections;
    // sets it to 9 for ClasspathClosed/ClasspathBuilding context field.
    // (prior) set it to 10 for ERRORS string-tag format (stable productPrefix wire encoding, item 14).
    // set it to 11 for UnhandledSubtypingCase / UnresolvedReference / UnknownType / MissingDeclaredType.
    // (this) sets it to 12 for PLISTS__ section (handoff-fixes campaign: Symbol.Method.paramListIds).
    "SnapshotFormat.minorVersion is 12 (added PLISTS__ section for Symbol.Method.paramListIds)" in {
        assert(SnapshotFormat.minorVersion == 12)
        succeed
    }

    // Test 2 (, add-only): TPARAMS_ is present in sectionNames.
    "SnapshotFormat.sectionNames contains TPARAMS_" in {
        assert(SnapshotFormat.sectionNames.contains("TPARAMS_"))
        succeed
    }

    // Test 3 (add-only): all pre-existing section names are still present.
    "SnapshotFormat.sectionNames retains all pre-existing section names" in {
        val required = List("NAMES", "SYMBOLS", "TYPES", "TYPESEXT", "PARENTS", "MEMBERS", "FILES", "BODYBYTE", "ERRORS")
        for name <- required do
            assert(SnapshotFormat.sectionNames.contains(name), s"Missing pre-existing section: $name")
        succeed
    }

    // Test 4: sectionTPARAMS constant equals the array entry.
    "SnapshotFormat.sectionTPARAMS constant matches the TPARAMS_ array entry" in {
        assert(SnapshotFormat.sectionTPARAMS == "TPARAMS_")
        assert(SnapshotFormat.sectionNames.contains(SnapshotFormat.sectionTPARAMS))
        succeed
    }

    // Test 5 (T2): magic bytes decoded as US-ASCII equal "KRFL".
    // Given: SnapshotFormat.magic (4-byte Array).
    // When: new String(magic, StandardCharsets.US_ASCII).
    // Then: equals "KRFL".
    "SnapshotFormat.magic bytes decoded as US-ASCII equal KRFL" in {
        val decoded = new String(SnapshotFormat.magic, StandardCharsets.US_ASCII)
        assert(decoded == "KRFL", s"Expected magic to decode to KRFL but got: $decoded")
        succeed
    }

end SnapshotFormatTest

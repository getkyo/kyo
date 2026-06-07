package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.tasty.snapshot.SnapshotFormat

/** Tests for SnapshotFormat version and section-name constants.
  *
  * Verifies that minorVersion is the expected value, sectionNames is add-only, and magic decodes correctly.
  */
class SnapshotFormatTest extends kyo.test.Test[Any]:

    "SnapshotFormat.minorVersion is 12 (added PLISTS__ section for Symbol.Method.paramListIds)" in {
        assert(SnapshotFormat.minorVersion == 12)
        succeed
    }

    "SnapshotFormat.sectionNames contains TPARAMS_" in {
        assert(SnapshotFormat.sectionNames.contains("TPARAMS_"))
        succeed
    }

    "SnapshotFormat.sectionNames retains all pre-existing section names" in {
        val required = List("NAMES", "SYMBOLS", "TYPES", "TYPESEXT", "PARENTS", "MEMBERS", "FILES", "BODYBYTE", "ERRORS")
        for name <- required do
            assert(SnapshotFormat.sectionNames.contains(name), s"Missing pre-existing section: $name")
        succeed
    }

    "SnapshotFormat.sectionTPARAMS constant matches the TPARAMS_ array entry" in {
        assert(SnapshotFormat.sectionTPARAMS == "TPARAMS_")
        assert(SnapshotFormat.sectionNames.contains(SnapshotFormat.sectionTPARAMS))
        succeed
    }

    "SnapshotFormat.magic bytes decoded as US-ASCII equal KRFL" in {
        val decoded = new String(SnapshotFormat.magic, StandardCharsets.US_ASCII)
        assert(decoded == "KRFL", s"Expected magic to decode to KRFL but got: $decoded")
        succeed
    }

end SnapshotFormatTest

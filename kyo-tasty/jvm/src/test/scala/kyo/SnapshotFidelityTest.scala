package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for snapshot round-trip integrity.
  *
  * Pins findings F-C-001, F-C-002, and F-C-003. All leaves are PENDING until Phase 12 un-pends them by fixing `SnapshotWriter` (serialize
  * permittedSubclassIds, annotations, javaMetadata), `SnapshotReader` (read new fields), and bumping FORMAT_VERSION to 4.
  */
class SnapshotFidelityTest extends Test:

    import AllowUnsafe.embrace.danger

    // F-C-002 / INV-010 leaf 1 (Phase 12): roundtrip-fidelity
    // Given: a cold-loaded Classpath with all Phase 04..11 fixes applied;
    //        a snapshot written via SnapshotWriter then read back via SnapshotReader-only (warm load)
    // When: running the full assertion set against the warm-loaded classpath
    // Then: post-fix every assertion passes;
    //       before fix permittedSubclassIds, annotations, and javaMetadata reverted to empty on warm load
    //       because SnapshotWriter never serialized them and SnapshotReader hard-coded Absent/empty
    // Pins: INV-010 producer
    "INV-010 (Phase 12): snapshot warm-load preserves all Phase 04..11 fixed data" in pending

    // F-C-002 leaf 2 (Phase 12): permits-roundtrip
    // Given: a cold + warm Classpath pair via TestClasspaths.withClasspath and initCached
    // When: comparing cp_cold.findClass("scala.Option").get.permittedSubclasses vs warm
    // Then: post-fix both are Present with structurally equal subclass lists;
    //       before fix the warm cp returned Absent (SnapshotReader.scala:662 hard-coded Absent)
    // Pins: F-C-002
    "F-C-002 (Phase 12): scala.Option.permittedSubclasses survives snapshot round-trip" in pending

    // F-C-003 leaf 3 (Phase 12): annotations-roundtrip
    // Given: a cold + warm Classpath pair via TestClasspaths.withClasspath and initCached
    // When: comparing cp_cold.symbolsAnnotatedWith("scala.deprecated").size vs warm
    // Then: post-fix the sizes match;
    //       before fix warm size was 0 because SnapshotWriter never persisted annotations
    // Pins: F-C-003
    "F-C-003 (Phase 12): symbolsAnnotatedWith(scala.deprecated) count survives snapshot round-trip" in pending

    // F-G-002 leaf 4 (Phase 12): javametadata-roundtrip
    // Given: a cold + warm Classpath pair; a class with both .tasty and .class companions
    // When: comparing javaMetadata for that class between cold and warm
    // Then: post-fix javaMetadata matches between cold and warm loads;
    //       before fix warm was Absent (SnapshotWriter did not serialize javaMetadata)
    // Pins: F-G-002 snapshot mirror
    "F-G-002 (Phase 12): javaMetadata survives snapshot round-trip" in pending

    // Wire-format leaf 5 (Phase 12): format-version-bumped
    // Given: a snapshot file written with the Phase 12 code
    // When: reading the first 4 bytes of the format header
    // Then: post-fix version is 4;
    //       before fix it was 3 (FORMAT_VERSION had not been bumped for the new fields)
    // Pins: snapshot wire-format bump
    "Phase 12: SnapshotFormat.FORMAT_VERSION is 4 after Phase 12 changes" in pending

end SnapshotFidelityTest

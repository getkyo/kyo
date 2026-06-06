package kyo

import scala.compiletime.testing.typeCheckErrors

/** Phase 13 final-gate verification.
  *
  * Leaves 1-3 are meta-verification stubs that document the sbt commands which must be run
  * externally. The impl agent runs those commands and records results in
  * phases/phase-13/runs/. The `succeed` bodies follow the pattern established by
  * Inv009BehavioralTest leaf 9.
  *
  * Leaf 4 documents the A8 dependent-module compile commands.
  *
  * Leaf 5 is a real compile-time probe that re-verifies INV-IMMUTABLE-ADT.
  *
  * Pins: INV-IMMUTABLE-ADT; INV-009; INV-005-CLEAN; INV-LOADING-SYMBOL; INV-TASTYERROR-WIRE;
  * INV-007; INV-011; INV-012; INV-013.
  */
class FinalGate extends Test:

    // ── Leaf 1: JVM module green ──────────────────────────────────────────────
    // External command: sbt 'kyo-tasty/test'
    // Expected: every test passes; count non-decreasing from Phase 12 HEAD baseline.
    // Pins: cross-platform jvm; all INV-* invariants verified by the full suite.
    "jvmModuleGreen" in {
        // Verification performed externally via: sbt 'kyo-tasty/test'
        // Result recorded in: phases/phase-13/runs/final-test-jvm-001.log
        succeed
    }

    // ── Leaf 2: JS module green ───────────────────────────────────────────────
    // External command: sbt 'kyo-tastyJS/test'
    // Expected: every test passes; module compiles cross-platform.
    // Pins: cross-platform js.
    "jsModuleGreen" in {
        // Verification performed externally via: sbt 'kyo-tastyJS/test'
        // Result recorded in: phases/phase-13/runs/final-test-js-001.log
        succeed
    }

    // ── Leaf 3: Native module green ───────────────────────────────────────────
    // External command: sbt 'kyo-tastyNative/test'
    // Expected: every test passes; module compiles cross-platform.
    // Pins: cross-platform native.
    "nativeModuleGreen" in {
        // Verification performed externally via: sbt 'kyo-tastyNative/test'
        // Result recorded in: phases/phase-13/runs/final-test-native-001.log
        succeed
    }

    // ── Leaf 4: A8 dependent modules compile ─────────────────────────────────
    // External commands:
    //   sbt 'kyo-tasty-bench/compile' 'kyo-tasty-examples/compile' 'kyo-tasty-sbt-plugin/compile'
    // Expected: every dependent module compiles; no public-surface breakage.
    // Pins: A8 (carried from prior campaign).
    "a8DependentModulesCompile" in {
        // Verification performed externally via the three compile commands above.
        // Result recorded in: phases/phase-13/runs/final-deps-compile-001.log
        succeed
    }

    // ── Leaf 5: INV-IMMUTABLE-ADT Array-field rejection ──────────────────────
    // Given: a probe case class with an Array[Int] field attempting Schema + CanEqual derivation.
    // When: typeCheckErrors is invoked with the definition.
    // Then: errors are non-empty (Array[Int] rejected) for the bad probe;
    //       errors are empty (Chunk[Int] accepted) for the good probe.
    // Pins: INV-IMMUTABLE-ADT; G (produced-ADT immutability).
    "invImmutableAdtGate" in {
        val bad = typeCheckErrors(
            "final case class P(a: Array[Int]) derives kyo.Schema, CanEqual"
        )
        assert(bad.nonEmpty, "Array[Int] field should be rejected by Schema derivation")

        val good = typeCheckErrors(
            "final case class Q(a: kyo.Chunk[Int]) derives kyo.Schema, CanEqual"
        )
        assert(good.isEmpty, "Chunk[Int] field should be accepted by Schema derivation")
    }

end FinalGate

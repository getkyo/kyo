package kyo

import kyo.internal.tasty.query.ClasspathRef

/** Tests for Phase 02c: ClasspathRef accessors carry (using AllowUnsafe) (INV-001).
  *
  * Verifies that ClasspathRef.get() and ClasspathRef.isAssigned have been migrated from inner import-danger to (using AllowUnsafe), and
  * that the accessors behave correctly on a ClasspathRef that has been assigned.
  */
class ClasspathRefTest extends Test:

    // flow-allow: §839 case 3 — test helper boundary; ClasspathRef factory + assign are unsafe-tier
    import AllowUnsafe.embrace.danger

    // Test 1 (INV-001): ClasspathRef.get() returns the assigned Classpath.
    // Given: a ClasspathRef with a Classpath assigned to it; AllowUnsafe in scope.
    // When: ref.get() is called.
    // Then: returns the same Classpath instance that was passed to assign.
    // Pins: INV-001 (ClasspathRef case).
    "ClasspathRef.get returns the assigned Classpath" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            val ref = ClasspathRef.init()
            ref.assign(cp)
            val got = ref.get()
            assert(
                got eq cp,
                "Expected ref.get() to return the assigned Classpath"
            )
    }

    // Test 2 (INV-001): ClasspathRef.isAssigned reflects the assigned/unassigned state.
    // Given: a fresh ClasspathRef; AllowUnsafe in scope.
    // When: isAssigned is called before assign; then assign is called; then isAssigned again.
    // Then: first call returns false; second call returns true.
    // Pins: INV-001.
    "ClasspathRef.isAssigned returns false before assign and true after" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            val ref    = ClasspathRef.init()
            val before = ref.isAssigned
            ref.assign(cp)
            val after = ref.isAssigned
            assert(!before, "Expected isAssigned to be false before assign")
            assert(after, "Expected isAssigned to be true after assign")
    }

    // Test 3 (T2): unassigned ClasspathRef.get() throws IllegalStateException.
    // Given: a fresh ClasspathRef with no assignment; AllowUnsafe in scope.
    // When: ref.get() is called.
    // Then: IllegalStateException is thrown with a message containing "not yet set".
    // Pins: T2.
    "ClasspathRef.get on unassigned ref throws IllegalStateException" in run {
        Sync.defer {
            val ref = ClasspathRef.init()
            val ex  = intercept[IllegalStateException](ref.get())
            assert(ex.getMessage.contains("not yet set"), s"Unexpected message: ${ex.getMessage}")
        }
    }

    // Test 4 (T2): second assign on an already-assigned ClasspathRef throws IllegalStateException.
    // Given: a ClasspathRef assigned once with cp1; AllowUnsafe in scope.
    // When: ref.assign(cp2) is called a second time.
    // Then: IllegalStateException is thrown with a message containing "already set".
    // Pins: T2.
    "ClasspathRef.assign a second time throws IllegalStateException" in run {
        Tasty.Classpath.fromPickles(Seq.empty).flatMap: cp1 =>
            Tasty.Classpath.fromPickles(Seq.empty).map: cp2 =>
                val ref = ClasspathRef.init()
                ref.assign(cp1)
                val ex = intercept[IllegalStateException](ref.assign(cp2))
                assert(ex.getMessage.contains("already set"), s"Unexpected message: ${ex.getMessage}")
    }

end ClasspathRefTest

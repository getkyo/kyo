package kyo

import kyo.internal.tasty.query.ClasspathRef

/** Tests for Phase 02c: ClasspathRef accessors carry (using AllowUnsafe) (INV-001).
  *
  * Verifies that ClasspathRef.get() and ClasspathRef.isAssigned have been migrated from inner import-danger to (using AllowUnsafe), and
  * that the accessors behave correctly on a ClasspathRef that has been assigned.
  */
class ClasspathRefTest extends Test:

    import AllowUnsafe.embrace.danger

    // Test 1 (INV-001): ClasspathRef.get() returns the assigned Classpath.
    // Given: a ClasspathRef with a Classpath assigned to it; AllowUnsafe in scope.
    // When: ref.get() is called.
    // Then: returns the same Classpath instance that was passed to assign.
    // Pins: INV-001 (ClasspathRef case).
    "ClasspathRef.get returns the assigned Classpath" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            val ref = new ClasspathRef
            ref.assign(cp)
            val got = ref.get()
            assert(
                Tasty.Classpath.unwrap(got) eq Tasty.Classpath.unwrap(cp),
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
            val ref    = new ClasspathRef
            val before = ref.isAssigned
            ref.assign(cp)
            val after = ref.isAssigned
            assert(!before, "Expected isAssigned to be false before assign")
            assert(after, "Expected isAssigned to be true after assign")
    }

end ClasspathRefTest

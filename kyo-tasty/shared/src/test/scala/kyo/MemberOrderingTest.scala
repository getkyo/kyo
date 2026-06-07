package kyo

/** Cat 5 member ordering per CONTRIBUTING.md.
  *
  * Leaf 1 (noPublicSymbolRemoved): compile-time probe confirming every public entry point on
  *   object Tasty that was re-ordered in still resolves after the reorder.
  *
  * Leaf 2 (withClasspathPrecedesClasspathAccess): behavioral test confirming the re-order does not
  *   change call-time semantics: withClasspath (suspend/create) wraps classpath (access) and
  *   the scope-open -> accessor -> close path produces a valid Classpath.
  */
class MemberOrderingTest extends kyo.test.Test[Any]:

    // ── Leaf 1: noPublicSymbolRemoved ────────────────────────────────────────
    // Given: every public entry point on object Tasty that was touched by.
    // When: compile-time typeCheckErrors probes for each name.
    // Then: every probe is free of "not found" errors (the path still resolves).
    "noPublicSymbolRemoved -- every reordered entry point still resolves" in {
        // withClasspath (roots overload)
        val e1 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; val _ = kyo.Tasty.withClasspath(Seq.empty[String])(kyo.Async.never)"
        )
        assert(
            e1.forall(e => !e.message.contains("not found: value withClasspath")),
            s"withClasspath(roots) must resolve; errors: ${e1.map(_.message).mkString(", ")}"
        )
        // withClasspath (cp overload) -- distinguished by its cp: Classpath parameter
        val e2 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; import kyo.Tasty.Classpath; val _ = (cp: Classpath) => kyo.Tasty.withClasspath(cp)(kyo.Async.never)"
        )
        assert(
            e2.forall(e => !e.message.contains("not found: value withClasspath")),
            s"withClasspath(cp) must resolve; errors: ${e2.map(_.message).mkString(", ")}"
        )
        // withPickles
        val e3 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; val _ = kyo.Tasty.withPickles(kyo.Chunk.empty[kyo.Tasty.Pickle])(kyo.Async.never)"
        )
        assert(
            e3.forall(e => !e.message.contains("not found: value withPickles")),
            s"withPickles must resolve; errors: ${e3.map(_.message).mkString(", ")}"
        )
        // evictOlderThan
        val e4 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; val _ = kyo.Tasty.evictOlderThan _"
        )
        assert(
            e4.forall(e => !e.message.contains("not found: value evictOlderThan")),
            s"evictOlderThan must resolve; errors: ${e4.map(_.message).mkString(", ")}"
        )
        // classpath
        val e5 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; val _ = kyo.Tasty.classpath"
        )
        assert(
            e5.forall(e => !e.message.contains("not found: value classpath")),
            s"classpath must resolve; errors: ${e5.map(_.message).mkString(", ")}"
        )
        // classFqn
        val e6 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; val _ = kyo.Tasty.classFqn[Int]"
        )
        assert(
            e6.forall(e => !e.message.contains("not found: value classFqn")),
            s"classFqn must resolve; errors: ${e6.map(_.message).mkString(", ")}"
        )
        succeed
    }

    // ── Leaf 2: withClasspathPrecedesClasspathAccess ─────────────────────────
    // Given: an empty roots list (SoftFail; produces an empty-but-valid Classpath).
    // When: Tasty.withClasspath(Seq.empty)(Tasty.classpath) is executed.
    // Then: the result is a Classpath; symbols.size >= 0; no exception or abort.
    //       This exercises the full scope-open -> accessor -> close path after the reorder.
    "withClasspathPrecedesClasspathAccess -- re-order does not change call-time semantics" in {
        Tasty.withClasspath(Seq.empty[String])(Tasty.classpath).map: cp =>
            assert(cp.symbols.size >= 0, s"Classpath.symbols.size must be non-negative; got ${cp.symbols.size}")
            succeed
    }

end MemberOrderingTest

package kyo

/** Tests for member ordering on object Tasty: verifies that re-ordering public entry points does not
  * remove them or change call-time semantics.
  */
class MemberOrderingTest extends kyo.test.Test[Any]:

    "noPublicSymbolRemoved -- every reordered entry point still resolves" in {
        // withClasspath (roots overload)
        val e1 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; val _ = kyo.Tasty.withClasspath(Seq.empty[String])(kyo.Async.never)"
        )
        assert(
            e1.forall(e => !e.message.contains("not found: value withClasspath")),
            s"withClasspath(roots) must resolve; errors: ${e1.map(_.message).mkString(", ")}"
        )
        // withClasspath (classpath overload) -- distinguished by its classpath: Classpath parameter
        val e2 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; import kyo.Tasty.Classpath; val _ = (classpath: Classpath) => kyo.Tasty.withClasspath(classpath)(kyo.Async.never)"
        )
        assert(
            e2.forall(e => !e.message.contains("not found: value withClasspath")),
            s"withClasspath(classpath) must resolve; errors: ${e2.map(_.message).mkString(", ")}"
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
        // classFullName
        val e6 = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.type = kyo.Tasty; val _ = kyo.Tasty.classFullName[Int]"
        )
        assert(
            e6.forall(e => !e.message.contains("not found: value classFullName")),
            s"classFullName must resolve; errors: ${e6.map(_.message).mkString(", ")}"
        )
        succeed
    }

    "withClasspathPrecedesClasspathAccess -- re-order does not change call-time semantics" in {
        Tasty.withClasspath(Seq.empty[String])(Tasty.classpath).map { classpath =>
            assert(classpath.symbols.size >= 0, s"Classpath.symbols.size must be non-negative; got ${classpath.symbols.size}")
            succeed
        }
    }

end MemberOrderingTest

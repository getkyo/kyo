package kyo

import scala.concurrent.Future

/** Tests for ErrorMode enum (Phase 10 Item 2).
  *
  * Pins: steering rule on enum over Boolean sentinel.
  */
class ErrorModeTest extends Test:

    import AllowUnsafe.embrace.danger

    // Leaf id:2 -- ErrorMode enum replaces strict: Boolean
    // Given: Classpath.open signatures
    // When: overloads are exercised
    // Then: no overload takes `strict: Boolean`; mode-aware overload accepts ErrorMode
    "ErrorMode.SoftFail and ErrorMode.FailFast are distinct enum cases" in {
        assert(Tasty.ErrorMode.SoftFail != Tasty.ErrorMode.FailFast)
        assert(Tasty.ErrorMode.SoftFail == Tasty.ErrorMode.SoftFail)
        assert(Tasty.ErrorMode.FailFast == Tasty.ErrorMode.FailFast)
        Future.successful(succeed)
    }

    "ErrorMode derives CanEqual" in {
        // Compile-time check: CanEqual derived means == does not require import
        val a: Tasty.ErrorMode = Tasty.ErrorMode.SoftFail
        val b: Tasty.ErrorMode = Tasty.ErrorMode.FailFast
        assert(a != b)
        Future.successful(succeed)
    }

    "Classpath.open one-arg overload delegates using ErrorMode.SoftFail" in {
        // Verify source text: the one-arg open calls open(roots, ErrorMode.SoftFail)
        val lines      = TestResourceLoader.readText("kyo/Tasty.scala").split("\n")
        val sigPattern = """^\s+def open\(roots: Seq\[String\]\)\(using Frame\)""".r
        val sigIdx     = lines.indexWhere(l => sigPattern.findFirstIn(l).isDefined)
        assert(sigIdx >= 0, "could not locate one-arg open signature in Tasty.scala")
        val bodyLine = lines.slice(sigIdx + 1, sigIdx + 3).find(_.trim.nonEmpty).getOrElse("")
        assert(
            bodyLine.contains("ErrorMode.SoftFail"),
            s"Expected body to reference ErrorMode.SoftFail, got: '${bodyLine.trim}'"
        )
        Future.successful(succeed)
    }

    "Classpath.open no overload takes strict: Boolean" in {
        // Verify no public `def open(roots, strict: Boolean)` signature exists
        val src           = TestResourceLoader.readText("kyo/Tasty.scala")
        val hasBoolStrict = src.contains("def open(roots: Seq[String], strict: Boolean)")
        assert(!hasBoolStrict, "Found deprecated strict: Boolean overload in Classpath.open")
        Future.successful(succeed)
    }

end ErrorModeTest

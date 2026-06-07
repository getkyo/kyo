package kyo

import kyo.internal.TestClasspaths

/** Verifies that @scala.annotation.tailrec is resolved on the live standard classpath. The @tailrec tycon is encoded as TYPEREFsymbol
  * (addr-based external class reference), which the unresolvedFqnByNegId fallback does not cover; resolution requires the real scala-library
  * jar on the classpath so the addr resolves to a real SymbolId.
  */
class TailrecAnnotationTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "cp.symbolsAnnotatedWith(scala.annotation.tailrec).size >= 1" in {
        TestClasspaths.withClasspath():
            Tasty.classpath.flatMap: classpath =>
                classpath.symbolsAnnotatedWith("scala.annotation.tailrec").map: annotated =>
                    assert(
                        annotated.size >= 1,
                        s"Expected >= 1 symbol annotated with scala.annotation.tailrec but found ${annotated.size}"
                    )
                    succeed
    }

end TailrecAnnotationTest

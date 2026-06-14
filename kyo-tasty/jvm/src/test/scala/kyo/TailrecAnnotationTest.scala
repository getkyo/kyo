package kyo

import kyo.internal.TestClasspaths

/** Verifies that @scala.annotation.tailrec is resolved on the live standard classpath. The @tailrec tycon is encoded as TYPEREFsymbol
  * (address-based external class reference), which the unresolvedFullNameByNegId fallback does not cover; resolution requires the real scala-library
  * jar on the classpath so the address resolves to a real SymbolId.
  */
class TailrecAnnotationTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "classpath.symbolsAnnotatedWith(scala.annotation.tailrec).size >= 1" in {
        TestClasspaths.withClasspath() {
            Tasty.classpath.map { classpath =>
                val annotated = classpath.symbolsAnnotatedWith("scala.annotation.tailrec")
                assert(
                    annotated.size >= 1,
                    s"Expected >= 1 symbol annotated with scala.annotation.tailrec but found ${annotated.size}"
                )
                succeed
            }
        }
    }

end TailrecAnnotationTest

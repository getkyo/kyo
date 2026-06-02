package kyo.fixtures

import scala.annotation.tailrec

/** Fixture classes for cross-platform annotation fidelity testing.
  *
  * Provides symbols with @deprecated, @tailrec, and custom annotations so that AnnotationFidelityTest can run on JS/Native without the real
  * stdlib classpath. Added as part of Phase 2.12 corrective to ungate embedded-fixture-compatible leaves.
  */

@deprecated("Use AnnotatedFixtureNew instead", "1.0")
class AnnotatedFixtureDeprecated

@deprecated("Use other method instead", "1.0")
def deprecatedTopLevel(): Int = 0

class AnnotatedFixtureMethods:

    @tailrec
    final def countDown(n: Int): Int =
        if n <= 0 then 0
        else countDown(n - 1)

    @scala.annotation.unused
    def annotatedWithUnused(x: Int): Int = x + 1

end AnnotatedFixtureMethods

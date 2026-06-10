package kyo.test.runner.internal

import kyo.test.internal.TestBase

/** JVM-specific reflective instantiation for the self-contained runner.
  *
  * Targets `kyo.test.internal.TestBase`. Uses standard Java reflection (`getDeclaredConstructor().newInstance()`); the suite must have a
  * public no-arg constructor, which Scala top-level classes satisfy.
  */
private[runner] object InstantiatePlatform:

    def newInstance(suite: Class[? <: TestBase[?]]): TestBase[?] =
        suite.getDeclaredConstructor().newInstance()

end InstantiatePlatform

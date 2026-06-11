package kyo.test.runner.internal

import kyo.test.internal.TestBase

/** Reflectively instantiates a `kyo.test.internal.TestBase` subclass.
  *
  * The `TestContext` must be installed on its thread-local via `setForInstantiation` BEFORE calling `newInstance`, because the `TestBase`
  * constructor reads it synchronously on the same thread. The actual instantiation is delegated to the platform-specific
  * [[InstantiatePlatform]] (JVM: `getDeclaredConstructor`; JS/Native: `Reflect.lookupInstantiatableClass`).
  */
private[runner] object Instantiate:

    /** Instantiate the suite class. The thread-confined contexts must already be set. Constructor exceptions propagate to the caller. */
    def newInstance(suite: Class[? <: TestBase[?]]): TestBase[?] =
        InstantiatePlatform.newInstance(suite)

end Instantiate

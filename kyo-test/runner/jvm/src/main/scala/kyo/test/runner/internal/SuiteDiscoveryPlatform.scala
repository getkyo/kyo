package kyo.test.runner.internal

import kyo.Chunk
import kyo.test.internal.TestBase

/** JVM implementation of the platform-specific suite discovery bridge.
  *
  * Delegates to [[SuiteDiscovery.discover()]], which reads `META-INF/services/kyo.test.Test` from the classpath.
  *
  * This file shadows the empty-stub definition in `shared/` for the JVM platform.
  */
private[runner] object SuiteDiscoveryPlatform:
    def discover(): Chunk[Class[? <: TestBase[?]]] = SuiteDiscovery.discover()
end SuiteDiscoveryPlatform

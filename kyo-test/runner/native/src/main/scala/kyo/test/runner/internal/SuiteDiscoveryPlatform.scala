package kyo.test.runner.internal

import kyo.Chunk
import kyo.test.internal.TestBase

/** Scala Native stub for suite discovery.
  *
  * On Scala Native, service-loader-based classpath scanning is unavailable. Test suites are discovered by the native test-interface bridge
  * instead. The CLI is not the primary entry point on Native, so returning empty is correct.
  */
private[runner] object SuiteDiscoveryPlatform:
    def discover(): Chunk[Class[? <: TestBase[?]]] = Chunk.empty
end SuiteDiscoveryPlatform

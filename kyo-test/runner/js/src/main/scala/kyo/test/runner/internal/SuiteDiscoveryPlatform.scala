package kyo.test.runner.internal

import kyo.Chunk
import kyo.test.internal.TestBase

/** Scala.js stub for suite discovery.
  *
  * On Scala.js, service-loader-based classpath scanning is unavailable. Test suites are discovered by the sbt test-interface bridge instead
  * (which provides `TaskDef`s to [[JsRunner]]). The CLI is not the primary entry point on JS, so returning empty is correct.
  */
private[runner] object SuiteDiscoveryPlatform:
    def discover(): Chunk[Class[? <: TestBase[?]]] = Chunk.empty
end SuiteDiscoveryPlatform

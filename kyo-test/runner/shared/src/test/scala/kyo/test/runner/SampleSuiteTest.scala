package kyo.test.runner

import kyo.test.Test

/** Discovery + execution smoke suite confirming that suites extending `kyo.test.Test[Any]` are auto-discovered by the platform `Framework`
  * and routed through the pure-Kyo runner with all leaves green.
  *
  * Unlike the [[RunnerTest]] fixtures (which extend `kyo.test.internal.TestBase[Any]` directly so sbt does NOT auto-discover them), this
  * suite extends `kyo.test.Test[Any]`, so it picks up the non-parametric `SuiteFingerprintMarker` ancestor.
  */
class SampleSuite extends Test[Any]:
    "sample-pass-a" in assert(1 + 1 == 2)
    "sample-pass-b" in assert("kyo".length == 3)
    "sample-pass-c" in assert(2 * 3 == 6)
end SampleSuite

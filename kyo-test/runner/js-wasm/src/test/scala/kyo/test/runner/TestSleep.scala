package kyo.test.runner

/** Scala.js implementation: no-op. [[Thread.sleep]] is not available on the JS runtime and the JS event loop cannot block anyway. Tests
  * that rely on real concurrent blocking are JVM-only by nature; on JS they run without delay.
  */
object TestSleep:
    def sleep(millis: Long): Unit = ()
end TestSleep

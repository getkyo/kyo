package kyo.test.internal

/** Scala.js implementation: no-op. [[Thread.sleep]] is not available on the JS runtime. */
private[test] object ApiTestSleep:
    def sleep(millis: Long): Unit = ()
end ApiTestSleep

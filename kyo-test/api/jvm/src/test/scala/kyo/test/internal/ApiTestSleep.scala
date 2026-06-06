package kyo.test.internal

/** JVM implementation: delegates to [[Thread.sleep]]. */
private[test] object ApiTestSleep:
    def sleep(millis: Long): Unit = Thread.sleep(millis)
end ApiTestSleep

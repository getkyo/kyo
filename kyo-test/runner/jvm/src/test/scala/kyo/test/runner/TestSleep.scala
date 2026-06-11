package kyo.test.runner

/** JVM implementation: delegates to [[Thread.sleep]]. */
object TestSleep:
    def sleep(millis: Long): Unit = Thread.sleep(millis)
end TestSleep

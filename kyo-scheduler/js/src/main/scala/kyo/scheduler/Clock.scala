package kyo.scheduler

import java.util.concurrent.Executor
import scala.annotation.nowarn

@nowarn
final class Clock(executor: Executor = null):

    def currentMillis(): Long = System.currentTimeMillis()

    def stop(): Unit = {}

end Clock

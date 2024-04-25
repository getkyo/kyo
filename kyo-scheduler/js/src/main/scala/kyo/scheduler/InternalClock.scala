package kyo.scheduler

import java.util.concurrent.Executor
import scala.annotation.nowarn

@nowarn
final class InternalClock(executor: Executor = null) {

    def currentMillis(): Long = System.currentTimeMillis()

    def stop(): Unit = {}

}

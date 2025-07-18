package kyo.scheduler

import java.util.concurrent.Executor
import scala.annotation.nowarn

@nowarn
final class InternalClock(executor: Executor = null) {

    var steps = 0
    var curr  = System.currentTimeMillis()

    def currentMillis(): Long = {
        steps += 1
        if ((steps & 128) == 0)
            curr = System.currentTimeMillis()
        curr
    }

    def stop(): Unit = {}

}

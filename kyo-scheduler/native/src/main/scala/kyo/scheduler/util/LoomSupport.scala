package kyo.scheduler.util

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.logging.Logger
import scala.util.control.NonFatal

object LoomSupport {
    def tryVirtualize(enabled: Boolean, exec: Executor): Executor = exec
}

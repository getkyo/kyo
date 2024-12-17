package kyo.scheduler.util

import java.util.concurrent.Executor

object LoomSupport {
    def tryVirtualize(enabled: Boolean, exec: Executor): Executor = exec
}

package kyo.scheduler.util

object LoomSupport {
    def tryVirtualize(enabled: Boolean, exec: Executor): Executor = exec
}

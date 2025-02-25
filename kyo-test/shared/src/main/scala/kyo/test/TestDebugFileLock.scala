package kyo.test

import kyo.*

private[test] object TestDebugFileLock:
    def make: TestDebugFileLock < IO =
        Var.make[Unit](()).map(TestDebugFileLock(_))

private[test] case class TestDebugFileLock(lock: Var[Unit]):
    def updateFile(action: Unit < IO): Unit < IO =
        lock.update(_ => action)

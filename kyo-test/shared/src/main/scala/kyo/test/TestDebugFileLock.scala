package kyo.test

import kyo.*
import kyo.Combinators.*
import kyo.IO
import kyo.Ref

private[test] object TestDebugFileLock:
    def make: TestDebugFileLock < IO =
        Ref.Synchronized.make[Unit](()).map(TestDebugFileLock(_))

private[test] case class TestDebugFileLock(lock: Ref.Synchronized[Unit]):
    def updateFile(action: Unit < IO): Unit < IO =
        lock.update(_ => action)

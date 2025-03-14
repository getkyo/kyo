package kyo.test

import kyo.*

object TestReporters:
    val make: TestReporters < (Env[Any] & Abort[Nothing]) =
        // This SuiteId should probably be passed in a more obvious way
        AtomicRef.init(List(SuiteId.global)).map(TestReporters(_))
end TestReporters

case class TestReporters(reportersStack: Var[List[SuiteId]]):
    def attemptToGetPrintingControl(id: SuiteId, ancestors: List[SuiteId]): Boolean < (Env[Any] & Abort[Nothing]) =
        reportersStack.updateSomeAndGet {
            case Nil =>
                List(id)
            case reporters if ancestors.nonEmpty && reporters.head == ancestors.head =>
                id :: reporters
        }.map(_.head == id)

    def relinquishPrintingControl(id: SuiteId): Unit < (Env[Any] & Abort[Nothing]) =
        reportersStack.updateSome {
            case currentReporter :: reporters if currentReporter == id =>
                reporters
        }
end TestReporters

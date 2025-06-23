package kyo.test

import kyo.*

trait KyoTestApiBase:
    type Assert
    inline def assertKyo(inline assertion: Boolean)(using Frame): Unit < Assert

trait KyoTestApiSpecialAssertion[Assertion]:
    self: KyoTestApiBase =>
    def assertKyo(assertion: => Assertion)(using Frame): Unit < Assert

trait KyoTestApiSync[TestResultSync] extends KyoTestApiBase:
    def runKyoSync(effect: Any < (Assert & Memo & Abort[Any] & IO))(using Frame): TestResultSync

trait KyoTestApiAsync[TestResultAsync] extends KyoTestApiBase:
    def runKyoAsync(effect: Any < (Assert & Memo & Resource & Abort[Any] & Async))(using Frame): TestResultAsync

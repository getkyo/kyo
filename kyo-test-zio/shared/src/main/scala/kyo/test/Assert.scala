package kyo.test

import kyo.*
import zio.test.*

private[test] object KyoAssert:
    opaque type Assert = Emit[TestResult]

    def run[A, S](effect: A < (Assert & S))(using Frame): TestResult < S =
        Emit.run[TestResult](effect).map:
            case (resultChunk, _) =>
                resultChunk.foldLeft(assertTrue(true))(_ && _)

    def get(result: TestResult)(using Frame): Unit < Assert =
        Emit.value(result)

end KyoAssert

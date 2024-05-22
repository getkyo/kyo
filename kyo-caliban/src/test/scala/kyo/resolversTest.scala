package kyo

import _root_.caliban.schema.Schema
import kyo.*
import kyoTest.KyoTest

class resolversTest extends KyoTest:

    case class Query(
        k1: Int < Aborts[Throwable],
        k2: Int < ZIOs,
        k3: String < (Aborts[Throwable] & ZIOs)
    ) derives Schema.SemiAuto

end resolversTest

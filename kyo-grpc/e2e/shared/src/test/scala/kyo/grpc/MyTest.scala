package kyo.grpc

import kyo.grpc.test.*
import kyo.*

class MyTest extends KyoTest:

    "TestMessageFieldNums is generated" in IOs.run {
        assert(TestMessageFieldNums.a == 1)
        assert(TestMessageFieldNums.b == 2)
    }

    "NestedMessage is generated" in IOs.run {
        assert(TestMessageFieldNums.NestedMessageFieldNums.color == 1)
    }

end MyTest

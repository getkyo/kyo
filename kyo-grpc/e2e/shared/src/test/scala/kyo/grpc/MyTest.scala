package kyo.grpc

import kyo.grpc.test.*

class MyTest extends munit.FunSuite:
    test("TestMessageFieldNums is generated") {
        assertEquals(TestMessageFieldNums.a, 1)
        assertEquals(TestMessageFieldNums.b, 2)
    }

    test("NestedMessage is generated") {
        assertEquals(TestMessageFieldNums.NestedMessageFieldNums.color, 1)
    }
end MyTest

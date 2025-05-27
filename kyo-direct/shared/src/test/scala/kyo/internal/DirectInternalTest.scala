package kyo.internal

import kyo.*
import kyo.directInternal.shiftedWhile

class DirectInternalTest extends Test:
    "shiftedWhile" in {

        def isEven(i: Int): Boolean < Any = i % 2 == 0

        def lessThanThree(i: Int): Boolean < Any = i < 3

        val countWhile = shiftedWhile(Chunk(1, 2, 3, 4, 5))(
            prolog = 0,
            f = lessThanThree,
            acc = (count, include, _) => count + 1,
            epilog = identity
        )
        assert(countWhile.eval == 3)

        val sumWithMessage = shiftedWhile(Chunk(1, 2, -1, 3, 4))(
            prolog = 0,
            f = lessThanThree,
            acc = (sum, include, curr) => if include then sum + curr else sum,
            epilog = sum => s"Sum: $sum"
        )
        assert(sumWithMessage.eval == "Sum: 2") // 1 + 2 + (-1) = 2

        val collectUntilOdd = shiftedWhile(Chunk(2, 4, 6, 7, 8))(
            prolog = List.empty,
            f = isEven,
            acc = (list, include, curr) => if include then curr :: list else list,
            epilog = _.reverse // Maintain original order
        )
        assert(collectUntilOdd.eval == List(2, 4, 6))

        val emptyTest = shiftedWhile(Seq.empty[Int])(
            prolog = "Default",
            f = _ => true,
            acc = (str, _, curr) => str + curr.toString,
            epilog = _.toUpperCase
        )
        assert(emptyTest.eval == "DEFAULT")

        val arrayTest = shiftedWhile(Array(1, 2, 3, 4))(
            prolog = "",
            f = lessThanThree,
            acc = (str, include, curr) => if include then str + curr.toString else str,
            epilog = _.length
        )
        assert(arrayTest.eval == 2)
    }
end DirectInternalTest

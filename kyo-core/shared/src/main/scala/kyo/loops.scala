package kyo

import kyo.core.internal.Kyo
import scala.annotation.tailrec
import scala.util.NotGiven

object Loops:

    private case class Continue[Input](input: Input)
    private case class Continue2[Input1, Input2](input1: Input1, input2: Input2)
    private case class Continue3[Input1, Input2, Input3](input1: Input1, input2: Input2, input3: Input3)

    opaque type Result[Input, Output]                   = Output | Continue[Input]
    opaque type Result2[Input1, Input2, Output]         = Output | Continue2[Input1, Input2]
    opaque type Result3[Input1, Input2, Input3, Output] = Output | Continue3[Input1, Input2, Input3]

    inline def done[Input, Output](v: Output): Result[Input, Output]       = v
    inline def continue[Input, Output, S](v: Input): Result[Input, Output] = Continue(v)

    inline def done[Input1, Input2, Output](v: Output): Result2[Input1, Input2, Output] = v
    inline def continue[Input1, Input2, Output](
        v1: Input1,
        v2: Input2
    ): Result2[Input1, Input2, Output] = Continue2(v1, v2)

    inline def done[Input1, Input2, Input3, Output](v: Output): Result3[Input1, Input2, Input3, Output] = v
    inline def continue[Input1, Input2, Input3, Output](
        v1: Input1,
        v2: Input2,
        v3: Input3
    ): Result3[Input1, Input2, Input3, Output] = Continue3(v1, v2, v3)

    inline def transform[Input, Output: Flat, S](
        input: Input
    )(
        inline run: Input => Result[Input, Output] < S
    ): Output < S =
        def _loop(input: Input): Output < S =
            loop(input)
        @tailrec def loop(input: Input): Output < S =
            run(input) match
                case next: Continue[Input] @unchecked =>
                    loop(next.input)
                case kyo: Kyo[Output | Continue[Input], S] @unchecked =>
                    kyo.map {
                        case next: Continue[Input] =>
                            _loop(next.input)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case res =>
                    res.asInstanceOf[Output]
        loop(input)
    end transform

    inline def transform2[Input1, Input2, Output: Flat, S](
        input1: Input1,
        input2: Input2
    )(
        inline run: (Input1, Input2) => Result2[Input1, Input2, Output] < S
    ): Output < S =
        def _loop(input1: Input1, input2: Input2): Output < S =
            loop(input1, input2)
        @tailrec def loop(input1: Input1, input2: Input2): Output < S =
            run(input1, input2) match
                case next: Continue2[Input1, Input2] @unchecked =>
                    loop(next.input1, next.input2)
                case kyo: Kyo[Output | Continue2[Input1, Input2], S] @unchecked =>
                    kyo.map {
                        case next: Continue2[Input1, Input2] =>
                            _loop(next.input1, next.input2)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case res =>
                    res.asInstanceOf[Output]
        loop(input1, input2)
    end transform2

    inline def transform3[Input1, Input2, Input3, Output: Flat, S](
        input1: Input1,
        input2: Input2,
        input3: Input3
    )(
        inline run: (Input1, Input2, Input3) => Result3[Input1, Input2, Input3, Output] < S
    ): Output < S =
        def _loop(input1: Input1, input2: Input2, input3: Input3): Output < S =
            loop(input1, input2, input3)
        @tailrec def loop(input1: Input1, input2: Input2, input3: Input3): Output < S =
            run(input1, input2, input3) match
                case next: Continue3[Input1, Input2, Input3] @unchecked =>
                    loop(next.input1, next.input2, next.input3)
                case kyo: Kyo[Output | Continue3[Input1, Input2, Input3], S] @unchecked =>
                    kyo.map {
                        case next: Continue3[Input1, Input2, Input3] =>
                            _loop(next.input1, next.input2, next.input3)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case res =>
                    res.asInstanceOf[Output]
        loop(input1, input2, input3)
    end transform3
end Loops

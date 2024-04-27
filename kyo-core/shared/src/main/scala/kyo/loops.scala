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

    private val _continueUnit = Continue[Unit](())

    inline def continue[T]: Result[Unit, T] = _continueUnit
    inline def done[T]: Result[T, Unit]     = ()

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

    inline def transform[Input1, Input2, Output: Flat, S](
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
                        case next: Continue2[Input1, Input2] @unchecked =>
                            _loop(next.input1, next.input2)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case res =>
                    res.asInstanceOf[Output]
        loop(input1, input2)
    end transform

    inline def transform[Input1, Input2, Input3, Output: Flat, S](
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
    end transform

    inline def indexed[Output: Flat, S](
        inline run: Int => Result[Unit, Output] < S
    ): Output < S =
        def _loop(idx: Int): Output < S =
            loop(idx)
        @tailrec def loop(idx: Int): Output < S =
            run(idx) match
                case next: Continue[Unit] @unchecked =>
                    loop(idx + 1)
                case kyo: Kyo[Output | Continue[Unit], S] @unchecked =>
                    kyo.map {
                        case next: Continue[Unit] @unchecked =>
                            _loop(idx + 1)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case res =>
                    res.asInstanceOf[Output]
        loop(0)
    end indexed

    inline def indexed[Input, Output: Flat, S](
        input: Input
    )(
        inline run: (Int, Input) => Result[Input, Output] < S
    ): Output < S =
        def _loop(idx: Int, input: Input): Output < S =
            loop(idx, input)
        @tailrec def loop(idx: Int, input: Input): Output < S =
            run(idx, input) match
                case next: Continue[Input] @unchecked =>
                    loop(idx + 1, next.input)
                case kyo: Kyo[Output | Continue[Input], S] @unchecked =>
                    kyo.map {
                        case next: Continue[Input] @unchecked =>
                            _loop(idx + 1, next.input)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case res =>
                    res.asInstanceOf[Output]
        loop(0, input)
    end indexed

    inline def indexed[Input1, Input2, Output: Flat, S](
        input1: Input1,
        input2: Input2
    )(
        inline run: (Int, Input1, Input2) => Result2[Input1, Input2, Output] < S
    ): Output < S =
        def _loop(idx: Int, input1: Input1, input2: Input2): Output < S =
            loop(idx, input1, input2)
        @tailrec def loop(idx: Int, input1: Input1, input2: Input2): Output < S =
            run(idx, input1, input2) match
                case next: Continue2[Input1, Input2] @unchecked =>
                    loop(idx + 1, next.input1, next.input2)
                case kyo: Kyo[Output | Continue2[Input1, Input2], S] @unchecked =>
                    kyo.map {
                        case next: Continue2[Input1, Input2] =>
                            _loop(idx + 1, next.input1, next.input2)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case res =>
                    res.asInstanceOf[Output]
        loop(0, input1, input2)
    end indexed

    inline def indexed[Input1, Input2, Input3, Output: Flat, S](
        input1: Input1,
        input2: Input2,
        input3: Input3
    )(
        inline run: (Int, Input1, Input2, Input3) => Result3[Input1, Input2, Input3, Output] < S
    ): Output < S =
        def _loop(idx: Int, input1: Input1, input2: Input2, input3: Input3): Output < S =
            loop(idx, input1, input2, input3)
        @tailrec def loop(idx: Int, input1: Input1, input2: Input2, input3: Input3): Output < S =
            run(idx, input1, input2, input3) match
                case next: Continue3[Input1, Input2, Input3] @unchecked =>
                    loop(idx + 1, next.input1, next.input2, next.input3)
                case kyo: Kyo[Output | Continue3[Input1, Input2, Input3], S] @unchecked =>
                    kyo.map {
                        case next: Continue3[Input1, Input2, Input3] =>
                            _loop(idx + 1, next.input1, next.input2, next.input3)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case res =>
                    res.asInstanceOf[Output]
        loop(0, input1, input2, input3)
    end indexed

    inline def foreach[S](
        inline run: => Result[Unit, Unit] < S
    ): Unit < S =
        def _loop(): Unit < S =
            loop()
        @tailrec def loop(): Unit < S =
            run match
                case next: Continue[Unit] @unchecked =>
                    loop()
                case kyo: Kyo[Unit | Continue[Unit], S] @unchecked =>
                    kyo.map {
                        case next: Continue[Unit] =>
                            _loop()
                        case res =>
                            ()
                    }
                case res =>
                    ()
        loop()
    end foreach
end Loops

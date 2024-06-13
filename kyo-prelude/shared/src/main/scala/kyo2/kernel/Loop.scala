package kyo2.kernel

import internal.*
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

object Loop:

    case class Continue[Input](input: Input)
    private case class Continue2[Input1, Input2](input1: Input1, input2: Input2)
    private case class Continue3[Input1, Input2, Input3](input1: Input1, input2: Input2, input3: Input3)

    opaque type Result[Input, Output]                   = Output | Continue[Input]
    opaque type Result2[Input1, Input2, Output]         = Output | Continue2[Input1, Input2]
    opaque type Result3[Input1, Input2, Input3, Output] = Output | Continue3[Input1, Input2, Input3]

    private val _continueUnit = Continue[Unit](())

    inline def continue[A]: Result[Unit, A] = _continueUnit

    @targetName("done0")
    def done[A]: Result[A, Unit] = ()
    @targetName("done1")
    def done[Input, Output](v: Output): Result[Input, Output] = v
    @targetName("done2")
    def done[Input1, Input2, Output](v: Output): Result2[Input1, Input2, Output] = v
    @targetName("done3")
    def done[Input1, Input2, Input3, Output](v: Output): Result3[Input1, Input2, Input3, Output] = v

    inline def continue[Input, Output, S](v: Input): Result[Input, Output] = Continue(v)

    inline def continue[Input1, Input2, Output](
        v1: Input1,
        v2: Input2
    ): Result2[Input1, Input2, Output] = Continue2(v1, v2)

    inline def continue[Input1, Input2, Input3, Output](
        v1: Input1,
        v2: Input2,
        v3: Input3
    ): Result3[Input1, Input2, Input3, Output] = Continue3(v1, v2, v3)

    inline def transform[Input, Output, S](
        inline input: Input
    )(
        inline run: Input => Result[Input, Output] < S
    )(using Frame): Output < S =
        def next(input: Input): Result[Input, Output] < S =
            run(input)
        def _loop(input: Input): Output < S =
            loop(input)
        @tailrec def loop(input: Input): Output < S =
            next(input) match
                case <(next: Continue[Input] @unchecked) =>
                    loop(next.input)
                case kyo @ <(_: Kyo[Output | Continue[Input], S] @unchecked) =>
                    kyo.map {
                        case next: Continue[Input] @unchecked =>
                            _loop(next.input)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case <(res) =>
                    res.asInstanceOf[Output]
        loop(input)
    end transform

    inline def transform[Input1, Input2, Output, S](
        input1: Input1,
        input2: Input2
    )(
        inline run: (Input1, Input2) => Result2[Input1, Input2, Output] < S
    )(using Frame): Output < S =
        def _loop(input1: Input1, input2: Input2): Output < S =
            loop(input1, input2)
        @tailrec def loop(input1: Input1, input2: Input2): Output < S =
            run(input1, input2) match
                case <(next: Continue2[Input1, Input2] @unchecked) =>
                    loop(next.input1, next.input2)
                case kyo @ <(_: Kyo[Output | Continue2[Input1, Input2], S] @unchecked) =>
                    kyo.map {
                        case next: Continue2[Input1, Input2] @unchecked =>
                            _loop(next.input1, next.input2)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case <(res) =>
                    res.asInstanceOf[Output]
        loop(input1, input2)
    end transform

    inline def transform[Input1, Input2, Input3, Output, S](
        input1: Input1,
        input2: Input2,
        input3: Input3
    )(
        inline run: (Input1, Input2, Input3) => Result3[Input1, Input2, Input3, Output] < S
    )(using Frame): Output < S =
        def _loop(input1: Input1, input2: Input2, input3: Input3): Output < S =
            loop(input1, input2, input3)
        @tailrec def loop(input1: Input1, input2: Input2, input3: Input3): Output < S =
            run(input1, input2, input3) match
                case <(next: Continue3[Input1, Input2, Input3] @unchecked) =>
                    loop(next.input1, next.input2, next.input3)
                case kyo @ <(_: Kyo[Output | Continue2[Input1, Input2], S] @unchecked) =>
                    kyo.map {
                        case next: Continue3[Input1, Input2, Input3] @unchecked =>
                            _loop(next.input1, next.input2, next.input3)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case <(res) =>
                    res.asInstanceOf[Output]
        loop(input1, input2, input3)
    end transform

    inline def indexed[Output, S](
        inline run: Int => Result[Unit, Output] < S
    )(using Frame): Output < S =
        def _loop(idx: Int): Output < S =
            loop(idx)
        @tailrec def loop(idx: Int): Output < S =
            run(idx) match
                case <(next: Continue[Unit] @unchecked) =>
                    loop(idx + 1)
                case kyo @ <(_: Kyo[Output | Continue[Unit], S] @unchecked) =>
                    kyo.map {
                        case next: Continue[Unit] @unchecked =>
                            _loop(idx + 1)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case <(res) =>
                    res.asInstanceOf[Output]
        loop(0)
    end indexed

    inline def indexed[Input, Output, S](
        input: Input
    )(
        inline run: (Int, Input) => Result[Input, Output] < S
    )(using Frame): Output < S =
        def _loop(idx: Int, input: Input): Output < S =
            loop(idx, input)
        @tailrec def loop(idx: Int, input: Input): Output < S =
            run(idx, input) match
                case <(next: Continue[Input] @unchecked) =>
                    loop(idx + 1, next.input)
                case kyo @ <(_: Kyo[Output | Continue[Input], S] @unchecked) =>
                    kyo.map {
                        case next: Continue[Input] @unchecked =>
                            _loop(idx + 1, next.input)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case <(res) =>
                    res.asInstanceOf[Output]
        loop(0, input)
    end indexed

    inline def indexed[Input1, Input2, Output, S](
        input1: Input1,
        input2: Input2
    )(
        inline run: (Int, Input1, Input2) => Result2[Input1, Input2, Output] < S
    )(using Frame): Output < S =
        def _loop(idx: Int, input1: Input1, input2: Input2): Output < S =
            loop(idx, input1, input2)
        @tailrec def loop(idx: Int, input1: Input1, input2: Input2): Output < S =
            run(idx, input1, input2) match
                case <(next: Continue2[Input1, Input2] @unchecked) =>
                    loop(idx + 1, next.input1, next.input2)
                case kyo @ <(_: Kyo[Output | Continue2[Input1, Input2], S] @unchecked) =>
                    kyo.map {
                        case next: Continue2[Input1, Input2] @unchecked =>
                            _loop(idx + 1, next.input1, next.input2)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case <(res) =>
                    res.asInstanceOf[Output]
        loop(0, input1, input2)
    end indexed

    inline def indexed[Input1, Input2, Input3, Output, S](
        input1: Input1,
        input2: Input2,
        input3: Input3
    )(
        inline run: (Int, Input1, Input2, Input3) => Result3[Input1, Input2, Input3, Output] < S
    )(using Frame): Output < S =
        def _loop(idx: Int, input1: Input1, input2: Input2, input3: Input3): Output < S =
            loop(idx, input1, input2, input3)
        @tailrec def loop(idx: Int, input1: Input1, input2: Input2, input3: Input3): Output < S =
            run(idx, input1, input2, input3) match
                case <(next: Continue3[Input1, Input2, Input3] @unchecked) =>
                    loop(idx + 1, next.input1, next.input2, next.input3)
                case kyo @ <(_: Kyo[Output | Continue3[Input1, Input2, Input3], S] @unchecked) =>
                    kyo.map {
                        case next: Continue3[Input1, Input2, Input3] @unchecked =>
                            _loop(idx + 1, next.input1, next.input2, next.input3)
                        case res =>
                            res.asInstanceOf[Output]
                    }
                case <(res) =>
                    res.asInstanceOf[Output]
        loop(0, input1, input2, input3)
    end indexed

    inline def foreach[S](
        inline run: => Result[Unit, Unit] < S
    )(using Frame): Unit < S =
        def _loop(): Unit < S =
            loop()
        @tailrec def loop(): Unit < S =
            run match
                case <(next: Continue[Unit] @unchecked) =>
                    loop()
                case kyo @ <(_: Kyo[Unit | Continue[Unit], S] @unchecked) =>
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

    inline def repeat[S](n: Int)(inline run: => Unit < S)(using Frame): Unit < S =
        def _loop(i: Int): Unit < S =
            loop(i)
        @tailrec def loop(i: Int = 0): Unit < S =
            if i == n then ()
            else
                run match
                    case kyo @ <(_: Kyo[Unit, S] @unchecked) =>
                        kyo.andThen(_loop(i + 1))
                    case _ =>
                        loop(i + 1)
        loop()
    end repeat

    inline def forever[S](inline run: Unit < S)(using Frame): Unit < S =
        def _loop(): Unit < S =
            loop()
        @tailrec def loop(): Unit < S =
            run match
                case kyo @ <(_: Kyo[Unit, S] @unchecked) =>
                    kyo.andThen(_loop())
                case _ =>
                    loop()
        loop()
    end forever
end Loop

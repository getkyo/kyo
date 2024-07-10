package kyo2.kernel

import internal.*
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

object Loop:

    abstract class Continue[V1]:
        private[Loop] def _v1: V1
    abstract class Continue2[V1, V2]:
        private[Loop] def _v1: V1
        private[Loop] def _v2: V2
    abstract class Continue3[V1, V2, V3]:
        private[Loop] def _v1: V1
        private[Loop] def _v2: V2
        private[Loop] def _v3: V3
    end Continue3
    abstract class Continue4[V1, V2, V3, V4]:
        private[Loop] def _v1: V1
        private[Loop] def _v2: V2
        private[Loop] def _v3: V3
        private[Loop] def _v4: V4
    end Continue4

    opaque type Result[I, O]               = O | Continue[I]
    opaque type Result2[I1, I2, O]         = O | Continue2[I1, I2]
    opaque type Result3[I1, I2, I3, O]     = O | Continue3[I1, I2, I3]
    opaque type Result4[I1, I2, I3, I4, O] = O | Continue4[I1, I2, I3, I4]

    private val _continueUnit: Continue[Unit] =
        new Continue:
            def _v1 = ()

    inline def continue[A]: Result[Unit, A] = _continueUnit

    @targetName("done0")
    def done[A]: Result[A, Unit] = ()
    @targetName("done1")
    def done[I, O](v: O): Result[I, O] = v
    @targetName("done2")
    def done[I1, I2, O](v: O): Result2[I1, I2, O] = v
    @targetName("done3")
    def done[I1, I2, I3, O](v: O): Result3[I1, I2, I3, O] = v
    @targetName("done4")
    def done[I1, I2, I3, I4, O](v: O): Result4[I1, I2, I3, I4, O] = v

    inline def continue[I, O, S](inline v: I): Result[I, O] =
        new Continue:
            def _v1 = v

    inline def continue[I1, I2, o](inline v1: I1, inline v2: I2): Result2[I1, I2, o] =
        new Continue2:
            def _v1 = v1
            def _v2 = v2

    inline def continue[I1, I2, I3, O](inline v1: I1, inline v2: I2, inline v3: I3): Result3[I1, I2, I3, O] =
        new Continue3:
            def _v1 = v1
            def _v2 = v2
            def _v3 = v3

    inline def continue[I1, I2, I3, I4, O](inline v1: I1, inline v2: I2, inline v3: I3, inline v4: I4): Result4[I1, I2, I3, I4, O] =
        new Continue4:
            def _v1 = v1
            def _v2 = v2
            def _v3 = v3
            def _v4 = v4

    inline def apply[I, O, S](inline input: I)(inline run: I => Result[I, O] < S)(using Frame): O < S =
        def _loop(i1: I): O < S = loop(i1)
        @tailrec def loop(i1: I): O < S =
            run(i1) match
                case <(next: Continue[I] @unchecked) =>
                    loop(next._v1)
                case kyo @ <(_: Kyo[O | Continue[I], S] @unchecked) =>
                    kyo.map {
                        case next: Continue[I] @unchecked =>
                            _loop(next._v1)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(input)
    end apply

    inline def apply[I1, I2, O, S](input1: I1, input2: I2)(inline run: (I1, I2) => Result2[I1, I2, O] < S)(using Frame): O < S =
        def _loop(i1: I1, i2: I2): O < S = loop(i1, i2)
        @tailrec def loop(i1: I1, i2: I2): O < S =
            run(i1, i2) match
                case <(next: Continue2[I1, I2] @unchecked) =>
                    loop(next._v1, next._v2)
                case kyo @ <(_: Kyo[o | Continue2[I1, I2], S] @unchecked) =>
                    kyo.map {
                        case next: Continue2[I1, I2] @unchecked =>
                            _loop(next._v1, next._v2)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(input1, input2)
    end apply

    inline def apply[I1, I2, I3, O, S](input1: I1, input2: I2, input3: I3)(
        inline run: (I1, I2, I3) => Result3[I1, I2, I3, O] < S
    )(using Frame): O < S =
        def _loop(i1: I1, i2: I2, i3: I3): O < S = loop(i1, i2, i3)
        @tailrec def loop(i1: I1, i2: I2, i3: I3): O < S =
            run(i1, i2, i3) match
                case <(next: Continue3[I1, I2, I3] @unchecked) =>
                    loop(next._v1, next._v2, next._v3)
                case kyo @ <(_: Kyo[O | Continue3[I1, I2, I3], S] @unchecked) =>
                    kyo.map {
                        case next: Continue3[I1, I2, I3] @unchecked =>
                            _loop(next._v1, next._v2, next._v3)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(input1, input2, input3)
    end apply

    inline def apply[I1, I2, I3, I4, O, S](input1: I1, input2: I2, input3: I3, input4: I4)(
        inline run: (I1, I2, I3, I4) => Result4[I1, I2, I3, I4, O] < S
    )(using Frame): O < S =
        def _loop(i1: I1, i2: I2, i3: I3, i4: I4): O < S = loop(i1, i2, i3, i4)
        @tailrec def loop(i1: I1, i2: I2, i3: I3, i4: I4): O < S =
            run(i1, i2, i3, i4) match
                case <(next: Continue4[I1, I2, I3, I4] @unchecked) =>
                    loop(next._v1, next._v2, next._v3, next._v4)
                case kyo @ <(_: Kyo[O | Continue4[I1, I2, I3, I4], S] @unchecked) =>
                    kyo.map {
                        case next: Continue4[I1, I2, I3, I4] @unchecked =>
                            _loop(next._v1, next._v2, next._v3, next._v4)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(input1, input2, input3, input4)
    end apply

    inline def indexed[O, S](inline run: Int => Result[Unit, O] < S)(using Frame): O < S =
        def _loop(idx: Int): O < S = loop(idx)
        @tailrec def loop(idx: Int): O < S =
            run(idx) match
                case <(next: Continue[Unit] @unchecked) =>
                    loop(idx + 1)
                case kyo @ <(_: Kyo[O | Continue[Unit], S] @unchecked) =>
                    kyo.map {
                        case next: Continue[Unit] @unchecked =>
                            _loop(idx + 1)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(0)
    end indexed

    inline def indexed[I, O, S](input: I)(inline run: (Int, I) => Result[I, O] < S)(using Frame): O < S =
        def _loop(idx: Int, i1: I): O < S = loop(idx, i1)
        @tailrec def loop(idx: Int, i1: I): O < S =
            run(idx, i1) match
                case <(next: Continue[I] @unchecked) =>
                    loop(idx + 1, next._v1)
                case kyo @ <(_: Kyo[O | Continue[I], S] @unchecked) =>
                    kyo.map {
                        case next: Continue[I] @unchecked =>
                            _loop(idx + 1, next._v1)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(0, input)
    end indexed

    inline def indexed[I1, I2, O, S](input1: I1, input2: I2)(inline run: (Int, I1, I2) => Result2[I1, I2, O] < S)(using Frame): O < S =
        def _loop(idx: Int, i1: I1, i2: I2): O < S = loop(idx, i1, i2)
        @tailrec def loop(idx: Int, i1: I1, i2: I2): O < S =
            run(idx, i1, i2) match
                case <(next: Continue2[I1, I2] @unchecked) =>
                    loop(idx + 1, next._v1, next._v2)
                case kyo @ <(_: Kyo[O | Continue2[I1, I2], S] @unchecked) =>
                    kyo.map {
                        case next: Continue2[I1, I2] @unchecked =>
                            _loop(idx + 1, next._v1, next._v2)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(0, input1, input2)
    end indexed

    inline def indexed[I1, I2, I3, O, S](input1: I1, input2: I2, input3: I3)(
        inline run: (Int, I1, I2, I3) => Result3[I1, I2, I3, O] < S
    )(using Frame): O < S =
        def _loop(idx: Int, i1: I1, i2: I2, i3: I3): O < S = loop(idx, i1, i2, i3)
        @tailrec def loop(idx: Int, i1: I1, i2: I2, i3: I3): O < S =
            run(idx, i1, i2, i3) match
                case <(next: Continue3[I1, I2, I3] @unchecked) =>
                    loop(idx + 1, next._v1, next._v2, next._v3)
                case kyo @ <(_: Kyo[O | Continue3[I1, I2, I3], S] @unchecked) =>
                    kyo.map {
                        case next: Continue3[I1, I2, I3] @unchecked =>
                            _loop(idx + 1, next._v1, next._v2, next._v3)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(0, input1, input2, input3)
    end indexed

    inline def indexed[I1, I2, I3, I4, O, S](input1: I1, input2: I2, input3: I3, input4: I4)(
        inline run: (Int, I1, I2, I3, I4) => Result4[I1, I2, I3, I4, O] < S
    )(using Frame): O < S =
        def _loop(idx: Int, i1: I1, i2: I2, i3: I3, i4: I4): O < S = loop(idx, i1, i2, i3, i4)
        @tailrec def loop(idx: Int, i1: I1, i2: I2, i3: I3, i4: I4): O < S =
            run(idx, i1, i2, i3, i4) match
                case <(next: Continue4[I1, I2, I3, I4] @unchecked) =>
                    loop(idx + 1, next._v1, next._v2, next._v3, next._v4)
                case kyo @ <(_: Kyo[O | Continue4[I1, I2, I3, I4], S] @unchecked) =>
                    kyo.map {
                        case next: Continue4[I1, I2, I3, I4] @unchecked =>
                            _loop(idx + 1, next._v1, next._v2, next._v3, next._v4)
                        case res =>
                            res.asInstanceOf[O]
                    }
                case <(res) =>
                    res.asInstanceOf[O]
        loop(0, input1, input2, input3, input4)
    end indexed

    inline def foreach[S](inline run: => Result[Unit, Unit] < S)(using Frame): Unit < S =
        def _loop(): Unit < S = loop()
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
        def _loop(i: Int): Unit < S = loop(i)
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
        def _loop(): Unit < S = loop()
        @tailrec def loop(): Unit < S =
            run match
                case kyo @ <(_: Kyo[Unit, S] @unchecked) =>
                    kyo.andThen(_loop())
                case _ =>
                    loop()
        loop()
    end forever
end Loop

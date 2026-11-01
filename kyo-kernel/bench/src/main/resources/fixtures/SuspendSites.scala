package kyobench

import kyo.*
import kyo.kernel.*

object SuspendSites:

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    sealed trait Tell extends ArrowEffect[[X] =>> Int, [X] =>> Unit]

    def get0: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get1: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get2: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get3: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get4: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get5: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get6: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get7: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get8: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get9: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get10: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get11: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get12: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get13: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get14: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get15: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get16: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get17: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get18: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get19: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get20: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get21: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get22: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get23: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get24: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get25: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get26: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get27: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get28: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def get29: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def put0(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put1(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put2(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put3(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put4(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put5(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put6(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put7(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put8(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put9(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put10(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put11(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put12(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put13(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put14(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put15(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put16(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put17(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put18(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put19(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put20(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put21(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put22(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put23(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put24(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put25(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put26(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put27(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put28(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

    def put29(v: Int): Int < (Ask & Tell) =
        ArrowEffect.suspendWith[Any](Tag[Tell], v)(_ => ArrowEffect.suspend[Any](Tag[Ask], ()))

end SuspendSites

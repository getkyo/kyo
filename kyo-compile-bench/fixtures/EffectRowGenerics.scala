package kyobench

import kyo.*
import kyo.kernel.*

object EffectRowGenerics:

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    sealed trait Log extends ArrowEffect[[X] =>> String, [X] =>> Unit]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())
    def log(s: String): Unit < Log = ArrowEffect.suspend[Any](Tag[Log], s)

    def zip[A, B, S, S2](a: A < S, b: B < S2): (A, B) < (S & S2) =
        a.map(x => b.map(y => (x, y)))

    def retry[A, S](n: Int)(v: => A < S): A < S =
        if n <= 0 then v else v.map(_ => retry(n - 1)(v))

    def traverse[A, B, S](xs: List[A])(f: A => B < S): List[B] < S =
        xs match
            case Nil          => Nil
            case head :: tail => f(head).map(b => traverse(tail)(f).map(bs => b :: bs))

    def use0: (Int, Unit) < (Ask & Log) =
        zip(retry(0)(ask), log("m0"))

    def use1: (Int, Unit) < (Ask & Log) =
        zip(retry(1)(ask), log("m1"))

    def use2: (Int, Unit) < (Ask & Log) =
        zip(retry(2)(ask), log("m2"))

    def use3: (Int, Unit) < (Ask & Log) =
        zip(retry(3)(ask), log("m3"))

    def use4: (Int, Unit) < (Ask & Log) =
        zip(retry(4)(ask), log("m4"))

    def use5: (Int, Unit) < (Ask & Log) =
        zip(retry(5)(ask), log("m5"))

    def use6: (Int, Unit) < (Ask & Log) =
        zip(retry(6)(ask), log("m6"))

    def use7: (Int, Unit) < (Ask & Log) =
        zip(retry(7)(ask), log("m7"))

    def use8: (Int, Unit) < (Ask & Log) =
        zip(retry(8)(ask), log("m8"))

    def use9: (Int, Unit) < (Ask & Log) =
        zip(retry(9)(ask), log("m9"))

    def all0(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 0)))

    def all1(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 1)))

    def all2(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 2)))

    def all3(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 3)))

    def all4(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 4)))

    def all5(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 5)))

    def all6(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 6)))

    def all7(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 7)))

    def all8(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 8)))

    def all9(xs: List[Int]): List[Int] < (Ask & Log) =
        traverse(xs)(x => log("x").map(_ => ask.map(_ + x + 9)))

end EffectRowGenerics

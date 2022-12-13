package kyoTest

import kyo.core._
import scala.util.Try

class coreTest extends KyoTest {

  object effects {

    opaque type Id1[T] = T
    class Ids1 extends Effect[Id1] {
      def apply[T, S](v: T > S): T > (S | Ids1) = v
    }
    val Ids1 = new Ids1

    given ShallowHandler[Id1, Ids1] =
      new ShallowHandler[Id1, Ids1] {
        def pure[T](v: T)                                     = v
        def apply[T, U, S](m: Id1[T], f: T => U > (S | Ids1)) = f(m)
      }

    opaque type Id2[T] = T
    class Ids2 extends Effect[Id2] {
      def apply[T, S](v: T > S): T > (S | Ids2) = v
    }
    val Ids2 = new Ids2

    given ShallowHandler[Id2, Ids2] =
      new ShallowHandler[Id2, Ids2] {
        def pure[T](v: T)                                     = v
        def apply[T, U, S](m: Id2[T], f: T => U > (S | Ids2)) = f(m)
      }

    opaque type Id3[T] = T
    class Ids3 extends Effect[Id3] {
      def apply[T, S](v: T > S): T > (S | Ids3) = v
    }
    val Ids3 = new Ids3

    given ShallowHandler[Id3, Ids3] =
      new ShallowHandler[Id3, Ids3] {
        def pure[T](v: T)                                     = v
        def apply[T, U, S](m: Id3[T], f: T => U > (S | Ids3)) = f(m)
      }

    opaque type Id4[T] = T
    class Ids4 extends Effect[Id4] {
      def apply[T, S](v: T > S): T > (S | Ids4) = v
    }
    val Ids4 = new Ids4

    given ShallowHandler[Id4, Ids4] =
      new ShallowHandler[Id4, Ids4] {
        def pure[T](v: T)                                     = v
        def apply[T, U, S](m: Id4[T], f: T => U > (S | Ids4)) = f(m)
      }

    opaque type Id5[T] = T
    class Ids5 extends Effect[Id5] {
      def apply[T, S](v: T > S): T > (S | Ids5) = v
    }
    val Ids5 = new Ids5

    given ShallowHandler[Id5, Ids5] =
      new ShallowHandler[Id5, Ids5] {
        def pure[T](v: T)                                     = v
        def apply[T, U, S](m: Id5[T], f: T => U > (S | Ids5)) = f(m)
      }

    given DeepHandler[Id1, Ids1] =
      new DeepHandler[Id1, Ids1] {
        def pure[T](v: T) = v
        def flatMap[T, U](m: Id1[T], f: T => Id1[U]) = f(m)
      }

    given DeepHandler[Id2, Ids2] =
      new DeepHandler[Id2, Ids2] {
        def pure[T](v: T) = v
        def flatMap[T, U](m: Id2[T], f: T => Id2[U]) = f(m)
      }

    given DeepHandler[Id3, Ids3] =
      new DeepHandler[Id3, Ids3] {
        def pure[T](v: T) = v
        def flatMap[T, U](m: Id3[T], f: T => Id3[U]) = f(m)
      }

    given DeepHandler[Id4, Ids4] =
      new DeepHandler[Id4, Ids4] {
        def pure[T](v: T) = v
        def flatMap[T, U](m: Id4[T], f: T => Id4[U]) = f(m)
      }
    
    given DeepHandler[Id5, Ids5] =
      new DeepHandler[Id5, Ids5] {
        def pure[T](v: T): Id5[T] = v
        def flatMap[T, U](m: Id5[T], f: T => Id5[U]): Id5[U] = f(m)
      }
  }

  import effects._

  "type inference" in {
    val a = Ids1(Ids2(Ids3(Ids4(Ids5(1)))))
    // val e: Id3[Id2[Id1[Int]]] > (Ids4 | Ids5) = d
    // val e = d < Ids4
    // val f = e < Ids5

  }

  // "suspend" - {
  //   "one" in {
  //     var called = false
  //     val a =
  //       (Id1(1) > Ids1) { i =>
  //         called = true
  //         i + 1
  //       }
  //     assert(!called)
  //     assert(((a < Ids1): Int) == 2)
  //     assert(called)
  //   }
  //   "nested" in {
  //     var called = false
  //     val a =
  //       (Id1(1) > Ids1) { i =>
  //         Id1(i + 1) > Ids1
  //       } { i =>
  //         called = true
  //         i + 1
  //       }
  //     assert(!called)
  //     assert(((a < Ids1): Int) == 3)
  //     assert(called)
  //   }
  // }

}

object A {
  import kyo.options._
  import kyo.tries._
  import kyo.envs._
  import kyo.aborts._
  import kyo.ios._
  val a: (String, Int, Int) > (Options | IOs | Tries) =
    for {
      i <- Options("1")
      j <- Tries(Integer.parseInt(i))
      k <- IOs(1)
    } yield (i, j, 1)

  val b = a < Options
  val c = b < IOs
  val d = c

}

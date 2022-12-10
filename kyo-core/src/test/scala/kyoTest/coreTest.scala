package kyoTest

import kyo.core._

class coreTest extends KyoTest {

  type Id[T] = T
  object Id {
    def apply[T](v: T): Id[T] = v
  }
  class Ids extends Effect[Id]
  val Ids = new Ids

  given ShallowHandler[Id, Ids] =
    new ShallowHandler[Id, Ids] {
      def pure[T](v: T)                                   = v
      def apply[T, U, S](m: Id[T], f: T => U > (S | Ids)) = f(m)
    }

  "suspend" - {
    "one" in {
      var called = false
      val a =
        (Id(1) > Ids) { i =>
          called = true
          i + 1
        }
      assert(!called)
      assert(((a < Ids): Int) == 2)
      assert(called)
    }
    "nested" in {
      var called = false
      val a =
        (Id(1) > Ids) { i =>
          Id(i + 1) > Ids
        } { i =>
          called = true
          i + 1
        }
      assert(!called)
      assert(((a < Ids): Int) == 3)
      assert(called)
    }
  }
}

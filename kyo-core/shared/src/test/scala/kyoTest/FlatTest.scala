package kyoTest
package kyoTest

import kyo._
import kyo.ios._
import kyo.options._
import kyo.concurrent.fibers._

class FlatTest extends KyoTest {

  // "ok" in {
  //   implicitly[Flat[Int, Any]]
  //   implicitly[Flat[Int, Options]]
  //   implicitly[Flat[Int, Nothing]]
  //   implicitly[Flat[Fiber[Int], Options]]
  //   succeed
  // }

  // "nok" in {
  //   def test1[T] = {
  //     assertDoesNotCompile("implicitly[Flat[T, Options]]")
  //     assertDoesNotCompile("implicitly[Flat[T, Any]]")
  //   }
  //   def test2[T](v: T > IOs)(implicit f: Flat[T, IOs]) = {}
  //   assertDoesNotCompile("test2(1: Int > Options)")
  //   assertDoesNotCompile("test2(1: Int > IOs > IOs)")
  //   assertDoesNotCompile("implicitly[Flat[Int > Options, Any]]")
  //   assertDoesNotCompile("implicitly[Flat[Int > Options, IOs]]")
  // }

  "a" in {
    def test1[T] = {
      implicitly[Flat[T]]
      implicitly[Flat[T > Options]]
    }
    implicitly[Flat[Int > IOs > IOs]]
    def test2[T](v: T > IOs)(implicit f: Flat[T > IOs]) = {}
    test2(1: Int > Options)
    def test3[T](implicit f: Flat[T]) =
      implicitly[Flat[T > IOs]]
    def test4[T](implicit f: Flat[T > Options]) =
      implicitly[Flat[T > IOs]]
    def test5[T] =
      implicitly[Flat[T > IOs]]
    succeed
  }

}

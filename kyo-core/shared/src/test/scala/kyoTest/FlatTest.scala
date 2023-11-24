package kyoTest
package kyoTest

import kyo._
import kyo.ios._
import kyo.options._
import kyo.concurrent.fibers._

class FlatTest extends KyoTest {

  "ok" - {
    "concrete" in {
      implicitly[Flat[Int]]
      implicitly[Flat[Int > Any]]
      implicitly[Flat[Int > Options]]
      implicitly[Flat[Int > Nothing]]
      succeed
    }
    "fiber" in {
      implicitly[Flat[Fiber[Int] > Options]]
      succeed
    }
    "by evidence" in {
      def test1[T: Flat] = {
        implicitly[Flat[T > IOs]]
        implicitly[Flat[T > Options]]
        implicitly[Flat[T > Any]]
      }
      succeed
    }
    "derived" in {
      def test2[T](implicit f: Flat[T > IOs]) = {
        implicitly[Flat[T]]
        implicitly[Flat[T > Options]]
        implicitly[Flat[T > Any]]
      }
      succeed
    }
  }

  "nok" - {

    "nested" in {
      assertDoesNotCompile("implicitly[Flat[Int > IOs > IOs]]")
      assertDoesNotCompile("implicitly[Flat[Any > IOs > IOs]]")
    }

    "nested w/ mismatch" in {
      assertDoesNotCompile("implicitly[Flat[Int > Options > IOs]]")
      assertDoesNotCompile("implicitly[Flat[Int > IOs > Options]]")
    }

    "generic" in {
      def test1[T] = {
        assertDoesNotCompile("implicitly[Flat[T]]")
        assertDoesNotCompile("implicitly[Flat[T > Options]]")
        assertDoesNotCompile("implicitly[Flat[T > Any]]")
      }
      test1[Int]
      succeed
    }

    "any" in {
      assertDoesNotCompile("implicitly[Flat[Any]]")
      assertDoesNotCompile("implicitly[Flat[Any > IOs]]")
    }
  }

}

package kyoTest

import kyo.core._
import kyo.options._
import org.scalatest.Args
import org.scalatest.Status

import scala.util.Failure
import scala.util.Success

class optionsTest extends KyoTest {

  "pure" - {
    "handle" in {
      checkEquals[Option[Int], Nothing](
          (1: Int > Options) < Options,
          Option(1)
      )
    }
    "handle + transform" in {
      checkEquals[Option[Int], Nothing](
          (1: Int > Options)(_ + 1) < Options,
          Option(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Option[Int], Nothing](
          (1: Int > Options)(i => Option(i + 1) > Options) < Options,
          Option(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Option[Int], Nothing](
          (1: Int > Options)(_ + 1)(i => Option(i + 1) > Options) < Options,
          Option(3)
      )
    }
  }

  "effectful" - {
    "handle" in {
      checkEquals[Option[Int], Nothing](
          Option(1) > Options < Options,
          Option(1)
      )
    }
    "handle + transform" in {
      checkEquals[Option[Int], Nothing](
          (Option(1) > Options)(_ + 1) < Options,
          Option(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Option[Int], Nothing](
          (Option(1) > Options)(i => Option(i + 1) > Options) < Options,
          Option(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Option[Int], Nothing](
          (Option(1) > Options)(_ + 1)(i => Option(i + 1) > Options) < Options,
          Option(3)
      )
    }
  }
}

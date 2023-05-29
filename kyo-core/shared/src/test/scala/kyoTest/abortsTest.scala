package kyoTest

import kyo.aborts._
import kyo._
import kyo.options._
import org.scalatest.Args
import org.scalatest.Status

import scala.util.Failure
import scala.util.Success
import scala.util.Try

class abortsTest extends KyoTest {

  class Ex1 extends RuntimeException
  class Ex2

  val ex1 = new Ex1
  val ex2 = new Ex2

  "pure" - {
    "handle" in {
      checkEquals[Either[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Right(1))),
          Right(1)
      )
    }
    "handle + transform" in {
      checkEquals[Either[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Right(1)).map(_ + 1)),
          Right(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Either[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Right(1)).map(i =>
            Aborts[Ex1].get(Right(i + 1))
          )),
          Right(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Either[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Right(1)).map(_ + 1).map(i =>
            Aborts[Ex1].get(Right(i + 1))
          )),
          Right(3)
      )
    }
    "handle + transform + failed effectful transform" in {
      val fail = Left[Ex1, Int](ex1)
      checkEquals[Either[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Right(1)).map(_ + 1).map(_ =>
            Aborts[Ex1].get(fail)
          )),
          fail
      )
    }
  }

  "effectful" - {
    "success" - {
      val v = Aborts[Ex1].get(Right(1))
      "handle" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v),
            Right(1)
        )
      }
      "handle + transform" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1)),
            Right(2)
        )
      }
      "handle + effectful transform" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(i => Aborts[Ex1].get(Right(i + 1)))),
            Right(2)
        )
      }
      "handle + transform + effectful transform" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1).map(i => Aborts[Ex1].get(Right(i + 1)))),
            Right(3)
        )
      }
      "handle + transform + failed effectful transform" in {
        val fail = Left[Ex1, Int](ex1)
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1).map(_ => Aborts[Ex1].get(fail))),
            fail
        )
      }
    }
    "failure" - {
      val v: Int > Aborts[Ex1] = Aborts[Ex1].get(Left(ex1))
      "handle" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v),
            Left(ex1)
        )
      }
      "handle + transform" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1)),
            Left(ex1)
        )
      }
      "handle + effectful transform" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(i => Aborts[Ex1].get(Right(i + 1)))),
            Left(ex1)
        )
      }
      "handle + transform + effectful transform" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1).map(i => Aborts[Ex1].get(Right(i + 1)))),
            Left(ex1)
        )
      }
      "handle + transform + failed effectful transform" in {
        val fail = Left[Ex1, Int](ex1)
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1).map(_ => Aborts[Ex1].get(fail))),
            fail
        )
      }
    }
  }

  "Aborts" - {
    def test(v: Int): Int > Aborts[Ex1] =
      v.map {
        case 0 => Aborts(ex1)
        case i => 10 / i
      }
    "run" - {
      "success" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(test(2)),
            Right(5)
        )
      }
      "failure" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].run(test(0)),
            Left(ex1)
        )
      }
    }
    "catching" - {
      "only effect" - {
        def test(v: Int): Int =
          v.map {
            case 0 => throw ex1
            case i => 10 / i
          }
        "success" in {
          checkEquals[Either[Ex1, Int], Any](
              Aborts[Ex1].run(Aborts[Ex1].catching(test(2))),
              Right(5)
          )
        }
        "failure" in {
          checkEquals[Either[Ex1, Int], Any](
              Aborts[Ex1].run(Aborts[Ex1].catching(test(0))),
              Left(ex1)
          )
        }
        "subclass" in {
          checkEquals[Either[RuntimeException, Int], Any](
              Aborts[RuntimeException].run(Aborts[RuntimeException].catching(test(0))),
              Left(ex1)
          )
        }
      }
      "with other effect" - {
        def test(v: Int > Options): Int > Options =
          v.map {
            case 0 => throw ex1
            case i => 10 / i
          }
        "success" in {
          checkEquals[Option[Either[Ex1, Int]], Any](
              Options.run(Aborts[Ex1].run(Aborts[Ex1].catching(test(Options.get(Option(2)))))),
              Some(Right(5))
          )
        }
        "failure" in {
          checkEquals[Option[Either[Ex1, Int]], Any](
              Options.run(Aborts[Ex1].run(Aborts[Ex1].catching(test(Options.get(Option(0)))))),
              Some(Left(ex1))
          )
        }
      }
    }
  }
}

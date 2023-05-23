package kyoTest

import kyo.aborts._
import kyo._
import kyo.core._
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

  "abort" - {
    "toOption" in {
      assert(Abort.success(1).toOption == Option(1))
      assert(Abort.failure(ex1).toOption == None)
    }
    "toEither" in {
      assert(Abort.success(1).toEither == Right(1))
      assert(Abort.failure(ex1).toEither == Left(ex1))
    }
    "transform toOption" in {
      checkEquals[Option[Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.success(1))).map(_.toOption),
          Option(1)
      )
      checkEquals[Option[Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.failure[Ex1, Int](ex1))).map(_.toOption),
          None
      )
    }
    "transform toEither" in {
      checkEquals[Either[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.success(1))).map(_.toEither),
          Right(1)
      )
      checkEquals[Either[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.failure[Ex1, Int](ex1))).map(_.toEither),
          Left(ex1)
      )
    }
  }

  "pure" - {
    "handle" in {
      checkEquals[Abort[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.success(1))),
          Abort.success(1)
      )
    }
    "handle + transform" in {
      checkEquals[Abort[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.success(1)).map(_ + 1)),
          Abort.success(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Abort[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.success(1)).map(i =>
            Aborts[Ex1].get(Abort.success(i + 1))
          )),
          Abort.success(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Abort[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.success(1)).map(_ + 1).map(i =>
            Aborts[Ex1].get(Abort.success(i + 1))
          )),
          Abort.success(3)
      )
    }
    "handle + transform + failed effectful transform" in {
      val fail = Abort.failure[Ex1, Int](ex1)
      checkEquals[Abort[Ex1, Int], Any](
          Aborts[Ex1].run(Aborts[Ex1].get(Abort.success(1)).map(_ + 1).map(_ =>
            Aborts[Ex1].get(fail)
          )),
          fail
      )
    }
  }

  "effectful" - {
    "success" - {
      val v = (Abort.success(1) > Aborts[Ex1])
      "handle" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v),
            Abort.success(1)
        )
      }
      "handle + transform" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1)),
            Abort.success(2)
        )
      }
      "handle + effectful transform" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(i => Aborts[Ex1].get(Abort.success(i + 1)))),
            Abort.success(2)
        )
      }
      "handle + transform + effectful transform" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1).map(i => Aborts[Ex1].get(Abort.success(i + 1)))),
            Abort.success(3)
        )
      }
      "handle + transform + failed effectful transform" in {
        val fail = Abort.failure[Ex1, Int](ex1)
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1).map(_ => Aborts[Ex1].get(fail))),
            fail
        )
      }
    }
    "failure" - {
      val v: Int > Aborts[Ex1] = (Abort.failure(ex1) > Aborts[Ex1])
      "handle" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v),
            Abort.failure(ex1)
        )
      }
      "handle + transform" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1)),
            Abort.failure(ex1)
        )
      }
      "handle + effectful transform" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(i => Aborts[Ex1].get(Abort.success(i + 1)))),
            Abort.failure(ex1)
        )
      }
      "handle + transform + effectful transform" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(v.map(_ + 1).map(i => Aborts[Ex1].get(Abort.success(i + 1)))),
            Abort.failure(ex1)
        )
      }
      "handle + transform + failed effectful transform" in {
        val fail = Abort.failure[Ex1, Int](ex1)
        checkEquals[Abort[Ex1, Int], Any](
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
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(test(2)),
            Abort.success(5)
        )
      }
      "failure" in {
        checkEquals[Abort[Ex1, Int], Any](
            Aborts[Ex1].run(test(0)),
            Abort.failure(ex1)
        )
      }
    }
    "toOption" - {
      "success" in {
        checkEquals[Option[Int], Any](
            Aborts[Ex1].toOption(test(2)),
            Some(5)
        )
      }
      "failure" in {
        checkEquals[Option[Int], Any](
            Aborts[Ex1].toOption(test(0)),
            None
        )
      }
    }
    "toEither" - {
      "success" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].toEither(test(2)),
            Right(5)
        )
      }
      "failure" in {
        checkEquals[Either[Ex1, Int], Any](
            Aborts[Ex1].toEither(test(0)),
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
          checkEquals[Abort[Ex1, Int], Any](
              Aborts[Ex1].run(Aborts[Ex1].catching(test(2))),
              Abort.success(5)
          )
        }
        "failure" in {
          checkEquals[Abort[Ex1, Int], Any](
              Aborts[Ex1].run(Aborts[Ex1].catching(test(0))),
              Abort.failure(ex1)
          )
        }
        "subclass" in {
          checkEquals[Abort[RuntimeException, Int], Any](
              Aborts[RuntimeException].run(Aborts[RuntimeException].catching(test(0))),
              Abort.failure(ex1)
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
          checkEquals[Option[Abort[Ex1, Int]], Any](
              Options.run(Aborts[Ex1].run(Aborts[Ex1].catching(test(Option(2) > Options)))),
              Some(Abort.success(5))
          )
        }
        "failure" in {
          checkEquals[Option[Abort[Ex1, Int]], Any](
              Options.run(Aborts[Ex1].run(Aborts[Ex1].catching(test(Option(0) > Options)))),
              Some(Abort.failure(ex1))
          )
        }
      }
    }
  }
}

package kyoTest

import kyo.aborts._
import kyo.core
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
      checkEquals[Option[Int], Nothing](
          (Abort.success(1) > Aborts[Ex1] < Aborts[Ex1])(_.toOption),
          Option(1)
      )
      checkEquals[Option[Int], Nothing](
          (Abort.failure[Ex1, Int](ex1) > Aborts[Ex1] < Aborts[Ex1])(_.toOption),
          None
      )
    }
    "transform toEither" in {
      checkEquals[Either[Ex1, Int], Nothing](
          (Abort.success(1) > Aborts[Ex1] < Aborts[Ex1])(_.toEither),
          Right(1)
      )
      checkEquals[Either[Ex1, Int], Nothing](
          (Abort.failure[Ex1, Int](ex1) > Aborts[Ex1] < Aborts[Ex1])(_.toEither),
          Left(ex1)
      )
    }
  }

  "pure" - {
    "handle" in {
      checkEquals[Abort[Ex1, Int], Nothing](
          (1: Int > Aborts[Ex1]) < Aborts[Ex1],
          Abort.success(1)
      )
    }
    "handle + transform" in {
      checkEquals[Abort[Ex1, Int], Nothing](
          (1: Int > Aborts[Ex1])(_ + 1) < Aborts[Ex1],
          Abort.success(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Abort[Ex1, Int], Nothing](
          (1: Int > Aborts[Ex1])(i => Abort.success(i + 1) > Aborts[Ex1]) < Aborts[Ex1],
          Abort.success(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Abort[Ex1, Int], Nothing](
          (1: Int > Aborts[Ex1])(_ + 1)(i => Abort.success(i + 1) > Aborts[Ex1]) < Aborts[Ex1],
          Abort.success(3)
      )
    }
    "handle + transform + failed effectful transform" in {
      val e = new Exception
      checkEquals[Abort[Ex1, Int], Nothing](
          (1: Int > Aborts[Ex1])(_ + 1)(_ => Abort.failure(ex1) > Aborts[Ex1]) < Aborts[Ex1],
          Abort.failure[Ex1, Int](ex1)
      )
    }
  }

  "effectful" - {
    "success" - {
      val v = (Abort.success(1) > Aborts[Ex1])
      "handle" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            v < Aborts[Ex1],
            Abort.success(1)
        )
      }
      "handle + transform" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            v(_ + 1) < Aborts[Ex1],
            Abort.success(2)
        )
      }
      "handle + effectful transform" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            v(i => Abort.success(i + 1) > Aborts[Ex1]) < Aborts[Ex1],
            Abort.success(2)
        )
      }
      "handle + transform + effectful transform" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            v(_ + 1)(i => Abort.success(i + 1) > Aborts[Ex1]) < Aborts[Ex1],
            Abort.success(3)
        )
      }
      "handle + transform + failed effectful transform" in {
        val e = new Exception
        checkEquals[Abort[Ex1, Int], Nothing](
            v(_ + 1)(_ => Abort.failure(ex1) > Aborts[Ex1]) < Aborts[Ex1],
            Abort.failure(ex1)
        )
      }
    }
    "failure" - {
      val v: Int > Aborts[Ex1] = (Abort.failure(ex1) > Aborts[Ex1])
      "handle" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            v < Aborts[Ex1],
            Abort.failure(ex1)
        )
      }
      "handle + transform" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            v(_ + 1) < Aborts[Ex1],
            Abort.failure(ex1)
        )
      }
      "handle + effectful transform" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            v(i => Abort.success(i + 1) > Aborts[Ex1]) < Aborts[Ex1],
            Abort.failure(ex1)
        )
      }
      "handle + transform + effectful transform" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            v(_ + 1)(i => Abort.success(i + 1) > Aborts[Ex1]) < Aborts[Ex1],
            Abort.failure(ex1)
        )
      }
      "handle + transform + failed effectful transform" in {
        val e = new Exception
        checkEquals[Abort[Ex1, Int], Nothing](
            v(_ + 1)(_ => Abort.failure(ex1) > Aborts[Ex1]) < Aborts[Ex1],
            Abort.failure(ex1)
        )
      }
    }
  }

  "multiple aborts" - {
    def test(v: Int): Int > (Aborts[Ex1] | Aborts[Ex2]) =
      v {
        case 0 => Aborts(ex1)
        case 1 => Aborts(ex2)
        case i => 10 / i
      }
    "handle all" in {
      checkEquals[Abort[Ex1, Int], Nothing](
          test(0) < Aborts[Ex1 | Ex2],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex1, Int], Nothing](
          test(1) < Aborts[Ex1 | Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Int], Nothing](
          test(2) < Aborts[Ex1 | Ex2],
          Abort.success(5)
      )
    }
    "handle ex1" in {
      checkEquals[Abort[Ex1, Int], Aborts[Ex2]](
          test(0) < Aborts[Ex1],
          Abort.failure(ex1)
      )
      checkNotEquals[Abort[Ex1, Int], Aborts[Ex2]](
          test(1) < Aborts[Ex1],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Int], Aborts[Ex2]](
          test(2) < Aborts[Ex1],
          Abort.success(5)
      )
    }
    "handle ex2" in {
      checkNotEquals[Abort[Ex2, Int], Aborts[Ex1]](
          test(0) < Aborts[Ex2],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex2, Int], Aborts[Ex1]](
          test(1) < Aborts[Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex2, Int], Aborts[Ex1]](
          test(2) < Aborts[Ex2],
          Abort.success(5)
      )
    }
    "handle ex1 then ex2" in {
      checkEquals[Abort[Ex2, Abort[Ex1, Int]], Nothing](
          test(0) < Aborts[Ex1] < Aborts[Ex2],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex2, Abort[Ex1, Int]], Nothing](
          test(1) < Aborts[Ex1] < Aborts[Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex2, Abort[Ex1, Int]], Nothing](
          test(2) < Aborts[Ex1] < Aborts[Ex2],
          Abort.success(5)
      )
    }
    "handle ex2 then ex1" in {
      checkEquals[Abort[Ex1, Abort[Ex2, Int]], Nothing](
          test(0) < Aborts[Ex2] < Aborts[Ex1],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex1, Abort[Ex2, Int]], Nothing](
          test(1) < Aborts[Ex2] < Aborts[Ex1],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Abort[Ex2, Int]], Nothing](
          test(2) < Aborts[Ex2] < Aborts[Ex1],
          Abort.success(5)
      )
    }
  }

  "squashed aborts effect remain present if not fully handled" - {
    def test(v: Int): Int > Aborts[Ex1 | Ex2] =
      v {
        case 0 => Aborts(ex1)
        case 1 => Aborts(ex2)
        case i => 10 / i
      }
    "handle all" in {
      checkEquals[Abort[Ex1, Int], Nothing](
          test(0) < Aborts[Ex1 | Ex2],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex1, Int], Nothing](
          test(1) < Aborts[Ex1 | Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Int], Nothing](
          test(2) < Aborts[Ex1 | Ex2],
          Abort.success(5)
      )
    }
    "handle ex1" in {
      checkEquals[Abort[Ex1, Int], Aborts[Ex1 | Ex2]](
          test(0) < Aborts[Ex1],
          Abort.failure(ex1)
      )
      checkNotEquals[Abort[Ex1, Int], Aborts[Ex1 | Ex2]](
          test(1) < Aborts[Ex1],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Int], Aborts[Ex1 | Ex2]](
          test(2) < Aborts[Ex1],
          Abort.success(5)
      )
    }
    "handle ex2" in {
      checkNotEquals[Abort[Ex2, Int], Aborts[Ex1 | Ex2]](
          test(0) < Aborts[Ex2],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex2, Int], Aborts[Ex1 | Ex2]](
          test(1) < Aborts[Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex2, Int], Aborts[Ex1 | Ex2]](
          test(2) < Aborts[Ex2],
          Abort.success(5)
      )
    }
    "handle ex1 then ex2" in {
      checkEquals[Abort[Ex2, Abort[Ex1, Int]], Aborts[Ex1 | Ex2]](
          test(0) < Aborts[Ex1] < Aborts[Ex2],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex2, Abort[Ex1, Int]], Aborts[Ex1 | Ex2]](
          test(1) < Aborts[Ex1] < Aborts[Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex2, Abort[Ex1, Int]], Aborts[Ex1 | Ex2]](
          test(2) < Aborts[Ex1] < Aborts[Ex2],
          Abort.success(5)
      )
    }
    "handle ex2 then ex1" in {
      checkEquals[Abort[Ex1, Abort[Ex2, Int]], Aborts[Ex1 | Ex2]](
          test(0) < Aborts[Ex2] < Aborts[Ex1],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex1, Abort[Ex2, Int]], Aborts[Ex1 | Ex2]](
          test(1) < Aborts[Ex2] < Aborts[Ex1],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Abort[Ex2, Int]], Aborts[Ex1 | Ex2]](
          test(2) < Aborts[Ex2] < Aborts[Ex1],
          Abort.success(5)
      )
    }
  }

  "opaque squashed aborts must be fully handled" - {
    def test(v: Int): Int > Aborts[Ex1 | Ex2] =
      v {
        case 0 => Aborts(ex1: (Ex1 | Ex2))
        case 1 => Aborts(ex2: (Ex1 | Ex2))
        case i => 10 / i
      }
    "handle all" in {
      checkEquals[Abort[Ex1, Int], Nothing](
          test(0) < Aborts[Ex1 | Ex2],
          Abort.failure(ex1)
      )
      checkEquals[Abort[Ex1, Int], Nothing](
          test(1) < Aborts[Ex1 | Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Int], Nothing](
          test(2) < Aborts[Ex1 | Ex2],
          Abort.success(5)
      )
    }
    "handle ex1" in {
      checkNotEquals[Abort[Ex1, Int], Aborts[Ex1 | Ex2]](
          test(0) < Aborts[Ex1],
          Abort.failure(ex1)
      )
      checkNotEquals[Abort[Ex1, Int], Aborts[Ex1 | Ex2]](
          test(1) < Aborts[Ex1],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Int], Aborts[Ex1 | Ex2]](
          test(2) < Aborts[Ex1],
          Abort.success(5)
      )
    }
    "handle ex2" in {
      checkNotEquals[Abort[Ex2, Int], Aborts[Ex1 | Ex2]](
          test(0) < Aborts[Ex2],
          Abort.failure(ex1)
      )
      checkNotEquals[Abort[Ex2, Int], Aborts[Ex1 | Ex2]](
          test(1) < Aborts[Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex2, Int], Aborts[Ex1 | Ex2]](
          test(2) < Aborts[Ex2],
          Abort.success(5)
      )
    }
    "handle ex1 then ex2" in {
      checkNotEquals[Abort[Ex2, Abort[Ex1, Int]], Aborts[Ex1 | Ex2]](
          test(0) < Aborts[Ex1] < Aborts[Ex2],
          Abort.failure(ex1)
      )
      checkNotEquals[Abort[Ex2, Abort[Ex1, Int]], Aborts[Ex1 | Ex2]](
          test(1) < Aborts[Ex1] < Aborts[Ex2],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex2, Abort[Ex1, Int]], Aborts[Ex1 | Ex2]](
          test(2) < Aborts[Ex1] < Aborts[Ex2],
          Abort.success(5)
      )
    }
    "handle ex2 then ex1" in {
      checkNotEquals[Abort[Ex1, Abort[Ex2, Int]], Aborts[Ex1 | Ex2]](
          test(0) < Aborts[Ex2] < Aborts[Ex1],
          Abort.failure(ex1)
      )
      checkNotEquals[Abort[Ex1, Abort[Ex2, Int]], Aborts[Ex1 | Ex2]](
          test(1) < Aborts[Ex2] < Aborts[Ex1],
          Abort.failure(ex2)
      )
      checkEquals[Abort[Ex1, Abort[Ex2, Int]], Aborts[Ex1 | Ex2]](
          test(2) < Aborts[Ex2] < Aborts[Ex1],
          Abort.success(5)
      )
    }
  }

  "Aborts" - {
    def test(v: Int): Int > Aborts[Ex1] =
      v {
        case 0 => Aborts(ex1)
        case i => 10 / i
      }
    "run" - {
      "success" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            Aborts[Ex1].run(test(2)),
            Abort.success(5)
        )
      }
      "failure" in {
        checkEquals[Abort[Ex1, Int], Nothing](
            Aborts[Ex1].run(test(0)),
            Abort.failure(ex1)
        )
      }
    }
    "toOption" - {
      "success" in {
        checkEquals[Option[Int], Nothing](
            Aborts[Ex1].toOption(test(2)),
            Some(5)
        )
      }
      "failure" in {
        checkEquals[Option[Int], Nothing](
            Aborts[Ex1].toOption(test(0)),
            None
        )
      }
    }
    "toEither" - {
      "success" in {
        checkEquals[Either[Ex1, Int], Nothing](
            Aborts[Ex1].toEither(test(2)),
            Right(5)
        )
      }
      "failure" in {
        checkEquals[Either[Ex1, Int], Nothing](
            Aborts[Ex1].toEither(test(0)),
            Left(ex1)
        )
      }
    }
    "catching" - {
      "only effect" - {
        def test(v: Int): Int =
          v {
            case 0 => throw ex1
            case i => 10 / i
          }
        "success" in {
          checkEquals[Abort[Ex1, Int], Nothing](
              Aborts[Ex1].run(Aborts[Ex1].catching(test(2))),
              Abort.success(5)
          )
        }
        "failure" in {
          checkEquals[Abort[Ex1, Int], Nothing](
              Aborts[Ex1].run(Aborts[Ex1].catching(test(0))),
              Abort.failure(ex1)
          )
        }
        "subclass" in {
          checkEquals[Abort[Ex1, Int], Nothing](
              Aborts[RuntimeException].run(Aborts[RuntimeException].catching(test(0))),
              Abort.failure(ex1)
          )
        }
      }
      "with other effect" - {
        def test(v: Int > Options): Int > Options =
          v {
            case 0 => throw ex1
            case i => 10 / i
          }
        "success" in {
          checkEquals[Option[Abort[Ex1, Int]], Nothing](
              Options.run(Aborts[Ex1].run(Aborts[Ex1].catching(test(Option(2) > Options)))),
              Some(Abort.success(5))
          )
        }
        "failure" in {
          checkEquals[Option[Abort[Ex1, Int]], Nothing](
              Options.run(Aborts[Ex1].run(Aborts[Ex1].catching(test(Option(0) > Options)))),
              Some(Abort.failure(ex1))
          )
        }
      }
    }
  }
}

package kyoTest

import kyo._
import kyo.options._
import org.scalatest.Args
import org.scalatest.Status

import scala.util.Failure
import scala.util.Success
import kyo.tries.Tries
import scala.util.Try

class optionsTest extends KyoTest {

  "apply" - {
    "null" in {
      assert(((Options.run(Options(null: String))): Option[String]) == None)
      checkEquals[Option[String], Nothing](
          Options(null: String) < Options,
          None
      )
    }
    "value" in {
      checkEquals[Option[String], Nothing](
          Options("hi") < Options,
          Option("hi")
      )
    }
  }

  "pure" - {
    "handle" in {
      checkEquals[Option[Int], Nothing](
          (1: Int > Options) < Options,
          Option(1)
      )
    }
    "handle + transform" in {
      checkEquals[Option[Int], Nothing](
          (1: Int > Options).map(_ + 1) < Options,
          Option(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Option[Int], Nothing](
          (1: Int > Options).map(i => Options.get(Option(i + 1))) < Options,
          Option(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Option[Int], Nothing](
          (1: Int > Options).map(_ + 1).map(i => Options.get(Option(i + 1))) < Options,
          Option(3)
      )
    }
  }

  "effectful" - {
    "handle" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.get(Option(1))),
          Option(1)
      )
    }
    "handle + transform" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.get(Option(1)).map(_ + 1)),
          Option(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.get(Option(1)).map(i => Options.get(Option(i + 1)))),
          Option(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.get(Option(1)).map(_ + 1).map(i => Options.get(Option(i + 1)))),
          Option(3)
      )
    }
  }

  "Options.run" - {
    "pure" in {
      checkEquals[Option[Int], Nothing](
          Options.run(1: Int > Options),
          Option(1)
      )
    }
    "not empty" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.get(Option(1))),
          Option(1)
      )
    }
    "empty" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.get(Option.empty[Int])),
          None
      )
    }
  }

  "orElse" - {
    "empty" in {
      checkEquals[Option[Int], Nothing](
          Options.orElse[Int, Nothing]() < Options,
          None
      )
    }
    "not empty" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.orElse(Options.get(Option(1)))),
          Option(1)
      )
    }
    "not empty + empty" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.orElse(Options.get(Option(1)), Options.get(Option.empty[Int]))),
          Option(1)
      )
    }
    "empty + not empty" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.orElse(Options.get(Option.empty[Int]), Options.get(Option(1)))),
          Option(1)
      )
    }
    "empty + empty" in {
      checkEquals[Option[Int], Nothing](
          Options.orElse(Options.get(Option.empty[Int]), Options.get(Option.empty[Int])) < Options,
          None
      )
    }
    "not empty + not empty" in {
      checkEquals[Option[Int], Nothing](
          Options.orElse(Options.get(Option(1)), Options.get(Option(2))) < Options,
          Option(1)
      )
    }
    "not empty + not empty + not empty" in {
      checkEquals[Option[Int], Nothing](
          Options.run(Options.orElse(
              Options.get(Option(1)),
              Options.get(Option(2)),
              Options.get(Option(3))
          )),
          Option(1)
      )
    }
    "not empty + not empty + not empty + not empty" in {
      checkEquals[Option[Int], Nothing](
          Options.orElse(
              Options.get(Option(1)),
              Options.get(Option(2)),
              Options.get(Option(3)),
              Options.get(Option(4))
          ) < Options,
          Option(1)
      )
    }
    "not empty + not empty + not empty + not empty + not empty" in {
      checkEquals[Option[Int], Nothing](
          Options.orElse(
              Options.get(Option(1)),
              Options.get(Option(2)),
              Options.get(Option(3)),
              Options.get(Option(4)),
              Options.get(Option(5))
          ) < Options,
          Option(1)
      )
    }
  }

  "getOrElse" - {
    "empty" in {
      checkEquals[Int, Nothing](
          Options.getOrElse(Option.empty[Int], 1),
          1
      )
    }
    "not empty" in {
      checkEquals[Int, Nothing](
          Options.getOrElse(Some(2), 1),
          2
      )
    }
    "or fail" in {
      val e              = Exception()
      val a: Int > Tries = Options.getOrElse(Option.empty[Int], Tries.fail("fail"))
      checkEquals[Try[Int], Nothing](
          Tries.run(Options.getOrElse(Option.empty[Int], Tries.fail(e))),
          Failure(e)
      )
      checkEquals[Try[Int], Nothing](
          Tries.run(Options.getOrElse(Some(1), Tries.fail(e))),
          Success(1)
      )
    }
  }
}

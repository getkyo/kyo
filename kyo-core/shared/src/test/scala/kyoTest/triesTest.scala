package kyoTest

import kyo._
import kyo.options._
import kyo.tries._
import org.scalatest.Args
import org.scalatest.Status

import scala.util.Failure
import scala.util.Success
import scala.util.Try

class triesTest extends KyoTest {

  val e = new Exception

  "apply" - {
    "failure" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(Tries((throw e): Int)),
          Failure(e)
      )
    }
    "success" in {
      checkEquals[Try[Int], Nothing](
          Tries(1) < Tries,
          Success(1)
      )
    }
  }

  "run" - {
    "failure" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(Tries((throw e): Int)),
          Failure(e)
      )
    }
    "success" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(Tries(1)),
          Success(1)
      )
    }
  }

  "get" - {
    "failure" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(Tries.get(Failure[Int](e))),
          Failure(e)
      )
    }
    "success" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(Tries.get(Success(1))),
          Success(1)
      )
    }
  }

  "fail" in {
    checkEquals[Try[Int], Nothing](
        Tries.run(Tries.fail[Int](e)),
        Failure(e)
    )
  }

  "pure" - {
    "handle" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(1: Int > Tries),
          Try(1)
      )
    }
    "handle + transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run((1: Int > Tries).map(_ + 1)),
          Try(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run((1: Int > Tries).map(i => Tries.get(Try(i + 1)))),
          Try(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run((1: Int > Tries).map(_ + 1).map(i => Tries.get(Try(i + 1)))),
          Try(3)
      )
    }
    "handle + transform + failed effectful transform" in {
      val e = new Exception
      checkEquals[Try[Int], Nothing](
          Tries.run((1: Int > Tries).map(_ + 1).map(i => Tries.get(Try[Int](throw e)))),
          Failure(e)
      )
    }
  }

  "effectful" - {
    "handle" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(Tries.get(Try(1))),
          Try(1)
      )
    }
    "handle + transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(Tries.get(Try(1)).map(_ + 1)),
          Try(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run(Tries.get(Try(1)).map(i => Tries.get(Try(i + 1)))),
          Try(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run((Tries.get(Try(1))).map(_ + 1).map(i => Tries.get(Try(i + 1)))),
          Try(3)
      )
    }
    "handle + failed transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run((Tries.get(Try(1))).map(_ => (throw e): Int)),
          Failure(e)
      )
    }
    "handle + transform + effectful transform + failed transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run((Tries.get(Try(1))).map(_ + 1).map(i => Tries.get(Try(i + 1))).map(_ =>
            (throw e): Int
          )),
          Failure(e)
      )
    }
    "handle + transform + failed effectful transform" in {
      checkEquals[Try[Int], Nothing](
          Tries.run((Tries.get(Try(1))).map(_ + 1).map(i => Tries.get(Try((throw e): Int)))),
          Failure(e)
      )
    }
    "nested effect + failure" in {
      checkEquals[Option[Try[Int]], Nothing](
          Tries.get(Try(Option(1))).map(opt =>
            ((opt: Option[Int] > Tries) > Options).map(_ => (throw e): Int)
          ) < Tries < Options,
          Some(Failure(e))
      )
    }
  }
}

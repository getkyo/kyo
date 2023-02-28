package kyoTest

import kyo.core._
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
          Tries((throw e): Int) < Tries,
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
          Tries.get(Failure[Int](e)) < Tries,
          Failure(e)
      )
    }
    "success" in {
      checkEquals[Try[Int], Nothing](
          Tries.get(Success(1)) < Tries,
          Success(1)
      )
    }
  }

  "pure" - {
    "handle" in {
      checkEquals[Try[Int], Nothing](
          (1: Int > Tries) < Tries,
          Try(1)
      )
    }
    "handle + transform" in {
      checkEquals[Try[Int], Nothing](
          (1: Int > Tries)(_ + 1) < Tries,
          Try(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Try[Int], Nothing](
          (1: Int > Tries)(i => Try(i + 1) > Tries) < Tries,
          Try(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Try[Int], Nothing](
          (1: Int > Tries)(_ + 1)(i => Try(i + 1) > Tries) < Tries,
          Try(3)
      )
    }
    "handle + transform + failed effectful transform" in {
      val e = new Exception
      checkEquals[Try[Int], Nothing](
          (1: Int > Tries)(_ + 1)(i => Try[Int](throw e) > Tries) < Tries,
          Failure(e)
      )
    }
  }

  "effectful" - {
    "handle" in {
      checkEquals[Try[Int], Nothing](
          Try(1) > Tries < Tries,
          Try(1)
      )
    }
    "handle + transform" in {
      checkEquals[Try[Int], Nothing](
          (Try(1) > Tries)(_ + 1) < Tries,
          Try(2)
      )
    }
    "handle + effectful transform" in {
      checkEquals[Try[Int], Nothing](
          (Try(1) > Tries)(i => Try(i + 1) > Tries) < Tries,
          Try(2)
      )
    }
    "handle + transform + effectful transform" in {
      checkEquals[Try[Int], Nothing](
          (Try(1) > Tries)(_ + 1)(i => Try(i + 1) > Tries) < Tries,
          Try(3)
      )
    }
    "handle + failed transform" in {
      checkEquals[Try[Int], Nothing](
          (Try(1) > Tries)(_ => (throw e): Int) < Tries,
          Failure(e)
      )
    }
    "handle + transform + effectful transform + failed transform" in {
      checkEquals[Try[Int], Nothing](
          (Try(1) > Tries)(_ + 1)(i => Try(i + 1) > Tries)(_ => (throw e): Int) < Tries,
          Failure(e)
      )
    }
    "handle + transform + failed effectful transform" in {
      checkEquals[Try[Int], Nothing](
          (Try(1) > Tries)(_ + 1)(i => Try((throw e): Int) > Tries) < Tries,
          Failure(e)
      )
    }
    "nested effect + failure" in {
      checkEquals[Option[Try[Int]], Nothing](
          (Try(Option(1)) > Tries)(opt =>
            ((opt: Option[Int] > Tries) > Options)(_ => (throw e): Int)
          ) < Tries < Options,
          Some(Failure(e))
      )
    }
  }
}

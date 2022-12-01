package kyoTest

import kyo.core._
import kyo.futures._
import kyo.options._

import java.util.concurrent.TimeoutException
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Try

class futuresTest extends KyoTest {

  given [T]: Eq[Future[T]] = (a, b) =>
    Try(Await.result(a, Duration.Inf)) == Try(Await.result(b, Duration.Inf))

  val ex = new Exception

  "plain future" - {
    "success" in {
      checkEquals[Future[Int], Nothing](
          (Future(1) > Futures)(_ + 1) << Futures,
          Future.successful(2)
      )
    }
    "failure" in {
      checkEquals[Future[Int], Nothing](
          (Future.failed[Int](ex) > Futures)(_ + 1) << Futures,
          Future.failed[Int](ex)
      )
    }
  }

  "fork" - {
    "success" in {
      checkEquals[Future[Int], Nothing](
          Futures.fork(1)(_ + 1) << Futures,
          Future.successful(2)
      )
    }
    "failure" in {
      checkEquals[Future[Int], Nothing](
          Futures.fork[Int, Nothing](throw ex)(_ + 1) << Futures,
          Future.failed[Int](ex)
      )
    }
    "effectful" in {
      checkEquals[Future[Option[Int]], Nothing](
          Futures.fork(Option(1) > Options)(_ + 1) < Options << Futures,
          Future.successful(Option(2))
      )
    }
    "fork then failed transform" in {
      checkEquals[Future[Int], Nothing](
          Futures.fork(1)(_ => (throw ex): Int) << Futures,
          Future.failed(ex)
      )
    }
    "fork with failed transform" in {
      checkEquals[Future[Option[Int]], Nothing](
          Futures.fork((Option(1) > Options)(_ => (throw ex): Int)) < Options << Futures,
          Future.failed(ex)
      )
    }
  }

  "block" - {
    "without timeout" - {
      "success" in {
        checkEquals[Int, Nothing](
            Futures.block(Futures.fork(1)(_ + 1), Duration.Inf),
            2
        )
      }
      "failure" in {
        val r = Try(Futures.block(Futures.fork[Int, Nothing](throw ex)(_ + 1), Duration.Inf))
        assert(r == Failure(ex))
      }
      "effectful" in {
        checkEquals[Option[Int], Nothing](
            Futures.block(Futures.fork(Option(1) > Options)(_ + 1), Duration.Inf) < Options,
            Some(2)
        )
      }
    }
    "with timeout" - {
      "success" in {
        checkEquals[Int, Nothing](
            Futures.block(Futures.fork(1)(_ + 1), 1.second),
            2
        )
      }
      "failure" in {
        val r = Try(Futures.block(Futures.fork[Int, Nothing](throw ex)(_ + 1), 1.second))
        assert(r == Failure(ex))
      }
      "effectful" in {
        checkEquals[Option[Int], Nothing](
            Futures.block(Futures.fork(Option(1) > Options)(_ + 1), 1.second) < Options,
            Some(2)
        )
      }
      "timeout failure" in {
        assertThrows[TimeoutException] {
          Futures.block(Future(Thread.sleep(100)) > Futures, 10.millis)
        }
      }
      "acc timeout failure" in {
        var done = false
        def loop(): Unit > Futures =
          if (done) ()
          else (Future(Thread.sleep(1)) > Futures)(_ => loop())
        assertThrows[TimeoutException] {
          Futures.block(loop(), 100.millis)
        }
        done = true
      }
      "effectful acc timeout failure" in {
        var done = false
        def loop(i: Int): Unit > (Futures | Options) =
          if (done) ()
          else if (i % 10 == 0) (Option(i - 1) > Options)(loop)
          else (Future(Thread.sleep(1)) > Futures)(_ => loop(i - 1))
        assertThrows[TimeoutException] {
          Futures.block(loop(100000) < Options, 100.millis)
        }
        done = true
      }
    }
  }
}

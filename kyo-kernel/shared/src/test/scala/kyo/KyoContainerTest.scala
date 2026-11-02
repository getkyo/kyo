package kyo

import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** The `Kyo` operations over `Maybe`, `Result`, `Option`, `Try`, `Either`, and `Either.LeftProjection`. */
class KyoContainerTest extends Test:

    import KyoForeachTest.*

    given [A, B]: CanEqual[Try[A], Try[B]]                   = CanEqual.derived
    given [A, B]: CanEqual[Throwable, Throwable]             = CanEqual.derived
    given [A, B, C, D]: CanEqual[Either[A, B], Either[C, D]] = CanEqual.derived

    "Maybe" - {
        val present: Maybe[Int] = Maybe(1)
        val absent: Maybe[Int]  = Maybe.empty

        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(present)(i => TestEffect1(i))).eval == Maybe(2))
            assert(TestEffect1.run(Kyo.foreach(absent)(i => TestEffect1(i))).eval == Maybe.empty)
        }
        "foreachConcat" in {
            assert(TestEffect1.run(Kyo.foreachConcat(present)(i => TestEffect1(i).map(Maybe(_)))).eval == Maybe(2))
            assert(TestEffect1.run(Kyo.foreachConcat(present)(i => TestEffect1(i).map(_ => Maybe.empty[Int]))).eval == Maybe.empty)
            assert(TestEffect1.run(Kyo.foreachConcat(absent)(i => TestEffect1(i).map(Maybe(_)))).eval == Maybe.empty)
        }
        "foreachDiscard" in {
            var seen = List.empty[Int]
            TestEffect1.run(Kyo.foreachDiscard(present)(i => TestEffect1(i).map(v => seen :+= v))).eval
            TestEffect1.run(Kyo.foreachDiscard(absent)(i => TestEffect1(i).map(v => seen :+= v))).eval
            assert(seen == List(2))
        }
        "filter" in {
            assert(TestEffect1.run(Kyo.filter(present)(i => TestEffect1(i).map(_ == 2))).eval == Maybe(1))
            assert(TestEffect1.run(Kyo.filter(present)(i => TestEffect1(i).map(_ == 3))).eval == Maybe.empty)
            assert(TestEffect1.run(Kyo.filter(absent)(i => TestEffect1(i).map(_ => true))).eval == Maybe.empty)
        }
        "filterNot" in {
            assert(TestEffect1.run(Kyo.filterNot(present)(i => TestEffect1(i).map(_ == 2))).eval == Maybe.empty)
            assert(TestEffect1.run(Kyo.filterNot(present)(i => TestEffect1(i).map(_ == 3))).eval == Maybe(1))
            assert(TestEffect1.run(Kyo.filterNot(absent)(i => TestEffect1(i).map(_ => false))).eval == Maybe.empty)
        }
        "exists" in {
            assert(TestEffect1.run(Kyo.exists(present)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.exists(present)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(!TestEffect1.run(Kyo.exists(absent)(i => TestEffect1(i).map(_ => true))).eval)
        }
        "forall" in {
            assert(TestEffect1.run(Kyo.forall(present)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.forall(present)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(TestEffect1.run(Kyo.forall(absent)(i => TestEffect1(i).map(_ => false))).eval)
        }
        "collect" in {
            assert(TestEffect1.run(Kyo.collect(present)(i => TestEffect1(i).map(v => Maybe.when(v == 2)(v * 10)))).eval == Maybe(20))
            assert(TestEffect1.run(Kyo.collect(present)(i => TestEffect1(i).map(v => Maybe.when(v == 3)(v * 10)))).eval == Maybe.empty)
            assert(TestEffect1.run(Kyo.collect(absent)(i => TestEffect1(i).map(Maybe(_)))).eval == Maybe.empty)
        }
        "fold" in {
            assert(TestEffect1.run(Kyo.fold(present)(TestEffect1(100))(i => TestEffect1(i))).eval == 2)
            assert(TestEffect1.run(Kyo.fold(absent)(TestEffect1(100))(i => TestEffect1(i))).eval == 101)
        }
        "orElse" in {
            assert(TestEffect1.run(Kyo.orElse(present)(TestEffect1(100).map(Maybe(_)))).eval == Maybe(1))
            assert(TestEffect1.run(Kyo.orElse(absent)(TestEffect1(100).map(Maybe(_)))).eval == Maybe(101))
        }
        "orElse evaluates the alternative only when absent" in {
            var evaluated = false
            TestEffect1.run(Kyo.orElse(present)({ evaluated = true; TestEffect1(100).map(Maybe(_)) })).eval
            assert(!evaluated)
        }
        "getOrElse" in {
            assert(TestEffect1.run(Kyo.getOrElse(present)(TestEffect1(100))).eval == 1)
            assert(TestEffect1.run(Kyo.getOrElse(absent)(TestEffect1(100))).eval == 101)
        }
    }

    "Result" - {
        val success: Result[String, Int] = Result.succeed(1)
        val failure: Result[String, Int] = Result.fail("boom")
        val error                        = new Exception("panic")
        val panic: Result[String, Int]   = Result.Panic(error)

        "a panic keeps its identity" in {
            val through = TestEffect1.run(Kyo.foreach(panic)(i => TestEffect1(i))).eval
            assert(through.panic.exists(_ eq error))
        }

        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(success)(i => TestEffect1(i))).eval == Result.succeed(2))
            assert(TestEffect1.run(Kyo.foreach(failure)(i => TestEffect1(i))).eval == failure)
            assert(TestEffect1.run(Kyo.foreach(panic)(i => TestEffect1(i))).eval == panic)
        }
        "foreachConcat" in {
            assert(TestEffect1.run(Kyo.foreachConcat(success)(i => TestEffect1(i).map(Result.succeed))).eval == Result.succeed(2))
            assert(TestEffect1.run(Kyo.foreachConcat(success)(i => TestEffect1(i).map(_ => Result.fail(i)))).eval == Result.fail(1))
            assert(TestEffect1.run(Kyo.foreachConcat(failure)(i => TestEffect1(i).map(Result.succeed))).eval == failure)
            assert(TestEffect1.run(Kyo.foreachConcat(panic)(i => TestEffect1(i).map(Result.succeed))).eval == panic)
        }
        "foreachDiscard" in {
            var seen = List.empty[Int]
            TestEffect1.run(Kyo.foreachDiscard(success)(i => TestEffect1(i).map(v => seen :+= v))).eval
            TestEffect1.run(Kyo.foreachDiscard(failure)(i => TestEffect1(i).map(v => seen :+= v))).eval
            TestEffect1.run(Kyo.foreachDiscard(panic)(i => TestEffect1(i).map(v => seen :+= v))).eval
            assert(seen == List(2))
        }
        "filter" in {
            assert(TestEffect1.run(Kyo.filter(success)(i => TestEffect1(i).map(_ == 2))).eval == success)
            assert(TestEffect1.run(
                Kyo.filter(success)(i => TestEffect1(i).map(_ == 3))
            ).eval.failure.exists(_.isInstanceOf[NoSuchElementException]))
            assert(TestEffect1.run(Kyo.filter(failure)(i => TestEffect1(i).map(_ => true))).eval == failure)
            assert(TestEffect1.run(Kyo.filter(panic)(i => TestEffect1(i).map(_ => true))).eval == panic)
        }
        "exists" in {
            assert(TestEffect1.run(Kyo.exists(success)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.exists(success)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(!TestEffect1.run(Kyo.exists(failure)(i => TestEffect1(i).map(_ => true))).eval)
            assert(!TestEffect1.run(Kyo.exists(panic)(i => TestEffect1(i).map(_ => true))).eval)
        }
        "forall" in {
            assert(TestEffect1.run(Kyo.forall(success)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.forall(success)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(TestEffect1.run(Kyo.forall(failure)(i => TestEffect1(i).map(_ => false))).eval)
            assert(TestEffect1.run(Kyo.forall(panic)(i => TestEffect1(i).map(_ => false))).eval)
        }
        "fold" in {
            def fold(r: Result[String, Int]) =
                Kyo.fold(r)(i => TestEffect1(i).map(_.toString), e => TestEffect2(e), t => TestEffect2(t.getMessage))
            assert(TestEffect2.run(TestEffect1.run(fold(success))).eval == "2")
            assert(TestEffect2.run(TestEffect1.run(fold(failure))).eval == "BOOM")
            assert(TestEffect2.run(TestEffect1.run(fold(panic))).eval == "PANIC")
        }
        "orElse" in {
            assert(TestEffect1.run(Kyo.orElse(success)(TestEffect1(100).map(Result.succeed))).eval == success)
            assert(TestEffect1.run(Kyo.orElse(failure)(TestEffect1(100).map(Result.succeed))).eval == Result.succeed(101))
            assert(TestEffect1.run(Kyo.orElse(panic)(TestEffect1(100).map(Result.succeed))).eval == Result.succeed(101))
        }
        "getOrElse" in {
            assert(TestEffect1.run(Kyo.getOrElse(success)(TestEffect1(100))).eval == 1)
            assert(TestEffect1.run(Kyo.getOrElse(failure)(TestEffect1(100))).eval == 101)
            assert(TestEffect1.run(Kyo.getOrElse(panic)(TestEffect1(100))).eval == 101)
        }
    }

    "Option" - {
        val some: Option[Int] = Some(1)
        val none: Option[Int] = None

        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(some)(i => TestEffect1(i))).eval == Some(2))
            assert(TestEffect1.run(Kyo.foreach(none)(i => TestEffect1(i))).eval == None)
        }
        "foreachConcat" in {
            assert(TestEffect1.run(Kyo.foreachConcat(some)(i => TestEffect1(i).map(Some(_)))).eval == Some(2))
            assert(TestEffect1.run(Kyo.foreachConcat(some)(i => TestEffect1(i).map(_ => None))).eval == None)
            assert(TestEffect1.run(Kyo.foreachConcat(none)(i => TestEffect1(i).map(Some(_)))).eval == None)
        }
        "foreachDiscard" in {
            var seen = List.empty[Int]
            TestEffect1.run(Kyo.foreachDiscard(some)(i => TestEffect1(i).map(v => seen :+= v))).eval
            TestEffect1.run(Kyo.foreachDiscard(none)(i => TestEffect1(i).map(v => seen :+= v))).eval
            assert(seen == List(2))
        }
        "filter" in {
            assert(TestEffect1.run(Kyo.filter(some)(i => TestEffect1(i).map(_ == 2))).eval == Some(1))
            assert(TestEffect1.run(Kyo.filter(some)(i => TestEffect1(i).map(_ == 3))).eval == None)
            assert(TestEffect1.run(Kyo.filter(none)(i => TestEffect1(i).map(_ => true))).eval == None)
        }
        "filterNot" in {
            assert(TestEffect1.run(Kyo.filterNot(some)(i => TestEffect1(i).map(_ == 2))).eval == None)
            assert(TestEffect1.run(Kyo.filterNot(some)(i => TestEffect1(i).map(_ == 3))).eval == Some(1))
            assert(TestEffect1.run(Kyo.filterNot(none)(i => TestEffect1(i).map(_ => false))).eval == None)
        }
        "exists" in {
            assert(TestEffect1.run(Kyo.exists(some)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.exists(some)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(!TestEffect1.run(Kyo.exists(none)(i => TestEffect1(i).map(_ => true))).eval)
        }
        "forall" in {
            assert(TestEffect1.run(Kyo.forall(some)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.forall(some)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(TestEffect1.run(Kyo.forall(none)(i => TestEffect1(i).map(_ => false))).eval)
        }
        "collect" in {
            assert(TestEffect1.run(Kyo.collect(some)(i => TestEffect1(i).map(v => Maybe.when(v == 2)(v * 10)))).eval == Some(20))
            assert(TestEffect1.run(Kyo.collect(some)(i => TestEffect1(i).map(v => Maybe.when(v == 3)(v * 10)))).eval == None)
            assert(TestEffect1.run(Kyo.collect(none)(i => TestEffect1(i).map(Maybe(_)))).eval == None)
        }
        "fold" in {
            assert(TestEffect1.run(Kyo.fold(some)(TestEffect1(100))(i => TestEffect1(i))).eval == 2)
            assert(TestEffect1.run(Kyo.fold(none)(TestEffect1(100))(i => TestEffect1(i))).eval == 101)
        }
        "orElse" in {
            assert(TestEffect1.run(Kyo.orElse(some)(TestEffect1(100).map(Some(_)))).eval == Some(1))
            assert(TestEffect1.run(Kyo.orElse(none)(TestEffect1(100).map(Some(_)))).eval == Some(101))
        }
        "getOrElse" in {
            assert(TestEffect1.run(Kyo.getOrElse(some)(TestEffect1(100))).eval == 1)
            assert(TestEffect1.run(Kyo.getOrElse(none)(TestEffect1(100))).eval == 101)
        }
    }

    "Try" - {
        val error             = new Exception("boom")
        val success: Try[Int] = Success(1)
        val failure: Try[Int] = Failure(error)

        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(success)(i => TestEffect1(i))).eval == Success(2))
            assert(TestEffect1.run(Kyo.foreach(failure)(i => TestEffect1(i))).eval == failure)
        }
        "foreachConcat" in {
            assert(TestEffect1.run(Kyo.foreachConcat(success)(i => TestEffect1(i).map(Success(_)))).eval == Success(2))
            assert(TestEffect1.run(Kyo.foreachConcat(success)(i => TestEffect1(i).map(_ => failure))).eval == failure)
            assert(TestEffect1.run(Kyo.foreachConcat(failure)(i => TestEffect1(i).map(Success(_)))).eval == failure)
        }
        "foreachDiscard" in {
            var seen = List.empty[Int]
            TestEffect1.run(Kyo.foreachDiscard(success)(i => TestEffect1(i).map(v => seen :+= v))).eval
            TestEffect1.run(Kyo.foreachDiscard(failure)(i => TestEffect1(i).map(v => seen :+= v))).eval
            assert(seen == List(2))
        }
        "filter" in {
            assert(TestEffect1.run(Kyo.filter(success)(i => TestEffect1(i).map(_ == 2))).eval == success)
            assert(
                TestEffect1.run(Kyo.filter(success)(i => TestEffect1(i).map(_ == 3))).eval.failed.get.isInstanceOf[NoSuchElementException]
            )
            assert(TestEffect1.run(Kyo.filter(failure)(i => TestEffect1(i).map(_ => true))).eval == failure)
        }
        "collect" in {
            assert(TestEffect1.run(Kyo.collect(success)(i => TestEffect1(i).map(v => Maybe.when(v == 2)(v * 10)))).eval == Success(20))
            assert(TestEffect1.run(Kyo.collect(success)(i => TestEffect1(i).map(v => Maybe.when(v == 3)(v * 10)))).eval.failed.get
                .isInstanceOf[NoSuchElementException])
            assert(TestEffect1.run(Kyo.collect(failure)(i => TestEffect1(i).map(Maybe(_)))).eval == failure)
        }
        "fold" in {
            def fold(t: Try[Int]) = Kyo.fold(t)(e => TestEffect2(e.getMessage), i => TestEffect1(i).map(_.toString))
            assert(TestEffect2.run(TestEffect1.run(fold(success))).eval == "2")
            assert(TestEffect2.run(TestEffect1.run(fold(failure))).eval == "BOOM")
        }
        "orElse" in {
            assert(TestEffect1.run(Kyo.orElse(success)(TestEffect1(100).map(Success(_)))).eval == success)
            assert(TestEffect1.run(Kyo.orElse(failure)(TestEffect1(100).map(Success(_)))).eval == Success(101))
        }
        "getOrElse" in {
            assert(TestEffect1.run(Kyo.getOrElse(success)(TestEffect1(100))).eval == 1)
            assert(TestEffect1.run(Kyo.getOrElse(failure)(TestEffect1(100))).eval == 101)
        }
        "recover" in {
            assert(TestEffect1.run(Kyo.recover(success)(_ => TestEffect1(100).map(Maybe(_)))).eval == success)
            assert(TestEffect1.run(Kyo.recover(failure)(_ => TestEffect1(100).map(Maybe(_)))).eval == Success(101))
            assert(TestEffect1.run(Kyo.recover(failure)(_ => TestEffect1(100).map(_ => Maybe.empty[Int]))).eval == failure)
        }
        "recoverWith" in {
            val other: Try[Int] = Failure(new Exception("other"))
            assert(TestEffect1.run(Kyo.recoverWith(success)(_ => TestEffect1(100).map(v => Maybe(Success(v))))).eval == success)
            assert(TestEffect1.run(Kyo.recoverWith(failure)(_ => TestEffect1(100).map(v => Maybe(Success(v))))).eval == Success(101))
            assert(TestEffect1.run(Kyo.recoverWith(failure)(_ => TestEffect1(100).map(_ => Maybe(other)))).eval == other)
            assert(TestEffect1.run(Kyo.recoverWith(failure)(_ => TestEffect1(100).map(_ => Maybe.empty[Try[Int]]))).eval == failure)
        }
    }

    "Either" - {
        val right: Either[String, Int] = Right(1)
        val left: Either[String, Int]  = Left("boom")

        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(right)(i => TestEffect1(i))).eval == Right(2))
            assert(TestEffect1.run(Kyo.foreach(left)(i => TestEffect1(i))).eval == left)
        }
        "foreachConcat" in {
            assert(TestEffect1.run(Kyo.foreachConcat(right)(i => TestEffect1(i).map(Right(_)))).eval == Right(2))
            assert(TestEffect1.run(Kyo.foreachConcat(right)(i => TestEffect1(i).map(v => Left(v.toString)))).eval == Left("2"))
            assert(TestEffect1.run(Kyo.foreachConcat(left)(i => TestEffect1(i).map(Right(_)))).eval == left)
        }
        "foreachDiscard" in {
            var seen = List.empty[Int]
            TestEffect1.run(Kyo.foreachDiscard(right)(i => TestEffect1(i).map(v => seen :+= v))).eval
            TestEffect1.run(Kyo.foreachDiscard(left)(i => TestEffect1(i).map(v => seen :+= v))).eval
            assert(seen == List(2))
        }
        "exists" in {
            assert(TestEffect1.run(Kyo.exists(right)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.exists(right)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(!TestEffect1.run(Kyo.exists(left)(i => TestEffect1(i).map(_ => true))).eval)
        }
        "forall" in {
            assert(TestEffect1.run(Kyo.forall(right)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.forall(right)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(TestEffect1.run(Kyo.forall(left)(i => TestEffect1(i).map(_ => false))).eval)
        }
        "fold" in {
            def fold(e: Either[String, Int]) = Kyo.fold(e)(s => TestEffect2(s), i => TestEffect1(i).map(_.toString))
            assert(TestEffect2.run(TestEffect1.run(fold(right))).eval == "2")
            assert(TestEffect2.run(TestEffect1.run(fold(left))).eval == "BOOM")
        }
        "orElse" in {
            assert(TestEffect1.run(Kyo.orElse(right)(TestEffect1(100).map(Right(_)))).eval == right)
            assert(TestEffect1.run(Kyo.orElse(left)(TestEffect1(100).map(Right(_)))).eval == Right(101))
        }
        "getOrElse" in {
            assert(TestEffect1.run(Kyo.getOrElse(right)(TestEffect1(100))).eval == 1)
            assert(TestEffect1.run(Kyo.getOrElse(left)(TestEffect1(100))).eval == 101)
        }
        "filterOrElse" in {
            assert(TestEffect2.run(TestEffect1.run(Kyo.filterOrElse(right)(i => TestEffect1(i).map(_ == 2), TestEffect2("zero")))).eval ==
                right)
            assert(TestEffect2.run(TestEffect1.run(Kyo.filterOrElse(right)(i => TestEffect1(i).map(_ == 3), TestEffect2("zero")))).eval ==
                Left("ZERO"))
            assert(TestEffect2.run(TestEffect1.run(Kyo.filterOrElse(left)(i => TestEffect1(i).map(_ => true), TestEffect2("zero")))).eval ==
                left)
        }
    }

    "Either.LeftProjection" - {
        val right: Either[Int, String] = Right("boom")
        val left: Either[Int, String]  = Left(1)

        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(left.left)(i => TestEffect1(i))).eval == Left(2))
            assert(TestEffect1.run(Kyo.foreach(right.left)(i => TestEffect1(i))).eval == right)
        }
        "foreachConcat" in {
            assert(TestEffect1.run(Kyo.foreachConcat(left.left)(i => TestEffect1(i).map(Left(_)))).eval == Left(2))
            assert(TestEffect1.run(Kyo.foreachConcat(left.left)(i => TestEffect1(i).map(v => Right(v.toString)))).eval == Right("2"))
            assert(TestEffect1.run(Kyo.foreachConcat(right.left)(i => TestEffect1(i).map(Left(_)))).eval == right)
        }
        "foreachDiscard" in {
            var seen = List.empty[Int]
            TestEffect1.run(Kyo.foreachDiscard(left.left)(i => TestEffect1(i).map(v => seen :+= v))).eval
            TestEffect1.run(Kyo.foreachDiscard(right.left)(i => TestEffect1(i).map(v => seen :+= v))).eval
            assert(seen == List(2))
        }
        "exists" in {
            assert(TestEffect1.run(Kyo.exists(left.left)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.exists(left.left)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(!TestEffect1.run(Kyo.exists(right.left)(i => TestEffect1(i).map(_ => true))).eval)
        }
        "forall" in {
            assert(TestEffect1.run(Kyo.forall(left.left)(i => TestEffect1(i).map(_ == 2))).eval)
            assert(!TestEffect1.run(Kyo.forall(left.left)(i => TestEffect1(i).map(_ == 3))).eval)
            assert(TestEffect1.run(Kyo.forall(right.left)(i => TestEffect1(i).map(_ => false))).eval)
        }
        "filterToOption" in {
            assert(TestEffect1.run(Kyo.filterToOption(left.left)(i => TestEffect1(i).map(_ == 2))).eval == Some(left))
            assert(TestEffect1.run(Kyo.filterToOption(left.left)(i => TestEffect1(i).map(_ == 3))).eval == None)
            assert(TestEffect1.run(Kyo.filterToOption(right.left)(i => TestEffect1(i).map(_ => false))).eval == Some(right))
        }
        "getOrElse" in {
            assert(TestEffect1.run(Kyo.getOrElse(left.left)(TestEffect1(100))).eval == 1)
            assert(TestEffect1.run(Kyo.getOrElse(right.left)(TestEffect1(100))).eval == 101)
        }
    }
end KyoContainerTest

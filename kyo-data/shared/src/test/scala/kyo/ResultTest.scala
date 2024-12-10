package kyo

import kyo.Result
import kyo.Result.*
import scala.util.Try

class ResultTest extends Test:

    val ex = new Exception

    val try2: Result[String, Result[String, Int]] = Success(Fail("error"))

    "should match Success containing Fail" in {
        val result = try2 match
            case Success(Fail(e)) => e
            case _                => ""
        assert(result == "error")
    }

    "catching" - {

        "success" in {
            assert(Result.catching[Exception](1) == Success(1))
        }

        "fail" in {
            assert(Result.catching[Exception](throw ex) == Fail(ex))
        }

        "panic" in {
            assert(Result.catching[IllegalArgumentException](throw ex) == Panic(ex))
        }

        "union of exception types" in {
            val ex = new IllegalStateException("test")
            val result = Result.catching[IllegalArgumentException | IllegalStateException] {
                throw ex
            }
            assert(result == Result.fail(ex))
        }

        "intersection of exception types" in {
            trait CustomException
            class CustomRuntimeException extends RuntimeException("test") with CustomException
            val ex     = new CustomRuntimeException
            val result = Result.catching[RuntimeException & CustomException](throw ex)
            assert(result == Result.fail(ex))
        }

        "union + intersection" in {
            trait SomeTrait
            class CustomException extends RuntimeException("inner") with SomeTrait
            val ex = new CustomException
            val result = Result.catching[IllegalArgumentException | (RuntimeException & SomeTrait)] {
                throw ex
            }
            assert(result == Result.fail(ex))
        }
    }

    "unit" in {
        assert(Result.unit == Result.success(()))
    }

    "fromTry" - {
        "should return Success for successful Try" in {
            val tryValue = scala.util.Try(5)
            val result   = Result.fromTry(tryValue)
            assert(result == Result.success(5))
        }

        "should return Fail for failed Try" in {
            val exception = new RuntimeException("Test exception")
            val tryValue  = scala.util.Try(throw exception)
            val result    = Result.fromTry(tryValue)
            assert(result == Result.fail(exception))
        }
    }

    "fromEither" - {
        "should return Success for Right" in {
            val eitherValue = Right(5)
            val result      = Result.fromEither(eitherValue)
            assert(result == Result.success(5))
        }

        "should return Fail for Left" in {
            val eitherValue = Left("Fail message")
            val result      = Result.fromEither(eitherValue)
            assert(result == Result.fail("Fail message"))
        }

        "should maintain type parameters" in {
            val result: Result[String, Int] = Result.fromEither(Right(5))
            assert(result == Result.success(5))

            val result2: Result[String, Int] = Result.fromEither(Left("Fail"))
            assert(result2 == Result.fail("Fail"))
        }
    }

    "value" - {
        "returns Present with the value for Success" in {
            assert(Result.success(42).value == Maybe(42))
        }

        "returns Absent for Fail" in {
            assert(Result.fail("error").value == Maybe.empty)
        }

        "returns Absent for Panic" in {
            assert(Result.panic(new Exception).value == Maybe.empty)
        }
    }

    "failure" - {
        "returns Present with the error for Fail" in {
            assert(Result.fail("error").failure == Maybe("error"))
        }

        "returns Absent for Success" in {
            assert(Result.success(42).failure == Maybe.empty)
        }

        "returns Absent for Panic" in {
            assert(Result.panic(new Exception).failure == Maybe.empty)
        }
    }

    "panic" - {
        "returns Present with the exception for Panic" in {
            val ex = new Exception("test")
            assert(Result.panic(ex).panic == Maybe(ex))
        }

        "returns Absent for Success" in {
            assert(Result.success(42).panic == Maybe.empty)
        }

        "returns Absent for Fail" in {
            assert(Result.fail("error").panic == Maybe.empty)
        }
    }

    "isSuccess" - {
        "returns true for Success" in {
            assert(Result.success(1).isSuccess)
        }
        "returns false for Fail" in {
            assert(!Result.fail(ex).isSuccess)
        }
        "returns false for Panic" in {
            assert(!Result.panic(ex).isSuccess)
        }
    }

    "isFail" - {
        "returns false for Success" in {
            assert(!Result.success(1).isFail)
        }
        "returns true for Fail" in {
            assert(Result.fail(ex).isFail)
        }
        "returns false for Panic" in {
            assert(!Result.panic(ex).isFail)
        }
    }

    "isPanic" - {
        "returns false for Success" in {
            assert(!Result.success(1).isPanic)
        }
        "returns false for Fail" in {
            assert(!Result.fail(ex).isPanic)
        }
        "returns true for Panic" in {
            assert(Result.panic(ex).isPanic)
        }
    }

    "get" - {
        "returns the value for Success" in {
            assert(Result.success(1).get == 1)
        }
        "can't be called for Fail" in {
            assertDoesNotCompile("Result.error(ex).get")
        }
        "throws an exception for Panic" in {
            assertThrows[Exception](Result.panic(ex).get)
        }
    }

    "getOrElse" - {
        "returns the value for Success" in {
            assert(Result.success(1).getOrElse(0) == 1)
        }
        "returns the default value for Fail" in {
            assert(Result.fail(ex).getOrElse(0) == 0)
        }
        "returns the default value for Panic" in {
            assert(Result.panic(ex).getOrElse(0) == 0)
        }
        "inference" in {
            val r: List[String] = Result(List.empty[String]).getOrElse(List.empty)
            assert(r == List.empty)
        }
    }

    "getOrThrow" - {
        "returns the value for Success" in {
            assert(Result.success(1).getOrThrow == 1)
        }
        "doesn't compile for non-Throwable Fail" in {
            assertDoesNotCompile("Result.fail(1).getOrThrow")
        }
        "throws for Throwable Fail" in {
            assert(Result.catching[Exception](Result.fail(ex).getOrThrow) == Result.fail(ex))
        }
        "throws for Panic" in {
            assert(Result.catching[Exception](Result.panic(ex).getOrThrow) == Result.fail(ex))
        }
    }

    "orElse" - {
        "returns itself for Success" in {
            assert(Result.success(1).orElse(Result.success(2)) == Success(1))
        }
        "returns the alternative for Fail" in {
            assert(Result.fail(ex).orElse(Success(1)) == Success(1))
        }
        "returns the alternative for Panic" in {
            assert(Result.panic(ex).orElse(Success(1)) == Success(1))
        }
    }

    "flatMap" - {
        "applies the function for Success" in {
            assert(Result.success(1).flatMap(x => Result.success(x + 1)) == Success(2))
        }
        "does not apply the function for Fail" in {
            assert(Result.fail[String, Int]("error").flatMap(x => Success(x + 1)) == Fail("error"))
        }
        "does not apply the function for Panic" in {
            assert(Result.panic[String, Int](ex).flatMap(x => Success(x + 1)) == Panic(ex))
        }
    }

    "map" - {
        "applies the function for Success" in {
            assert(Result.success(1).map(_ + 1) == Success(2))
        }
        "does not apply the function for Fail" in {
            assert(Result.fail[String, Int]("error").map(_ + 1) == Fail("error"))
        }
        "does not apply the function for Panic" in {
            assert(Result.panic[String, Int](ex).map(_ + 1) == Panic(ex))
        }
    }

    "fold" - {
        "applies the success function for Success" in {
            assert(Result.success(1).fold(_ => 0)(x => x + 1) == 2)
        }
        "applies the failure function for Failure" in {
            assert(Result.fail[String, Int]("error").fold(_ => 0)(x => x) == 0)
        }
    }

    "filter" - {
        "adds NoSuchElementException" in {
            val x = Result.success(2).filter(_ % 2 == 0)
            discard(x)
            assertCompiles("val _: Result[NoSuchElementException, Int] = x")
        }
        "returns itself if the predicate holds for Success" in {
            assert(Result.success(2).filter(_ % 2 == 0) == Success(2))
        }
        "returns Fail if the predicate doesn't hold for Success" in {
            assert(Result.success(1).filter(_ % 2 == 0).isFail)
        }
        "returns itself for Fail" in {
            assert(Result.fail[String, Int]("error").filter(_ => true) == Fail("error"))
        }
    }

    "recover" - {
        "returns itself for Success" in {
            assert(Result.success(1).recover { case _ => 0 } == Success(1))
        }
        "returns Success with the mapped value if the partial function is defined for Fail" in {
            assert(Result.fail("error").recover { case _ => 0 } == Success(0))
        }
        "returns itself if the partial function is not defined for Fail" in {
            assert(Result.fail("error").recover { case _ if false => 0 } == Fail("error"))
        }
    }

    "recoverWith" - {
        "returns itself for Success" in {
            assert(Result.success(1).recoverWith { case _ => Success(0) } == Success(1))
        }
        "returns the mapped Result if the partial function is defined for Fail" in {
            assert(Fail("error").recoverWith { case _ => Success(0) } == Success(0))
        }
        "returns itself if the partial function is not defined for Fail" in {
            val error = Fail("error")
            assert(Fail(error).recoverWith { case _ if false => Success(0) } == Fail(error))
        }
    }

    "toEither" - {
        "returns Right with the value for Success" in {
            assert(Result.success(1).toEither == Right(1))
        }
        "returns Left with the error for Fail" in {
            val error = "error"
            assert(Fail(error).toEither == Left(error))
        }
    }

    "deeply nested Result" - {
        val nestedResult = Success(Success(Success(Success(1))))

        "get should return the deeply nested value" in {
            assert(nestedResult.get.get.get.get == 1)
        }

        "map should apply the function to the deeply nested Result" in {
            assert(nestedResult.map(_.map(_.map(_.map(_ + 1)))) == Success(Success(Success(Success(2)))))
        }

        "flatMap should apply the function and flatten the result" in {
            assert(nestedResult.flatMap(x => x.flatMap(y => y.flatMap(z => z.map(_ + 1)))) == Success(2))
        }

        "isSuccess should return true for deeply nested Success" in {
            assert(nestedResult.isSuccess)
        }

        "isFail should return false for deeply nested Success" in {
            assert(!nestedResult.isFail)
        }
    }

    "exception" - {
        "only available if E is Throwable" in {
            assertDoesNotCompile("Result.Fail(1).exception")
        }
        "from Fail" in {
            val ex = new Exception
            assert(Result.Fail(ex).exception == ex)
        }
        "from Panic" in {
            val ex = new Exception
            assert(Result.Panic(ex).exception == ex)
        }
    }

    "toTry" - {
        "Success to Try" in {
            val success: Result[Nothing, Int] = Success(42)
            val tryResult                     = success.toTry
            assert(tryResult.isSuccess)
            assert(tryResult.get == 42)
        }

        "Fail to Try" - {
            "Throwable error" in {
                val failure: Result[Exception, Int] = Fail(ex)
                val tryResult                       = failure.toTry
                assert(tryResult.isFailure)
                assert(tryResult.failed.get == ex)
            }
            "Nothing error" in {
                val failure: Result[Nothing, Int] = Result.success(1)
                val tryResult                     = failure.toTry
                assert(tryResult == Try(1))
            }
            "fails to compile for non-Throwable error" in {
                val failure: Result[String, Int] = Fail("Something went wrong")
                val _                            = failure
                assertDoesNotCompile("failure.toTry")
            }
        }

        "Panic to Try" in {
            val panic: Result[Throwable, Int] = Result.panic(new Exception("Panic"))
            val tryResult                     = panic.toTry
            assert(tryResult.isFailure)
            assert(tryResult.failed.get.isInstanceOf[Exception])
            assert(tryResult.failed.get.getMessage == "Panic")
        }
    }

    "null values" - {
        "Success with null value" in {
            val result: Result[Nothing, String] = Success(null)
            assert(result.isSuccess)
            assert(result.get == null)
        }

        "Success with null value flatMap" in {
            val result: Result[Nothing, String] = Success(null)
            val flatMapped                      = result.flatMap(str => Success(s"mapped: $str"))
            assert(flatMapped == Success("mapped: null"))
        }

        "Fail with null error" in {
            val result: Result[String, Int] = Fail(null)
            assert(result == Fail(null))
        }

        "Fail with null exception flatMap" in {
            val result: Result[String, Int] = Fail(null)
            val flatMapped                  = result.flatMap(num => Success(num + 1))
            assert(flatMapped == Fail(null))
        }

        "Fail with null exception map" in {
            val result: Result[String, Int] = Fail(null)
            val mapped                      = result.map(num => num + 1)
            assert(mapped == Fail(null))
        }
    }

    "pattern matching" - {
        "deep matching" - {
            val nestedResult = Success(Success(Success(Success(1))))

            "should match deeply nested Success and extract inner value" in {
                val result = nestedResult match
                    case Success(Success(Success(Success(x)))) => x
                assert(result == 1)
            }

            "should match partially and extract nested Result" in {
                val result = nestedResult match
                    case Success(Success(x)) => x
                assert(result == Success(Success(1)))
            }
        }

        "matching with guards" - {
            "should match Success with a guard" in {
                val tryy: Result[Nothing, Int] = Success(2)
                val result = tryy match
                    case Success(x) if x > 1 => "greater than 1"
                    case Success(_)          => "less than or equal to 1"
                    case Fail(_)             => "failure"
                assert(result == "greater than 1")
            }

            "should match Fail with a guard" in {
                val tryy: Result[String, Int] = Fail("error")
                val result = tryy match
                    case Fail(e) if e.length > 5 => "long error"
                    case Fail(_)                 => "short error"
                    case Success(_)              => "success"
                assert(result == "short error")
            }
        }
        "Error.unapply" - {
            "should match Fail" in {
                val result = Result.fail("FAIL!")
                result match
                    case Error(_) => succeed
                    case _        => fail()
            }
            "should match Panic" in {
                val result = Result.panic(new AssertionError)
                result match
                    case Error(_) => succeed
                    case _        => fail()
            }
            "should not match Success" in {
                val result = Result.success(1)
                result match
                    case Error(_) => fail()
                    case _        => succeed
            }
        }

    }

    "inference" - {
        "flatMap" in {
            val result: Result[String, Int]    = Result.success(5)
            val mapped: Result[String, String] = result.flatMap(x => Result.success(x.toString))
            assert(mapped == Success("5"))
        }

        "flatMap with different error types" in {
            val r1: Result[String, Int]              = Result.success(5)
            val r2: Result[Int, String]              = Result.success("hello")
            val nested: Result[String | Int, String] = r1.flatMap(x => r2.map(y => s"$x $y"))
            assert(nested == Success("5 hello"))
        }

        "for-comprehension with multiple flatMaps" in {
            def divideIfEven(x: Int): Result[String, Int] =
                if x % 2 == 0 then Result.success(10 / x) else Result.fail("Odd number")

            val complex: Result[String, String] =
                for
                    a <- Result.success(4)
                    b <- divideIfEven(a)
                    c <- Result.success(b * 2)
                yield c.toString

            assert(complex == Success("4"))
        }
    }

    "edge cases" - {

        "should not handle exceptions in ifError" in {
            val tryy      = Success(1)
            val exception = new RuntimeException("exception")
            val result =
                try
                    tryy.fold(_ => throw exception)(_ => throw exception)
                    "no exception"
                catch
                    case e: RuntimeException => "caught exception"
            assert(result == "caught exception")
        }

        "should not handle exceptions in ifPanic" in {
            val tryy      = Result.panic(ex)
            val exception = new RuntimeException("exception")
            val result =
                try
                    tryy.fold(_ => throw exception)(_ => throw exception)
                    "no exception"
                catch
                    case e: RuntimeException => "caught exception"
            assert(result == "caught exception")
        }

        "should handle exceptions in ifSuccess" in {
            val tryy      = Success(1)
            val exception = new RuntimeException("exception")
            val result =
                try
                    tryy.fold(_ => 0)(_ => throw exception)
                    "no exception"
                catch
                    case e: RuntimeException => "caught exception"
            assert(result == "no exception")
        }

        "should handle exceptions during map" in {
            val tryy      = Success(1)
            val exception = new RuntimeException("exception")
            val result =
                try
                    tryy.map(_ => throw exception)
                    "no exception"
                catch
                    case e: RuntimeException => "caught exception"
            assert(result == "no exception")
        }

        "should handle exceptions during flatMap" in {
            val tryy      = Success(1)
            val exception = new RuntimeException("exception")
            val result =
                try
                    tryy.flatMap(_ => throw exception)
                    "no exception"
                catch
                    case e: RuntimeException => "caught exception"
            assert(result == "no exception")
        }

        "nested Success containing Fail" in {
            val nested: Result[String, Result[Int, String]] = Result.success(Result.fail(42))
            val flattened                                   = nested.flatten

            assert(flattened == Fail(42))
        }

        "recover with a partial function" in {
            val result: Result[String, Int] = Result.fail("error")
            val recovered = result.recover {
                case Fail("error") => 0
                case _             => -1
            }

            assert(recovered == Success(0))
        }

        "empty Success" in {
            val empty: Result[Nothing, Unit] = Result.success(())
            assert(empty == Success(()))
        }

        "Panic distinct from Fail" in {
            val exception = new RuntimeException("Unexpected error")
            val panic     = Result.panic(exception)
            assert(!panic.isFail)
            assert(panic match
                case Panic(_) => true
                case _        => false
            )
        }

        "deeply nested Success/Fail" in {
            val deeplyNested = Success(Success(Success(Fail("deep error"))))
            assert(deeplyNested.flatten == Success(Success(Fail("deep error"))))
            assert(deeplyNested.flatten.flatten == Success(Fail("deep error")))
            assert(deeplyNested.flatten.flatten.flatten == Fail("deep error"))
        }

        "Panic propagation through flatMap" in {
            val panic  = Result.panic(new RuntimeException("Unexpected"))
            val result = panic.flatMap(_ => Success(1)).flatMap(_ => Fail("won't happen"))
            assert(result == panic)
        }

        "Fail type widening" in {
            val r1: Result[String, Int] = Fail("error1")
            val r2: Result[Int, String] = Fail(42)
            val combined                = r1.orElse(r2)
            assert(combined.isFail)
            assert(combined match
                case Fail(_: String | _: Int) => true
                case _                        => false
            )
        }

        "nested flatMap with type changes" in {
            def f(i: Int): Result[String, Int] =
                if i > 0 then Success(i) else Fail("non-positive")
            def g(d: Int): Result[Int, String] =
                if d < 10 then Success(d.toString) else Fail(d.toInt)

            val result = Success(5).flatMap(f).flatMap(g)
            assert(result == Success("5"))

            val result2 = Success(-1).flatMap(f).flatMap(g)
            assert(result2 == Fail("non-positive"))

            val result3 = Success(20).flatMap(f).flatMap(g)
            assert(result3 == Fail(20))
        }
    }

    "swap" - {
        "Success to Fail" in {
            val result = Result.success[String, Int](42)
            assert(result.swap == Result.fail(42))
        }

        "Fail to Success" in {
            val result = Result.fail[String, Int]("error")
            assert(result.swap == Result.success("error"))
        }

        "Panic remains Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[Int, String](ex)
            assert(result.swap == Result.panic(ex))
        }

        "nested Results" in {
            val nested = Result.success[Int, Result[String, Boolean]](Result.fail("inner"))
            assert(nested.swap == Result.fail(Result.fail("inner")))
        }

        "type inference" in {
            val result: Result[Int, String]  = Result.success("hello")
            val swapped: Result[String, Int] = result.swap
            assert(swapped == Result.fail("hello"))
        }

        "idempotence" in {
            val success = Result.success[String, Int](42)
            assert(success.swap.swap == success)

            val failure = Result.fail[String, Int]("error")
            assert(failure.swap.swap == failure)

            val panic = Result.panic[Int, String](new Exception("test"))
            assert(panic.swap.swap == panic)
        }
    }

    "for comprehensions" - {
        "yield a Success result" in {
            val result =
                for
                    x <- Result.success(1)
                    y <- Result.success(2)
                    z <- Result.success(3)
                yield x + y + z

            assert(result == Result.success(6))
        }

        "short-circuit on Failure" in {
            val result =
                for
                    x <- Result.success(1)
                    y <- Result.fail[Exception, Int](new Exception("error"))
                    z <- Result.success(3)
                yield x + y + z

            assert(result.isFail)
        }

        "handle exceptions in the yield" in {
            val result =
                for
                    _ <- Result.success(1)
                    _ <- Result.success(2)
                yield throw new Exception("error")

            assert(result.isPanic)
        }

        "sequence operations with flatMap" in {
            val result =
                for
                    x <- Result.success(1)
                    y <- Result.success(2)
                    if y > 0
                    z <- Result.success(3)
                yield x + y + z

            assert(result == Result.success(6))
        }

        "fail the comprehension with a guard" in {
            val result =
                for
                    x <- Result.success(1)
                    y <- Result.success(-1)
                    if y > 0
                yield x + y

            assert(result.isFail)
        }
    }

    "mapFail" - {
        "should not change Success" in {
            val result = Result.success[String, Int](5)
            val mapped = result.mapFail(_ => 42)
            assert(mapped == Success(5))
        }

        "should apply the function to Fail" in {
            val result = Result.fail[String, Int]("error")
            val mapped = result.mapFail(_.length)
            assert(mapped == Fail(5))
        }

        "should not change Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, Int](ex)
            val mapped = result.mapFail(_ => 42)
            assert(mapped == Panic(ex))
        }

        "should allow changing the error type" in {
            val result: Result[String, Int] = Result.fail("error")
            val mapped: Result[Int, Int]    = result.mapFail(_.length)
            assert(mapped == Fail(5))
        }

        "should handle exceptions in the mapping function" in {
            val result = Result.fail[String, Int]("error")
            val mapped = result.mapFail(_ => throw new RuntimeException("Mapping error"))
            assert(mapped.isPanic)
        }

        "should work with for-comprehensions" in {
            val result =
                for
                    x <- Result.success[String, Int](5)
                    y <- Result.fail[String, Int]("error")
                yield x + y

            val mapped = result.mapFail(_.toUpperCase)
            assert(mapped == Fail("ERROR"))
        }
    }

    "collect" - {
        "all Success results" in {
            val results = Seq(
                Result.success(1),
                Result.success(2),
                Result.success(3)
            )
            val collected = Result.collect(results)
            assert(collected == Success(Seq(1, 2, 3)))
        }

        "first Fail encountered" in {
            val results = Seq(
                Result.success(1),
                Result.fail("error"),
                Result.success(3)
            )
            val collected = Result.collect(results)
            assert(collected == Fail("error"))
        }

        "Panic encountered" in {
            val ex = new Exception("panic")
            val results = Seq(
                Result.success(1),
                Result.panic(ex),
                Result.fail("error")
            )
            val collected = Result.collect(results)
            assert(collected == Panic(ex))
        }

        "empty input sequence" in {
            val results: Seq[Result[String, Int]] = Seq.empty
            val collected                         = Result.collect(results)
            assert(collected == Success(Seq.empty))
        }

        "mixed error types" in {
            val results = Seq(
                Result.success(1),
                Result.fail("string error"),
                Result.fail(42),
                Result.success(3)
            )
            val collected: Result[String | Int, Seq[Int]] =
                Result.collect(results)
            assert(collected.isFail)
            assert(collected.failure.get.equals("string error"))
        }

        "mixed Success and Fail with different error types" in {
            val results: Seq[Result[Any, Int]] = Seq(
                Result.success(1),
                Result.fail("string error"),
                Result.success(2),
                Result.fail(42),
                Result.success(3)
            )
            val collected = Result.collect(results)
            assert(collected.isFail)
            assert(collected.failure.get.equals("string error"))
        }

    }

    "contains" - {
        "should return true for Success with matching value" in {
            val result = Result.success(42)
            assert(result.contains(42))
        }

        "should return false for Success with non-matching value" in {
            val result = Result.success(42)
            assert(!result.contains(43))
        }

        "should return false for Fail" in {
            val result = Result.fail[String, Int]("error")
            assert(!result.contains(42))
        }

        "should return false for Panic" in {
            val result = Result.panic[String, Int](new Exception("panic"))
            assert(!result.contains(42))
        }

        "should work with custom types" in {
            case class Person(name: String, age: Int) derives CanEqual
            val person = Person("Alice", 30)
            val result = Result.success(person)
            assert(result.contains(person))
            assert(!result.contains(Person("Bob", 25)))
        }

        "should work with Maybe values" in {
            val someMaybeResult = Result.success(Maybe(42))
            assert(someMaybeResult.contains(Maybe(42)))
            assert(!someMaybeResult.contains(Maybe(43)))
            assert(!someMaybeResult.contains(Maybe.empty))

            val noneMaybeResult = Result.success(Maybe.empty)
            assert(noneMaybeResult.contains(Maybe.empty))
            assert(!noneMaybeResult.contains(Maybe(42)))

            val failResult: Result[String, Maybe[Int]] = Result.fail("error")
            assert(!failResult.contains(Maybe(42)))
            assert(!failResult.contains(Maybe.empty))

            val panicResult: Result[String, Maybe[Int]] = Result.panic(new Exception("panic"))
            assert(!panicResult.contains(Maybe(42)))
            assert(!panicResult.contains(Maybe.empty))
        }

        "should work with nested Result values" in {
            val nestedSuccessResult: Result[String, Result[Int, String]] = Result.success(Result.success("nested"))
            assert(nestedSuccessResult.contains(Result.success("nested")))
            assert(!nestedSuccessResult.contains(Result.success("other")))
            assert(!nestedSuccessResult.contains(Result.fail(42)))

            val nestedFailResult: Result[String, Result[Int, String]] = Result.success(Result.fail(42))
            assert(nestedFailResult.contains(Result.fail(42)))
            assert(!nestedFailResult.contains(Result.success("nested")))

            val deeplyNestedResult: Result[String, Result[Int, Result[Double, String]]] =
                Result.success(Result.success(Result.success("deeply nested")))
            assert(deeplyNestedResult.contains(Result.success(Result.success("deeply nested"))))
            assert(!deeplyNestedResult.contains(Result.success(Result.fail(3.14))))

            val outerFailResult: Result[String, Result[Int, String]] = Result.fail("outer error")
            assert(!outerFailResult.contains(Result.success("nested")))
            assert(!outerFailResult.contains(Result.fail(42)))
        }
    }

    "unit" - {
        "should convert Success to Success(())" in {
            val result = Result.success(42).unit
            assert(result == Success(()))
        }

        "should not change Fail" in {
            val result = Result.fail[String, Int]("error").unit
            assert(result == Fail("error"))
        }

        "should not change Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, Int](ex).unit
            assert(result == Panic(ex))
        }
    }

    "exists" - {
        "should return true for Success when predicate holds" in {
            val result = Result.success(42)
            assert(result.exists(_ > 0))
        }

        "should return false for Success when predicate doesn't hold" in {
            val result = Result.success(42)
            assert(!result.exists(_ < 0))
        }

        "should return false for Fail" in {
            val result = Result.fail[String, Int]("error")
            assert(!result.exists(_ => true))
        }

        "should return false for Panic" in {
            val result = Result.panic[String, Int](new Exception("test"))
            assert(!result.exists(_ => true))
        }
    }

    "forall" - {
        "should return true for Success when predicate holds" in {
            val result = Result.success(42)
            assert(result.forall(_ > 0))
        }

        "should return false for Success when predicate doesn't hold" in {
            val result = Result.success(42)
            assert(!result.forall(_ < 0))
        }

        "should return true for Fail" in {
            val result = Result.fail[String, Int]("error")
            assert(result.forall(_ => false))
        }

        "should return true for Panic" in {
            val result = Result.panic[String, Int](new Exception("test"))
            assert(result.forall(_ => false))
        }
    }

    "show" - {
        "Success" in {
            assert(Result.success(42).show == "Success(42)")
        }

        "Fail" in {
            assert(Result.fail("error").show == "Fail(error)")
        }

        "Panic" in {
            val ex = new Exception("test")
            assert(Result.panic(ex).show == s"Panic($ex)")
        }

        "nested Success" in {
            val nested = Result.success(Result.success(Result.success(23)))
            assert(nested.show == "Success(Success(Success(23)))")
            assert(k"$nested" == "Success(Success(Success(23)))")
            val widened: Result[Nothing, Result[Nothing, Result[Nothing, Int]]] = nested
            assert(k"$widened" == "Success(Success(Success(23)))")
        }

        "nested Success with failure" in {
            val nested = Result.success(Result.success(Result.fail("error")))
            assert(nested.show == "Success(Success(Fail(error)))")
            assert(k"$nested" == "Success(Success(Fail(error)))")
            val widened: Result[Nothing, Result[Nothing, Result[String, Nothing]]] = nested
            assert(k"$widened" == "Success(Success(Fail(error)))")
        }
    }

    "SuccessError.toString" - {
        "single level" in {
            val successError = Result.Success(Result.Fail("error"))
            assert(successError.toString == "Success(Fail(error))")
        }

        "multiple levels" in {
            val nested = Result.Success(Result.Success(Result.Success(Result.Fail("error"))))
            assert(nested.toString == "Success(Success(Success(Fail(error))))")
        }
    }

    "absent" in {
        val result = Result.absent[Int]
        assert(result == Fail(Absent))
        assert(result.isFail)
        assert(!result.isSuccess)
        assert(!result.isPanic)
    }

end ResultTest

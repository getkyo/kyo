package kyo

import java.io.IOException
import kyo.Result
import kyo.Result.*
import scala.util.Try

class ResultTest extends Test:

    val ex = new Exception

    val try2: Result[String, Result[String, Int]] = Success(Failure("error"))

    "should match Success containing Failure" in {
        val result = try2 match
            case Success(Failure(e)) => e
            case _                   => ""
        assert(result == "error")
    }

    "catching" - {

        "success" in {
            assert(Result.catching[Exception](1) == Success(1))
        }

        "fail" in {
            assert(Result.catching[Exception](throw ex) == Failure(ex))
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
        assert(Result.unit == Result.succeed(()))
    }

    "fromTry" - {
        "should return Success for successful Try" in {
            val tryValue = scala.util.Try(5)
            val result   = Result.fromTry(tryValue)
            assert(result == Result.succeed(5))
        }

        "should return Failure for failed Try" in {
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
            assert(result == Result.succeed(5))
        }

        "should return Failure for Left" in {
            val eitherValue = Left("Failure message")
            val result      = Result.fromEither(eitherValue)
            assert(result == Result.fail("Failure message"))
        }

        "should maintain type parameters" in {
            val result: Result[String, Int] = Result.fromEither(Right(5))
            assert(result == Result.succeed(5))

            val result2: Result[String, Int] = Result.fromEither(Left("Failure"))
            assert(result2 == Result.fail("Failure"))
        }
    }

    "value" - {
        "returns Present with the value for Success" in {
            assert(Result.succeed(42).value == Maybe(42))
        }

        "returns Absent for Failure" in {
            assert(Result.fail("error").value == Maybe.empty)
        }

        "returns Absent for Panic" in {
            assert(Result.panic(new Exception).value == Maybe.empty)
        }
    }

    "failure" - {
        "returns Present with the error for Failure" in {
            assert(Result.fail("error").failure == Maybe("error"))
        }

        "returns Absent for Success" in {
            assert(Result.succeed(42).failure == Maybe.empty)
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
            assert(Result.succeed(42).panic == Maybe.empty)
        }

        "returns Absent for Failure" in {
            assert(Result.fail("error").panic == Maybe.empty)
        }
    }

    "failureOrPanic" - {
        "should return Present with error for Failure" in {
            val ex     = new Exception("test")
            val result = Result.fail[Exception, Int](ex)
            assert(result.failureOrPanic.contains(ex))
        }

        "should return Present with exception for Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[Exception, Int](ex)
            assert(result.failureOrPanic.contains(ex))
        }

        "should return Absent for Success" in {
            val result = Result.succeed[String, Int](42)
            assert(result.failureOrPanic.isEmpty)
        }
    }

    "isSuccess" - {
        "returns true for Success" in {
            assert(Result.succeed(1).isSuccess)
        }
        "returns false for Failure" in {
            assert(!Result.fail(ex).isSuccess)
        }
        "returns false for Panic" in {
            assert(!Result.panic(ex).isSuccess)
        }
    }

    "isFail" - {
        "returns false for Success" in {
            assert(!Result.succeed(1).isFailure)
        }
        "returns true for Failure" in {
            assert(Result.fail(ex).isFailure)
        }
        "returns false for Panic" in {
            assert(!Result.panic(ex).isFailure)
        }
    }

    "isPanic" - {
        "returns false for Success" in {
            assert(!Result.succeed(1).isPanic)
        }
        "returns false for Failure" in {
            assert(!Result.fail(ex).isPanic)
        }
        "returns true for Panic" in {
            assert(Result.panic(ex).isPanic)
        }
    }

    "getOrElse" - {
        "returns the value for Success" in {
            assert(Result.succeed(1).getOrElse(0) == 1)
        }
        "returns the default value for Failure" in {
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
            assert(Result.succeed(1).getOrThrow == 1)
        }
        "doesn't compile for non-Throwable Failure" in {
            typeCheckFailure("Result.fail(1).getOrThrow")("value getOrThrow is not a member of kyo.Result")
        }
        "throws for Throwable Failure" in {
            assert(Result.catching[Exception](Result.fail(ex).getOrThrow) == Result.fail(ex))
        }
        "throws for Panic" in {
            assert(Result.catching[Exception](Result.panic(ex).getOrThrow) == Result.fail(ex))
        }
    }

    "orElse" - {
        "returns itself for Success" in {
            assert(Result.succeed(1).orElse(Result.succeed(2)) == Success(1))
        }
        "returns the alternative for Failure" in {
            assert(Result.fail(ex).orElse(Success(1)) == Success(1))
        }
        "returns the alternative for Panic" in {
            assert(Result.panic(ex).orElse(Success(1)) == Success(1))
        }
    }

    "flatMap" - {
        "applies the function for Success" in {
            assert(Result.succeed(1).flatMap(x => Result.succeed(x + 1)) == Success(2))
        }
        "does not apply the function for Failure" in {
            assert(Result.fail[String, Int]("error").flatMap(x => Success(x + 1)) == Failure("error"))
        }
        "does not apply the function for Panic" in {
            assert(Result.panic[String, Int](ex).flatMap(x => Success(x + 1)) == Panic(ex))
        }
    }

    "map" - {
        "applies the function for Success" in {
            assert(Result.succeed(1).map(_ + 1) == Success(2))
        }
        "does not apply the function for Failure" in {
            assert(Result.fail[String, Int]("error").map(_ + 1) == Failure("error"))
        }
        "does not apply the function for Panic" in {
            assert(Result.panic[String, Int](ex).map(_ + 1) == Panic(ex))
        }
    }

    "fold methods" - {
        val success = Result.succeed[String, Int](42)
        val failure = Result.fail[String, Int]("error")
        val ex      = new Exception("test")
        val panic   = Result.panic[String, Int](ex)

        "foldError" - {
            "should apply success function for Success" in {
                val result = success.foldError(_ + 1, _ => -1)
                assert(result == 43)
            }

            "should apply error function for both Failure and Panic" in {
                val failureResult = failure.foldError(_ => "success", _.toString)
                assert(failureResult == "Failure(error)")

                val panicResult = panic.foldError(_ => "success", _.toString)
                assert(panicResult == Panic(ex).toString)
            }
        }

        "foldOrThrow" - {
            "should apply success function for Success" in {
                val result = success.foldOrThrow(_ + 1, _ => -1)
                assert(result == 43)
            }

            "should apply failure function for Failure" in {
                val result = failure.foldOrThrow(_ => -1, _.length)
                assert(result == 5)
            }

            "should throw for Panic" in {
                assertThrows[Exception] {
                    panic.foldOrThrow(_ => 42, _ => -1)
                }
            }
        }

        "fold" - {
            "should apply success function for Success" in {
                val result = success.fold(v => s"success: $v", _ => "failure", _ => "panic")
                assert(result == "success: 42")
            }

            "should apply failure function for Failure" in {
                val result = failure.fold(_ => "success", e => s"failure: $e", _ => "panic")
                assert(result == "failure: error")
            }

            "should apply panic function for Panic" in {
                val result = panic.fold(_ => "success", _ => "failure", e => s"panic: ${e.getMessage}")
                assert(result == "panic: test")
            }

            "should handle exceptions in success function" in {
                val ex     = new RuntimeException("fold error")
                val result = success.fold(_ => throw ex, _ => "failure", e => s"panic: $e")
                assert(result == s"panic: $ex")
            }
        }
    }

    "filter" - {
        "adds NoSuchElementException" in {
            val x = Result.succeed(2).filter(_ % 2 == 0)
            discard(x)
            assertCompiles("val _: Result[NoSuchElementException, Int] = x")
        }
        "returns itself if the predicate holds for Success" in {
            assert(Result.succeed(2).filter(_ % 2 == 0) == Success(2))
        }
        "returns Failure if the predicate doesn't hold for Success" in {
            assert(Result.succeed(1).filter(_ % 2 == 0).isFailure)
        }
        "returns itself for Failure" in {
            assert(Result.fail[String, Int]("error").filter(_ => true) == Failure("error"))
        }
        "should handle exceptions in predicate" in {
            val result = Result.succeed(1).filter(_ => throw new RuntimeException("predicate error"))
            assert(result.isPanic)
        }
    }

    "toEither" - {
        "returns Right with the value for Success" in {
            assert(Result.succeed(1).toEither == Right(1))
        }
        "returns Left with the error for Failure" in {
            val error = "error"
            assert(Failure(error).toEither == Left(error))
        }
    }

    "deeply nested Result" - {
        val nestedResult = Success(Success(Success(Success(1))))

        "get should return the deeply nested value" in {
            assert(nestedResult.getOrThrow.getOrThrow.getOrThrow.getOrThrow == 1)
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
            assert(!nestedResult.isFailure)
        }

        "should handle exceptions in deeply nested transformations" in {
            val nested = Success(Success(Success(1)))
            val ex     = new RuntimeException("nested error")
            val result = nested.map(_.map(_.map(_ => throw ex)))
            assert(result == Panic(ex))
        }

        "should maintain correct error type through nested flatMaps" in {
            val r1: Result[String, Int]     = Success(1)
            val r2: Result[Int, String]     = Success("hello")
            val r3: Result[Double, Boolean] = Success(true)

            val result = r1.flatMap(x =>
                r2.flatMap(y =>
                    r3.map(_ => throw new RuntimeException("error"))
                )
            )

            val _: Result[String | Int | Double, String] = result
            assert(result.isPanic)
        }
    }

    "exception" - {
        "only available if E is Throwable" in {
            typeCheckFailure("Result.Failure(1).exception")("value exception is not a member of kyo.Result.Failure")
        }
        "from Failure" in {
            val ex = new Exception
            assert(Result.Failure(ex).exception == ex)
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

        "Failure to Try" - {
            "Throwable error" in {
                val failure: Result[Exception, Int] = Failure(ex)
                val tryResult                       = failure.toTry
                assert(tryResult.isFailure)
                assert(tryResult.failed.get == ex)
            }
            "Nothing error" in {
                val failure: Result[Nothing, Int] = Result.succeed(1)
                val tryResult                     = failure.toTry
                assert(tryResult == Try(1))
            }
            "fails to compile for non-Throwable error" in {
                val failure: Result[String, Int] = Failure("Something went wrong")
                val _                            = failure
                typeCheckFailure("failure.toTry")("Failure type must be a 'Throwable' to invoke 'toTry'. Found: 'String'")
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
            assert(result.getOrThrow == null)
        }

        "Success with null value flatMap" in {
            val result: Result[Nothing, String] = Success(null)
            val flatMapped                      = result.flatMap(str => Success(s"mapped: $str"))
            assert(flatMapped == Success("mapped: null"))
        }

        "Failure with null error" in {
            val result: Result[String, Int] = Failure(null)
            assert(result == Failure(null))
        }

        "Failure with null exception flatMap" in {
            val result: Result[String, Int] = Failure(null)
            val flatMapped                  = result.flatMap(num => Success(num + 1))
            assert(flatMapped == Failure(null))
        }

        "Failure with null exception map" in {
            val result: Result[String, Int] = Failure(null)
            val mapped                      = result.map(num => num + 1)
            assert(mapped == Failure(null))
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
                    case Failure(_)          => "failure"
                assert(result == "greater than 1")
            }

            "should match Failure with a guard" in {
                val tryy: Result[String, Int] = Failure("error")
                val result = tryy match
                    case Failure(e) if e.length > 5 => "long error"
                    case Failure(_)                 => "short error"
                    case Success(_)                 => "success"
                assert(result == "short error")
            }
        }
        "Error.unapply" - {
            "should match Failure" in {
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
                val result = Result.succeed(1)
                result match
                    case Error(_) => fail()
                    case _        => succeed
            }
        }

    }

    "inference" - {
        "flatMap" in {
            val result: Result[String, Int]    = Result.succeed(5)
            val mapped: Result[String, String] = result.flatMap(x => Result.succeed(x.toString))
            assert(mapped == Success("5"))
        }

        "flatMap with different error types" in {
            val r1: Result[String, Int]              = Result.succeed(5)
            val r2: Result[Int, String]              = Result.succeed("hello")
            val nested: Result[String | Int, String] = r1.flatMap(x => r2.map(y => s"$x $y"))
            assert(nested == Success("5 hello"))
        }

        "for-comprehension with multiple flatMaps" in {
            def divideIfEven(x: Int): Result[String, Int] =
                if x % 2 == 0 then Result.succeed(10 / x) else Result.fail("Odd number")

            val complex: Result[String, String] =
                for
                    a <- Result.succeed(4)
                    b <- divideIfEven(a)
                    c <- Result.succeed(b * 2)
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
                    tryy.foldError(_ => throw exception, _ => throw exception)
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
                    tryy.foldError(_ => throw exception, _ => throw exception)
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
                    tryy.foldError(_ => throw exception, _ => 0)
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

        "nested Success containing Failure" in {
            val nested: Result[String, Result[Int, String]] = Result.succeed(Result.fail(42))
            val flattened                                   = nested.flatten

            assert(flattened == Failure(42))
        }

        "empty Success" in {
            val empty: Result[Nothing, Unit] = Result.succeed(())
            assert(empty == Success(()))
        }

        "Panic distinct from Failure" in {
            val exception = new RuntimeException("Unexpected error")
            val panic     = Result.panic(exception)
            assert(!panic.isFailure)
            assert(panic match
                case Panic(_) => true
                case _        => false)
        }

        "deeply nested Success/Failure" in {
            val deeplyNested = Success(Success(Success(Failure("deep error"))))
            assert(deeplyNested.flatten == Success(Success(Failure("deep error"))))
            assert(deeplyNested.flatten.flatten == Success(Failure("deep error")))
            assert(deeplyNested.flatten.flatten.flatten == Failure("deep error"))
        }

        "Panic propagation through flatMap" in {
            val panic  = Result.panic(new RuntimeException("Unexpected"))
            val result = panic.flatMap(_ => Success(1)).flatMap(_ => Failure("won't happen"))
            assert(result == panic)
        }

        "Failure type widening" in {
            val r1: Result[String, Int] = Failure("error1")
            val r2: Result[Int, String] = Failure(42)
            val combined                = r1.orElse(r2)
            assert(combined.isFailure)
            assert(combined match
                case Failure(_: String | _: Int) => true
                case _                           => false)
        }

        "nested flatMap with type changes" in {
            def f(i: Int): Result[String, Int] =
                if i > 0 then Success(i) else Failure("non-positive")
            def g(d: Int): Result[Int, String] =
                if d < 10 then Success(d.toString) else Failure(d.toInt)

            val result = Success(5).flatMap(f).flatMap(g)
            assert(result == Success("5"))

            val result2 = Success(-1).flatMap(f).flatMap(g)
            assert(result2 == Failure("non-positive"))

            val result3 = Success(20).flatMap(f).flatMap(g)
            assert(result3 == Failure(20))
        }
    }

    "swap" - {
        "Success to Failure" in {
            val result = Result.succeed[String, Int](42)
            assert(result.swap == Result.fail(42))
        }

        "Failure to Success" in {
            val result = Result.fail[String, Int]("error")
            assert(result.swap == Result.succeed("error"))
        }

        "Panic remains Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[Int, String](ex)
            assert(result.swap == Result.panic(ex))
        }

        "nested Results" in {
            val nested = Result.succeed[Int, Result[String, Boolean]](Result.fail("inner"))
            assert(nested.swap == Result.fail(Result.fail("inner")))
        }

        "type inference" in {
            val result: Result[Int, String]  = Result.succeed("hello")
            val swapped: Result[String, Int] = result.swap
            assert(swapped == Result.fail("hello"))
        }

        "idempotence" in {
            val success = Result.succeed[String, Int](42)
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
                    x <- Result.succeed(1)
                    y <- Result.succeed(2)
                    z <- Result.succeed(3)
                yield x + y + z

            assert(result == Result.succeed(6))
        }

        "short-circuit on Failure" in {
            val result =
                for
                    x <- Result.succeed(1)
                    y <- Result.fail[Exception, Int](new Exception("error"))
                    z <- Result.succeed(3)
                yield x + y + z

            assert(result.isFailure)
        }

        "handle exceptions in the yield" in {
            val result =
                for
                    _ <- Result.succeed(1)
                    _ <- Result.succeed(2)
                yield throw new Exception("error")

            assert(result.isPanic)
        }

        "sequence operations with flatMap" in {
            val result =
                for
                    x <- Result.succeed(1)
                    y <- Result.succeed(2)
                    if y > 0
                    z <- Result.succeed(3)
                yield x + y + z

            assert(result == Result.succeed(6))
        }

        "fail the comprehension with a guard" in {
            val result =
                for
                    x <- Result.succeed(1)
                    y <- Result.succeed(-1)
                    if y > 0
                yield x + y

            assert(result.isFailure)
        }
    }

    "mapError" - {
        "should not change Success" in {
            val result = Result.succeed[String, Int](5)
            val mapped = result.mapError(_ => 42)
            assert(mapped == Success(5))
        }

        "should apply the function to both Failure and Panic" in {
            val failure = Result.fail[String, Int]("error")
            val panic   = Result.panic[String, Int](new Exception("panic"))

            assert(failure.mapError(_.toString.length) == Failure(14))
            assert(panic.mapError(_.toString.length) == Failure(33))
        }

        "should handle exceptions in mapping function" in {
            val result = Result.fail[String, Int]("error")
            val mapped = result.mapError(_ => throw new RuntimeException("Mapping error"))
            assert(mapped.isPanic)
        }
    }

    "mapFailure" - {
        "should not change Success" in {
            val result = Result.succeed[String, Int](5)
            val mapped = result.mapFailure(_ => 42)
            assert(mapped == Success(5))
        }

        "should apply the function to Failure" in {
            val result = Result.fail[String, Int]("error")
            val mapped = result.mapFailure(_.length)
            assert(mapped == Failure(5))
        }

        "should not change Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, Int](ex)
            val mapped = result.mapFailure(_ => 42)
            assert(mapped == Panic(ex))
        }

        "should allow changing the error type" in {
            val result: Result[String, Int] = Result.fail("error")
            val mapped: Result[Int, Int]    = result.mapFailure(_.length)
            assert(mapped == Failure(5))
        }

        "should handle exceptions in the mapping function" in {
            val result = Result.fail[String, Int]("error")
            val mapped = result.mapFailure(_ => throw new RuntimeException("Mapping error"))
            assert(mapped.isPanic)
        }

        "should work with for-comprehensions" in {
            val result =
                for
                    x <- Result.succeed[String, Int](5)
                    y <- Result.fail[String, Int]("error")
                yield x + y

            val mapped = result.mapFailure(_.toUpperCase)
            assert(mapped == Failure("ERROR"))
        }
    }

    "mapPanic" - {
        "should not change Success" in {
            val result = Result.succeed[String, Int](5)
            val mapped = result.mapPanic(_ => "mapped")
            assert(mapped == Success(5))
        }

        "should not change Failure" in {
            val result = Result.fail[String, Int]("error")
            val mapped = result.mapPanic(_ => "mapped")
            assert(mapped == Failure("error"))
        }

        "should apply the function to Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, Int](ex)
            val mapped = result.mapPanic(_.getMessage)
            assert(mapped == Failure("test"))
        }

        "should handle exceptions in mapping function" in {
            val result = Result.panic[String, Int](new Exception("original"))
            val ex     = new RuntimeException("Mapping error")
            val mapped = result.mapPanic(_ => throw ex)
            assert(mapped == Panic(ex))
        }

        "should handle chained flatMapPanic" in {
            val ex1 = new Exception("first")
            val ex2 = new Exception("second")

            val result = Result.panic[String, Int](ex1)
                .flatMapPanic { _ =>
                    Result.panic(ex2)
                }
                .flatMapPanic { _ =>
                    Result.succeed(42)
                }

            assert(result == Success(42))
        }
    }

    "flatMapError" - {
        "should not change Success" in {
            val result = Result.succeed[String, Int](5)
            val mapped = result.flatMapError(_ => Result.fail(42))
            assert(mapped == Success(5))
        }

        "should apply function to Failure" in {
            val result = Result.fail[String, String]("error")
            val mapped = result.flatMapError(s => Result.succeed(s"Mapped: $s"))
            assert(mapped == Success("Mapped: error"))
        }

        "should apply function to Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, String](ex)
            val mapped = result.flatMapError {
                case e: Exception => Result.succeed(e.getMessage)
                case _            => Result.fail("unexpected")
            }
            assert(mapped == Success("test"))
        }

        "should handle exceptions in mapping function" in {
            val result = Result.fail[String, Int]("error")
            val ex     = new RuntimeException("Mapping error")
            val mapped = result.flatMapError(_ => throw ex)
            assert(mapped == Panic(ex))
        }

        "with specific error type" - {
            class TestException1(msg: String) extends Exception(msg)
            class TestException2(msg: String) extends Exception(msg)
            class TestException3(msg: String) extends Exception(msg)

            "should handle specific exception from union" in {
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(new TestException1("error"))

                val handled = result.flatMapError[TestException1] { e =>
                    Result.succeed(42)
                }

                val _: Result[TestException2 | TestException3, Int] = handled

                assert(handled == Success(42))
            }

            "should not handle non-matching exception from union" in {
                val ex = new TestException2("error")
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(ex)

                val handled = result.flatMapError[TestException1] { e =>
                    Result.succeed(42)
                }

                val _: Result[TestException2 | TestException3, Int] = handled

                assert(handled.failure.contains(ex))
            }

            "should handle specific all exception from union" in {
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(new TestException1("error"))

                val handled = result.flatMapError { e =>
                    Result.succeed(42)
                }

                val _: Result[Nothing, Int] = handled

                assert(handled == Success(42))
            }

            "should handle union of exceptions" in {
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(new TestException2("error"))

                val handled = result.flatMapError[TestException1 | TestException2] { e =>
                    Result.succeed(42)
                }

                val _: Result[TestException3, Int] = handled

                assert(handled == Success(42))
            }

            "should not handle exception outside of specified union" in {
                val ex = new TestException3("error")
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(ex)

                val handled = result.flatMapError[TestException1 | TestException2] { e =>
                    Result.succeed(42)
                }

                val _: Result[TestException3, Int] = handled

                assert(handled.failure.contains(ex))
            }

            "should handle intersection of exception types" in {
                trait CustomException
                class CustomRuntimeException extends RuntimeException("test") with CustomException

                val result: Result[RuntimeException & CustomException, Int] =
                    Result.fail(new CustomRuntimeException)

                val handled = result.flatMapError[RuntimeException & CustomException] { e =>
                    Result.succeed(42)
                }

                val _: Result[Nothing, Int] = handled

                assert(handled == Success(42))
            }
        }
    }

    "flatMapFailure" - {
        "should not change Success" in {
            val result = Result.succeed[String, Int](5)
            val mapped = result.flatMapFailure(_ => Result.fail(42))
            assert(mapped == Success(5))
        }

        "should apply function to Failure" in {
            val result = Result.fail[String, String]("error")
            val mapped = result.flatMapFailure(s => Result.succeed(s"Mapped: $s"))
            assert(mapped == Success("Mapped: error"))
        }

        "should not change Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, String](ex)
            val mapped = result.flatMapFailure(_ => Result.succeed("mapped"))
            assert(mapped == Panic(ex))
        }

        "should handle exceptions in mapping function" in {
            val result = Result.fail[String, Int]("error")
            val ex     = new RuntimeException("Mapping error")
            val mapped = result.flatMapFailure(_ => throw ex)
            assert(mapped == Panic(ex))
        }

        "with specific error type" - {
            class TestException1(msg: String) extends Exception(msg)
            class TestException2(msg: String) extends Exception(msg)
            class TestException3(msg: String) extends Exception(msg)

            "should handle specific exception from union" in {
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(new TestException1("error"))

                val handled = result.flatMapFailure[TestException1] { e =>
                    Result.succeed(42)
                }

                val _: Result[TestException2 | TestException3, Int] = handled

                assert(handled == Success(42))
            }

            "should not handle non-matching exception from union" in {
                val ex = new TestException2("error")
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(ex)

                val handled = result.flatMapFailure[TestException1] { e =>
                    Result.succeed(42)
                }

                val _: Result[TestException2 | TestException3, Int] = handled

                assert(handled.failure.contains(ex))
            }

            "should handle union of exceptions" in {
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(new TestException2("error"))

                val handled = result.flatMapFailure[TestException1 | TestException2] { e =>
                    Result.succeed(42)
                }

                val _: Result[TestException3, Int] = handled

                assert(handled == Success(42))
            }

            "should not handle exception outside of specified union" in {
                val ex = new TestException3("error")
                val result: Result[TestException1 | TestException2 | TestException3, Int] =
                    Result.fail(ex)

                val handled = result.flatMapFailure[TestException1 | TestException2] { e =>
                    Result.succeed(42)
                }

                val _: Result[TestException3, Int] = handled

                assert(handled.failure.contains(ex))
            }

            "should handle intersection of exception types" in {
                trait CustomException
                class CustomRuntimeException extends RuntimeException("test") with CustomException

                val result: Result[RuntimeException & CustomException, Int] =
                    Result.fail(new CustomRuntimeException)

                val handled = result.flatMapFailure[RuntimeException & CustomException] { e =>
                    Result.succeed(42)
                }

                val _: Result[Nothing, Int] = handled

                assert(handled == Success(42))
            }
        }
    }

    "flatMapPanic" - {
        "should not change Success" in {
            val result = Result.succeed[String, Int](5)
            val mapped = result.flatMapPanic(_ => Result.fail("mapped"))
            assert(mapped == Success(5))
        }

        "should not change Failure" in {
            val result = Result.fail[String, Int]("error")
            val mapped = result.flatMapPanic(_ => Result.succeed(42))
            assert(mapped == Failure("error"))
        }

        "should apply function to Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, Int](ex)
            val mapped = result.flatMapPanic(e => Result.succeed(42))
            assert(mapped == Success(42))
        }

        "should handle exceptions in mapping function" in {
            val result = Result.panic[String, Int](new Exception("original"))
            val ex     = new RuntimeException("Mapping error")
            val mapped = result.flatMapPanic(_ => throw ex)
            assert(mapped == Panic(ex))
        }

        "should handle chained flatMapPanic" in {
            val ex1 = new Exception("first")
            val ex2 = new Exception("second")

            val result = Result.panic[String, Int](ex1)
                .flatMapPanic { _ =>
                    Result.panic(ex2)
                }
                .flatMapPanic { _ =>
                    Result.succeed(42)
                }

            assert(result == Success(42))
        }
    }

    "collect" - {
        "all Success results" in {
            val results = Seq(
                Result.succeed(1),
                Result.succeed(2),
                Result.succeed(3)
            )
            val collected = Result.collect(results)
            assert(collected == Success(Seq(1, 2, 3)))
        }

        "first Failure encountered" in {
            val results = Seq(
                Result.succeed(1),
                Result.fail("error"),
                Result.succeed(3)
            )
            val collected = Result.collect(results)
            assert(collected == Failure("error"))
        }

        "Panic encountered" in {
            val ex = new Exception("panic")
            val results = Seq(
                Result.succeed(1),
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
                Result.succeed(1),
                Result.fail("string error"),
                Result.fail(42),
                Result.succeed(3)
            )
            val collected: Result[String | Int, Seq[Int]] =
                Result.collect(results)
            assert(collected.isFailure)
            assert(collected.failure.get.equals("string error"))
        }

        "mixed Success and Failure with different error types" in {
            val results: Seq[Result[Any, Int]] = Seq(
                Result.succeed(1),
                Result.fail("string error"),
                Result.succeed(2),
                Result.fail(42),
                Result.succeed(3)
            )
            val collected = Result.collect(results)
            assert(collected.isFailure)
            assert(collected.failure.get.equals("string error"))
        }

        "should handle exceptions during sequence traversal" in {
            val results = new Seq[Result[String, Int]]:
                def length: Int                          = throw new RuntimeException("length error")
                def apply(idx: Int): Result[String, Int] = Result.succeed(idx)
                def iterator: Iterator[Result[String, Int]] =
                    throw new RuntimeException("iterator error")
            val collected = Result.collect(results)
            assert(collected.isPanic)
        }
    }

    "contains" - {
        "should return true for Success with matching value" in {
            val result = Result.succeed(42)
            assert(result.contains(42))
        }

        "should return false for Success with non-matching value" in {
            val result = Result.succeed(42)
            assert(!result.contains(43))
        }

        "should return false for Failure" in {
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
            val result = Result.succeed(person)
            assert(result.contains(person))
            assert(!result.contains(Person("Bob", 25)))
        }

        "should work with Maybe values" in {
            val someMaybeResult = Result.succeed(Maybe(42))
            assert(someMaybeResult.contains(Maybe(42)))
            assert(!someMaybeResult.contains(Maybe(43)))
            assert(!someMaybeResult.contains(Maybe.empty))

            val noneMaybeResult = Result.succeed(Maybe.empty)
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
            val nestedSuccessResult: Result[String, Result[Int, String]] = Result.succeed(Result.succeed("nested"))
            assert(nestedSuccessResult.contains(Result.succeed("nested")))
            assert(!nestedSuccessResult.contains(Result.succeed("other")))
            assert(!nestedSuccessResult.contains(Result.fail(42)))

            val nestedFailResult: Result[String, Result[Int, String]] = Result.succeed(Result.fail(42))
            assert(nestedFailResult.contains(Result.fail(42)))
            assert(!nestedFailResult.contains(Result.succeed("nested")))

            val deeplyNestedResult: Result[String, Result[Int, Result[Double, String]]] =
                Result.succeed(Result.succeed(Result.succeed("deeply nested")))
            assert(deeplyNestedResult.contains(Result.succeed(Result.succeed("deeply nested"))))
            assert(!deeplyNestedResult.contains(Result.succeed(Result.fail(3.14))))

            val outerFailResult: Result[String, Result[Int, String]] = Result.fail("outer error")
            assert(!outerFailResult.contains(Result.succeed("nested")))
            assert(!outerFailResult.contains(Result.fail(42)))
        }
    }

    "unit" - {
        "should convert Success to Success(())" in {
            val result = Result.succeed(42).unit
            assert(result == Success(()))
        }

        "should not change Failure" in {
            val result = Result.fail[String, Int]("error").unit
            assert(result == Failure("error"))
        }

        "should not change Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, Int](ex).unit
            assert(result == Panic(ex))
        }
    }

    "exists" - {
        "should return true for Success when predicate holds" in {
            val result = Result.succeed(42)
            assert(result.exists(_ > 0))
        }

        "should return false for Success when predicate doesn't hold" in {
            val result = Result.succeed(42)
            assert(!result.exists(_ < 0))
        }

        "should return false for Failure" in {
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
            val result = Result.succeed(42)
            assert(result.forall(_ > 0))
        }

        "should return false for Success when predicate doesn't hold" in {
            val result = Result.succeed(42)
            assert(!result.forall(_ < 0))
        }

        "should return true for Failure" in {
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
            assert(Result.succeed(42).show == "Success(42)")
        }

        "Failure" in {
            assert(Result.fail("error").show == "Failure(error)")
        }

        "Panic" in {
            val ex = new Exception("test")
            assert(Result.panic(ex).show == s"Panic($ex)")
        }

        "nested Success" in {
            val nested = Result.succeed(Result.succeed(Result.succeed(23)))
            assert(nested.show == "Success(Success(Success(23)))")
            assert(t"$nested".show == "Success(Success(Success(23)))")
            val widened: Result[Nothing, Result[Nothing, Result[Nothing, Int]]] = nested
            assert(widened.show == "Success(Success(Success(23)))")
            assert(t"$widened".show == "Success(Success(Success(23)))")
        }

        "nested Success with failure" in {
            val nested = Result.succeed(Result.succeed(Result.fail("error")))
            assert(nested.show == "Success(Success(Failure(error)))")
            assert(t"$nested".show == "Success(Success(Failure(error)))")
            val widened: Result[Nothing, Result[Nothing, Result[String, Nothing]]] = nested
            assert(widened.show == "Success(Success(Failure(error)))")
            assert(t"$widened".show == "Success(Success(Failure(error)))")
        }
    }

    "SuccessError.toString" - {
        "single level" in {
            val successError = Result.Success(Result.Failure("error"))
            assert(successError.toString == "Success(Failure(error))")
        }

        "multiple levels" in {
            val nested = Result.Success(Result.Success(Result.Success(Result.Failure("error"))))
            assert(nested.toString == "Success(Success(Success(Failure(error))))")
        }
    }

    "absent" in {
        val result = Result.absent[Int]
        assert(result == Failure(Absent))
        assert(result.isFailure)
        assert(!result.isSuccess)
        assert(!result.isPanic)
    }

    "Result.Partial" - {
        "construct" in {
            val success: Partial[String, Int] = Success(23)
            val failure: Partial[String, Int] = Failure("failed")
            succeed
        }

        "is Result" in {
            val success: Partial[String, Int]   = Success(23)
            val successRes: Result[String, Int] = success
            val failure: Partial[String, Int]   = Failure("failed")
            val failureRes: Result[String, Int] = failure
            assert(success == successRes && failure == failureRes)
        }

        "Result API" in {
            val success: Partial[String, Int] = Success(23)
            val failure: Partial[String, Int] = Failure("failed")

            val successValue   = success.value
            val failureValue   = failure.value
            val successFailure = success.failure
            val failureFailure = failure.failure

            assert(
                successValue == Present(23)
                    && failureValue.isEmpty
                    && successFailure.isEmpty
                    && failureFailure == Present("failed")
            )
        }

        "foldPartial" in {
            val success: Partial[String, Int] = Success(23)
            val failure: Partial[String, Int] = Failure("failed")
            val onFail                        = (s: String) => s.length < 5 // will be false for "failed"
            val onSuccess                     = (i: Int) => i > 5           // will be true for 23
            val foldedSuccess                 = success.foldPartial(onSuccess, onFail)
            val foldedFailure                 = failure.foldPartial(onSuccess, onFail)
            assert(foldedSuccess && !foldedFailure)
        }

        "toEitherPartial" in {
            val success: Partial[String, Int] = Success(23)
            val failure: Partial[String, Int] = Failure("failed")
            assert(
                success.toEitherPartial == Right(23)
                    && failure.toEitherPartial == Left("failed")
            )
        }

        "flattenPartial" in {
            val success: Partial[String, Partial[Int, Boolean]]  = Success(Success(true))
            val failure1: Partial[String, Partial[Int, Boolean]] = Success(Failure(0))
            val failure2: Partial[String, Partial[Int, Boolean]] = Failure("failed")
            assert(
                success.flattenPartial == Success(true)
                    && failure1.flattenPartial == Failure(0)
                    && failure2.flattenPartial == Failure("failed")
            )
        }

    }

end ResultTest

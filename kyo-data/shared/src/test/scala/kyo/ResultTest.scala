package kyo

import kyo.Result
import kyo.Result.*
import scala.util.Try

class ResultTest extends Test:

    val ex = new Exception

    val try2: Result[String, Result[String, Int]] = Success(Error("error"))

    "should match Success containing Error" in {
        val result = try2 match
            case Success(Error(e)) => e
            case _                 => ""
        assert(result == "error")
    }

    "catching" - {
        "success" in {
            assert(Result.catching[Exception](1) == Success(1))
        }
        "fail" in {
            assert(Result.catching[Exception](throw ex) == Error(ex))
        }
        "panic" in {
            assert(Result.catching[IllegalArgumentException](throw ex) == Panic(ex))
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

        "should return Error for failed Try" in {
            val exception = new RuntimeException("Test exception")
            val tryValue  = scala.util.Try(throw exception)
            val result    = Result.fromTry(tryValue)
            assert(result == Result.error(exception))
        }
    }

    "fromEither" - {
        "should return Success for Right" in {
            val eitherValue = Right(5)
            val result      = Result.fromEither(eitherValue)
            assert(result == Result.succeed(5))
        }

        "should return Error for Left" in {
            val eitherValue = Left("Error message")
            val result      = Result.fromEither(eitherValue)
            assert(result == Result.error("Error message"))
        }

        "should maintain type parameters" in {
            val result: Result[String, Int] = Result.fromEither(Right(5))
            assert(result == Result.succeed(5))

            val result2: Result[String, Int] = Result.fromEither(Left("Error"))
            assert(result2 == Result.error("Error"))
        }
    }

    "value" - {
        "returns Defined with the value for Success" in {
            assert(Result.succeed(42).value == Maybe(42))
        }

        "returns Empty for Error" in {
            assert(Result.error("error").value == Maybe.empty)
        }

        "returns Empty for Panic" in {
            assert(Result.panic(new Exception).value == Maybe.empty)
        }
    }

    "failure" - {
        "returns Defined with the error for Error" in {
            assert(Result.error("error").failure == Maybe("error"))
        }

        "returns Empty for Success" in {
            assert(Result.succeed(42).failure == Maybe.empty)
        }

        "returns Empty for Panic" in {
            assert(Result.panic(new Exception).failure == Maybe.empty)
        }
    }

    "panic" - {
        "returns Defined with the exception for Panic" in {
            val ex = new Exception("test")
            assert(Result.panic(ex).panic == Maybe(ex))
        }

        "returns Empty for Success" in {
            assert(Result.succeed(42).panic == Maybe.empty)
        }

        "returns Empty for Error" in {
            assert(Result.error("error").panic == Maybe.empty)
        }
    }

    "isSuccess" - {
        "returns true for Success" in {
            assert(Result.succeed(1).isSuccess)
        }
        "returns false for Error" in {
            assert(!Result.error(ex).isSuccess)
        }
        "returns false for Panic" in {
            assert(!Result.panic(ex).isSuccess)
        }
    }

    "isFail" - {
        "returns false for Success" in {
            assert(!Result.succeed(1).isFail)
        }
        "returns true for Error" in {
            assert(Result.error(ex).isFail)
        }
        "returns false for Panic" in {
            assert(!Result.panic(ex).isFail)
        }
    }

    "isPanic" - {
        "returns false for Success" in {
            assert(!Result.succeed(1).isPanic)
        }
        "returns false for Error" in {
            assert(!Result.error(ex).isPanic)
        }
        "returns true for Panic" in {
            assert(Result.panic(ex).isPanic)
        }
    }

    "get" - {
        "returns the value for Success" in {
            assert(Result.succeed(1).get == 1)
        }
        "can't be called for Error" in {
            assertDoesNotCompile("Result.error(ex).get")
        }
        "throws an exception for Panic" in {
            assertThrows[Exception](Result.panic(ex).get)
        }
    }

    "getOrElse" - {
        "returns the value for Success" in {
            assert(Result.succeed(1).getOrElse(0) == 1)
        }
        "returns the default value for Error" in {
            assert(Result.error(ex).getOrElse(0) == 0)
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
        "doesn't compile for non-Throwable Error" in {
            assertDoesNotCompile("Result.error(1).getOrThrow")
        }
        "throws for Throwable Error" in {
            assert(Result.catching[Exception](Result.error(ex).getOrThrow) == Result.error(ex))
        }
        "throws for Panic" in {
            assert(Result.catching[Exception](Result.panic(ex).getOrThrow) == Result.error(ex))
        }
    }

    "orElse" - {
        "returns itself for Success" in {
            assert(Result.succeed(1).orElse(Result.succeed(2)) == Success(1))
        }
        "returns the alternative for Error" in {
            assert(Result.error(ex).orElse(Success(1)) == Success(1))
        }
        "returns the alternative for Panic" in {
            assert(Result.panic(ex).orElse(Success(1)) == Success(1))
        }
    }

    "flatMap" - {
        "applies the function for Success" in {
            assert(Result.succeed(1).flatMap(x => Result.succeed(x + 1)) == Success(2))
        }
        "does not apply the function for Error" in {
            assert(Result.error[String, Int]("error").flatMap(x => Success(x + 1)) == Error("error"))
        }
        "does not apply the function for Panic" in {
            assert(Result.panic[String, Int](ex).flatMap(x => Success(x + 1)) == Panic(ex))
        }
    }

    "map" - {
        "applies the function for Success" in {
            assert(Result.succeed(1).map(_ + 1) == Success(2))
        }
        "does not apply the function for Error" in {
            assert(Result.error[String, Int]("error").map(_ + 1) == Error("error"))
        }
        "does not apply the function for Panic" in {
            assert(Result.panic[String, Int](ex).map(_ + 1) == Panic(ex))
        }
    }

    "fold" - {
        "applies the success function for Success" in {
            assert(Result.succeed(1).fold(_ => 0)(x => x + 1) == 2)
        }
        "applies the failure function for Failure" in {
            assert(Result.error[String, Int]("error").fold(_ => 0)(x => x) == 0)
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
        "returns Error if the predicate doesn't hold for Success" in {
            assert(Result.succeed(1).filter(_ % 2 == 0).isFail)
        }
        "returns itself for Error" in {
            assert(Result.error[String, Int]("error").filter(_ => true) == Error("error"))
        }
    }

    "recover" - {
        "returns itself for Success" in {
            assert(Result.succeed(1).recover { case _ => 0 } == Success(1))
        }
        "returns Success with the mapped value if the partial function is defined for Error" in {
            assert(Result.error("error").recover { case _ => 0 } == Success(0))
        }
        "returns itself if the partial function is not defined for Error" in {
            assert(Result.error("error").recover { case _ if false => 0 } == Error("error"))
        }
    }

    "recoverWith" - {
        "returns itself for Success" in {
            assert(Result.succeed(1).recoverWith { case _ => Success(0) } == Success(1))
        }
        "returns the mapped Result if the partial function is defined for Error" in {
            assert(Error("error").recoverWith { case _ => Success(0) } == Success(0))
        }
        "returns itself if the partial function is not defined for Error" in {
            val error = Error("error")
            assert(Error(error).recoverWith { case _ if false => Success(0) } == Error(error))
        }
    }

    "toEither" - {
        "returns Right with the value for Success" in {
            assert(Result.succeed(1).toEither == Right(1))
        }
        "returns Left with the error for Error" in {
            val error = "error"
            assert(Error(error).toEither == Left(error))
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
            assertDoesNotCompile("Result.Error(1).exception")
        }
        "from Error" in {
            val ex = new Exception
            assert(Result.Error(ex).exception == ex)
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

        "Error to Try" - {
            "Throwable error" in {
                val failure: Result[Exception, Int] = Error(ex)
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
                val failure: Result[String, Int] = Error("Something went wrong")
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

        "Error with null error" in {
            val result: Result[String, Int] = Error(null)
            assert(result == Error(null))
        }

        "Error with null exception flatMap" in {
            val result: Result[String, Int] = Error(null)
            val flatMapped                  = result.flatMap(num => Success(num + 1))
            assert(flatMapped == Error(null))
        }

        "Error with null exception map" in {
            val result: Result[String, Int] = Error(null)
            val mapped                      = result.map(num => num + 1)
            assert(mapped == Error(null))
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
                    case Error(_)            => "failure"
                assert(result == "greater than 1")
            }

            "should match Error with a guard" in {
                val tryy: Result[String, Int] = Error("error")
                val result = tryy match
                    case Error(e) if e.length > 5 => "long error"
                    case Error(_)                 => "short error"
                    case Success(_)               => "success"
                assert(result == "short error")
            }
        }
        "Error.unapply" - {
            "should match Error" in {
                val result = Result.error("FAIL!")
                result match
                    case Failure(_) => succeed
                    case _          => fail()
            }
            "should match Panic" in {
                val result = Result.panic(new AssertionError)
                result match
                    case Failure(_) => succeed
                    case _          => fail()
            }
            "should not match Success" in {
                val result = Result.succeed(1)
                result match
                    case Failure(_) => fail()
                    case _          => succeed
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
                if x % 2 == 0 then Result.succeed(10 / x) else Result.error("Odd number")

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

        "nested Success containing Error" in {
            val nested: Result[String, Result[Int, String]] = Result.succeed(Result.error(42))
            val flattened                                   = nested.flatten

            assert(flattened == Error(42))
        }

        "recover with a partial function" in {
            val result: Result[String, Int] = Result.error("error")
            val recovered = result.recover {
                case Error("error") => 0
                case _              => -1
            }

            assert(recovered == Success(0))
        }

        "empty Success" in {
            val empty: Result[Nothing, Unit] = Result.succeed(())
            assert(empty == Success(()))
        }

        "Panic distinct from Error" in {
            val exception = new RuntimeException("Unexpected error")
            val panic     = Result.panic(exception)
            assert(!panic.isFail)
            assert(panic match
                case Panic(_) => true
                case _        => false
            )
        }

        "deeply nested Success/Error" in {
            val deeplyNested = Success(Success(Success(Error("deep error"))))
            assert(deeplyNested.flatten == Success(Success(Error("deep error"))))
            assert(deeplyNested.flatten.flatten == Success(Error("deep error")))
            assert(deeplyNested.flatten.flatten.flatten == Error("deep error"))
        }

        "Panic propagation through flatMap" in {
            val panic  = Result.panic(new RuntimeException("Unexpected"))
            val result = panic.flatMap(_ => Success(1)).flatMap(_ => Error("won't happen"))
            assert(result == panic)
        }

        "Error type widening" in {
            val r1: Result[String, Int] = Error("error1")
            val r2: Result[Int, String] = Error(42)
            val combined                = r1.orElse(r2)
            assert(combined.isFail)
            assert(combined match
                case Error(_: String | _: Int) => true
                case _                         => false
            )
        }

        "nested flatMap with type changes" in {
            def f(i: Int): Result[String, Int] =
                if i > 0 then Success(i) else Error("non-positive")
            def g(d: Int): Result[Int, String] =
                if d < 10 then Success(d.toString) else Error(d.toInt)

            val result = Success(5).flatMap(f).flatMap(g)
            assert(result == Success("5"))

            val result2 = Success(-1).flatMap(f).flatMap(g)
            assert(result2 == Error("non-positive"))

            val result3 = Success(20).flatMap(f).flatMap(g)
            assert(result3 == Error(20))
        }
    }

    "swap" - {
        "Success to Error" in {
            val result = Result.succeed[String, Int](42)
            assert(result.swap == Result.error(42))
        }

        "Error to Success" in {
            val result = Result.error[String, Int]("error")
            assert(result.swap == Result.succeed("error"))
        }

        "Panic remains Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[Int, String](ex)
            assert(result.swap == Result.panic(ex))
        }

        "nested Results" in {
            val nested = Result.succeed[Int, Result[String, Boolean]](Result.error("inner"))
            assert(nested.swap == Result.error(Result.error("inner")))
        }

        "type inference" in {
            val result: Result[Int, String]  = Result.succeed("hello")
            val swapped: Result[String, Int] = result.swap
            assert(swapped == Result.error("hello"))
        }

        "idempotence" in {
            val success = Result.succeed[String, Int](42)
            assert(success.swap.swap == success)

            val failure = Result.error[String, Int]("error")
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
                    y <- Result.error[Exception, Int](new Exception("error"))
                    z <- Result.succeed(3)
                yield x + y + z

            assert(result.isFail)
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

            assert(result.isFail)
        }
    }

    "mapError" - {
        "should not change Success" in {
            val result = Result.succeed[String, Int](5)
            val mapped = result.mapError(_ => 42)
            assert(mapped == Success(5))
        }

        "should apply the function to Error" in {
            val result = Result.error[String, Int]("error")
            val mapped = result.mapError(_.length)
            assert(mapped == Error(5))
        }

        "should not change Panic" in {
            val ex     = new Exception("test")
            val result = Result.panic[String, Int](ex)
            val mapped = result.mapError(_ => 42)
            assert(mapped == Panic(ex))
        }

        "should allow changing the error type" in {
            val result: Result[String, Int] = Result.error("error")
            val mapped: Result[Int, Int]    = result.mapError(_.length)
            assert(mapped == Error(5))
        }

        "should handle exceptions in the mapping function" in {
            val result = Result.error[String, Int]("error")
            val mapped = result.mapError(_ => throw new RuntimeException("Mapping error"))
            assert(mapped.isPanic)
        }

        "should work with for-comprehensions" in {
            val result =
                for
                    x <- Result.succeed[String, Int](5)
                    y <- Result.error[String, Int]("error")
                yield x + y

            val mapped = result.mapError(_.toUpperCase)
            assert(mapped == Error("ERROR"))
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

        "first Error encountered" in {
            val results = Seq(
                Result.succeed(1),
                Result.error("error"),
                Result.succeed(3)
            )
            val collected = Result.collect(results)
            assert(collected == Error("error"))
        }

        "Panic encountered" in {
            val ex = new Exception("panic")
            val results = Seq(
                Result.succeed(1),
                Result.panic(ex),
                Result.error("error")
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
                Result.error("string error"),
                Result.error(42),
                Result.succeed(3)
            )
            val collected: Result[String | Int, Seq[Int]] =
                Result.collect(results)
            assert(collected.isFail)
            assert(collected.failure.get.equals("string error"))
        }

        "mixed Success and Error with different error types" in {
            val results: Seq[Result[Any, Int]] = Seq(
                Result.succeed(1),
                Result.error("string error"),
                Result.succeed(2),
                Result.error(42),
                Result.succeed(3)
            )
            val collected = Result.collect(results)
            assert(collected.isFail)
            assert(collected.failure.get.equals("string error"))
        }

    }

    "show" - {
        "Success" in {
            assert(Result.succeed(42).show == "Success(42)")
        }

        "Error" in {
            assert(Result.error("error").show == "Error(error)")
        }

        "Panic" in {
            val ex = new Exception("test")
            assert(Result.panic(ex).show == s"Panic($ex)")
        }

        "nested Success" in {
            val nested = Result.succeed(Result.succeed(Result.error("error")))
            assert(nested.show == "Success(Success(Success(Error(error))))")
        }
    }

    "SuccessError.toString" - {
        "single level" in {
            val successError = Result.Success(Result.Error("error"))
            assert(successError.toString == "Success(Error(error))")
        }

        "multiple levels" in {
            val nested = Result.Success(Result.Success(Result.Success(Result.Error("error"))))
            assert(nested.toString == "Success(Success(Success(Error(error))))")
        }
    }

end ResultTest

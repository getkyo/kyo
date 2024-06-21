package kyoTest

import kyo.*
import kyo.Result.Failure
import kyo.Result.Success
import kyoTest.Tagged.jvmOnly

class ResultTest extends KyoTest:

    val try2: Result[Result[Int]] = Success(Failure(new Exception("error")))

    "should match Success containing Failure" in {
        val result = try2 match
            case Success(Failure(e)) => e.getMessage
            case _                   => ""
        assert(result == "error")
    }

    "isSuccess" - {
        "returns true for Success" in {
            assert(Success(1).isSuccess)
        }
        "returns false for Failure" in {
            assert(!Failure[Int](new Exception("error")).isSuccess)
        }
    }

    "isFailure" - {
        "returns false for Success" in {
            assert(!Success(1).isFailure)
        }
        "returns true for Failure" in {
            assert(Failure[Int](new Exception("error")).isFailure)
        }
    }

    "get" - {
        "returns the value for Success" in {
            assert(Success(1).get == 1)
        }
        "throws the exception for Failure" in {
            val ex = new Exception("error")
            assertThrows[Exception](Failure[Int](ex).get)
        }
    }

    "getOrElse" - {
        "returns the value for Success" in {
            assert(Success(1).getOrElse(0) == 1)
        }
        "returns the default value for Failure" in {
            assert(Failure[Int](new Exception("error")).getOrElse(0) == 0)
        }
        "inference" in {
            val r: List[String] = Result(List.empty[String]).getOrElse(List.empty)
            assert(r == List.empty)
        }
    }

    "orElse" - {
        "returns itself for Success" in {
            assert(Success(1).orElse(Success(2)) == Success(1))
        }
        "returns the alternative for Failure" in {
            assert(Failure[Int](new Exception("error")).orElse(Success(1)) == Success(1))
        }
        "inference" in {
            val r: Result[List[String]] = Result(List.empty[String]).orElse(Result(List.empty))
            assert(r.get == List.empty)
        }
    }

    "flatMap" - {
        "applies the function for Success" in {
            assert(Success(1).flatMap(x => Success(x + 1)) == Success(2))
        }
        "does not apply the function for Failure" in {
            val ex = new Exception("error")
            assert(Failure[Int](ex).flatMap(x => Success(x + 1)) == Failure[Int](ex))
        }
    }

    "map" - {
        "applies the function for Success" in {
            assert(Success(1).map(_ + 1) == Success(2))
        }
        "does not apply the function for Failure" in {
            val ex = new Exception("error")
            assert(Failure[Int](ex).map(_ + 1) == Failure[Int](ex))
        }
    }

    "fold" - {
        "applies the success function for Success" in {
            assert(Success(1).fold(_ => 0)(x => x + 1) == 2)
        }
        "applies the failure function for Failure" in {
            assert(Failure[Int](new Exception("error")).fold(_ => 0)(x => x) == 0)
        }
        "catches failures in ifSuccess" in {
            val ex = new Exception
            assert(Success(1).fold(_ => 0)(_ => throw ex) == 0)
        }
    }

    "filter" - {
        "returns itself if the predicate holds for Success" in {
            assert(Success(2).filter(_ % 2 == 0) == Success(2))
        }
        "returns Failure if the predicate doesn't hold for Success" in {
            assert(Success(1).filter(_ % 2 == 0).isFailure)
        }
        "returns itself for Failure" in {
            val ex = new Exception("error")
            assert(Failure[Int](ex).filter(_ => true) == Failure[Int](ex))
        }
    }

    "recover" - {
        "returns itself for Success" in {
            assert(Success(1).recover { case _: Exception => 0 } == Success(1))
        }
        "returns Success with the mapped value if the partial function is defined for Failure" in {
            assert(Failure[Int](new Exception("error")).recover { case _: Exception => 0 } == Success(0))
        }
        "returns itself if the partial function is not defined for Failure" in {
            val ex = new Exception("error")
            assert(Failure[Int](ex).recover { case _: RuntimeException => 0 } == Failure[Int](ex))
        }
    }

    "recoverWith" - {
        "returns itself for Success" in {
            assert(Success(1).recoverWith { case _: Exception => Success(0) } == Success(1))
        }
        "returns the mapped Result if the partial function is defined for Failure" in {
            assert(Failure[Int](new Exception("error")).recoverWith { case _: Exception => Success(0) } == Success(0))
        }
        "returns itself if the partial function is not defined for Failure" in {
            val ex = new Exception("error")
            assert(Failure[Int](ex).recoverWith { case _: RuntimeException => Success(0) } == Failure[Int](ex))
        }
    }

    "toEither" - {
        "returns Right with the value for Success" in {
            assert(Success(1).toEither == Right(1))
        }
        "returns Left with the exception for Failure" in {
            val ex = new Exception("error")
            assert(Failure[Int](ex).toEither == Left(ex))
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

        "isFailure should return false for deeply nested Success" in {
            assert(!nestedResult.isFailure)
        }
    }

    "Success with nested Failure" - {
        val successWithNestedFailure: Success[Failure[Int]] = Success(Failure[Int](new Exception("error")))

        "get should return the nested Failure" in {
            assert(successWithNestedFailure.get.isFailure)
        }

        "isSuccess should return true" in {
            assert(successWithNestedFailure.isSuccess)
        }

        "isFailure should return false" in {
            assert(!successWithNestedFailure.isFailure)
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
                val tryy: Result[Int] = Success(2)
                val result = tryy match
                    case Success(x) if x > 1 => "greater than 1"
                    case Success(_)          => "less than or equal to 1"
                    case Failure(_)          => "failure"
                assert(result == "greater than 1")
            }

            "should match Failure with a guard" in {
                val tryy: Result[Int] = Failure(new Exception("error"))
                val result = tryy match
                    case Failure(e) if e.getMessage.length > 5 => "long error"
                    case Failure(_)                            => "short error"
                    case Success(_)                            => "success"
                assert(result == "short error")
            }
        }

        "matching nested Results" - {
            val try1: Result[Result[Int]] = Success(Success(1))
            val try2: Result[Result[Int]] = Success(Failure(new Exception("error")))
            val try3: Result[Result[Int]] = Failure(new Exception("error"))

            "should match deeply nested Success" in {
                val result = try1 match
                    case Success(Success(x)) => x
                    case _                   => 0
                assert(result == 1)
            }

            "should match Success containing Failure" in {
                val result = try2 match
                    case Success(Failure(e)) => e.getMessage
                    case _                   => ""
                assert(result == "error")
            }

            "should match top-level Failure" in {
                val result = try3 match
                    case Failure(e) => e.getMessage
                    case _          => ""
                assert(result == "error")
            }
        }
    }

    "edge cases" - {
        "should not handle exceptions in ifFailure" in {
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
    }

    "toTry" - {
        "Success to Try" in {
            val success: Result[Int] = Success(42)
            val tryResult            = success.toTry
            assert(tryResult.isSuccess)
            assert(tryResult.get == 42)
        }

        "Failure to Try" in {
            val exception            = new RuntimeException("Something went wrong")
            val failure: Result[Int] = Failure(exception)
            val tryResult            = failure.toTry
            assert(tryResult.isFailure)
            assert(tryResult.failed.get == exception)
        }

        "deeply nested Success to Try" in {
            val nested: Result[Result[Result[Int]]] = Success(Success(Success(42)))
            val tryResult                           = nested.toTry
            assert(tryResult.isSuccess)
            assert(tryResult.get == Success(Success(42)))
        }

        "deeply nested Failure to Try" in {
            val exception                           = new RuntimeException("Something went wrong")
            val nested: Result[Result[Result[Int]]] = Success(Success(Failure(exception)))
            val tryResult                           = nested.toTry
            assert(tryResult.isSuccess)
            assert(tryResult.get == Success(Failure(exception)))
        }
    }

    "null values" - {
        "Success with null value" in {
            val result: Result[String] = Success(null)
            assert(result.isSuccess)
            assert(result.get == null)
        }

        "Success with null value flatMap" in {
            val result: Result[String] = Success(null)
            val flatMapped             = result.flatMap(str => Success(s"mapped: $str"))
            assert(flatMapped == Success("mapped: null"))
        }

        "Failure with null exception" taggedAs jvmOnly in {
            val result: Result[Int] = Failure(null)
            assert(result.isFailure)
            assertThrows[NullPointerException](result.get)
        }

        "Failure with null exception flatMap" taggedAs jvmOnly in {
            val result: Result[Int] = Failure(null)
            val flatMapped          = result.flatMap(num => Success(num + 1))
            assert(flatMapped.isFailure)
            assertThrows[NullPointerException](flatMapped.get)
        }

        "Failure with null exception map" taggedAs jvmOnly in {
            val result: Result[Int] = Failure(null)
            val mapped              = result.map(num => num + 1)
            assert(mapped.isFailure)
            assertThrows[NullPointerException](mapped.get)
        }
    }

    "for comprehensions" - {
        "yield a Success result" in {
            val result =
                for
                    x <- Success(1)
                    y <- Success(2)
                    z <- Success(3)
                yield x + y + z

            assert(result == Success(6))
        }

        "short-circuit on Failure" in {
            val result =
                for
                    x <- Success(1)
                    y <- Failure[Int](new Exception("error"))
                    z <- Success(3)
                yield x + y + z

            assert(result.isFailure)
        }

        "handle exceptions in the yield" in {
            val result =
                for
                    x <- Success(1)
                    y <- Success(2)
                yield throw new Exception("error")

            assert(result.isFailure)
        }

        "sequence operations with flatMap" in {
            val result =
                for
                    x <- Success(1)
                    y <- Success(2)
                    if y > 0
                    z <- Success(3)
                yield x + y + z

            assert(result == Success(6))
        }

        "fail the comprehension with a guard" in {
            val result =
                for
                    x <- Success(1)
                    y <- Success(-1)
                    if y > 0
                yield x + y

            assert(result.isFailure)
        }
    }

end ResultTest

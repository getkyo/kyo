package kyo

import java.util.UUID
import kyo.*

class HttpCodecTest extends Test:

    "Int codec" - {
        "encode" in {
            assert(summon[HttpCodec[Int]].encode(42) == "42")
            assert(summon[HttpCodec[Int]].encode(0) == "0")
            assert(summon[HttpCodec[Int]].encode(-1) == "-1")
            assert(summon[HttpCodec[Int]].encode(Int.MaxValue) == Int.MaxValue.toString)
            assert(summon[HttpCodec[Int]].encode(Int.MinValue) == Int.MinValue.toString)
        }

        "decode" in {
            assert(summon[HttpCodec[Int]].decode("42") == Result.succeed(42))
            assert(summon[HttpCodec[Int]].decode("0") == Result.succeed(0))
            assert(summon[HttpCodec[Int]].decode("-1") == Result.succeed(-1))
        }

        "decode invalid fails" in {
            assert(summon[HttpCodec[Int]].decode("abc").isFailure)
        }

        "decode empty fails" in {
            assert(summon[HttpCodec[Int]].decode("").isFailure)
        }
    }

    "Long codec" - {
        "encode" in {
            assert(summon[HttpCodec[Long]].encode(123456789L) == "123456789")
            assert(summon[HttpCodec[Long]].encode(Long.MaxValue) == Long.MaxValue.toString)
        }

        "decode" in {
            assert(summon[HttpCodec[Long]].decode("123456789") == Result.succeed(123456789L))
        }

        "decode invalid fails" in {
            assert(summon[HttpCodec[Long]].decode("not_a_long").isFailure)
        }
    }

    "String codec" - {
        "encode is identity" in {
            assert(summon[HttpCodec[String]].encode("hello") == "hello")
            assert(summon[HttpCodec[String]].encode("") == "")
        }

        "decode is identity" in {
            assert(summon[HttpCodec[String]].decode("hello") == Result.succeed("hello"))
            assert(summon[HttpCodec[String]].decode("") == Result.succeed(""))
        }

        "preserves special characters" in {
            val s = "hello world/foo?bar=baz&x=1"
            assert(summon[HttpCodec[String]].encode(s) == s)
            assert(summon[HttpCodec[String]].decode(s) == Result.succeed(s))
        }
    }

    "Boolean codec" - {
        "encode true" in {
            assert(summon[HttpCodec[Boolean]].encode(true) == "true")
        }

        "encode false" in {
            assert(summon[HttpCodec[Boolean]].encode(false) == "false")
        }

        "decode true" in {
            assert(summon[HttpCodec[Boolean]].decode("true") == Result.succeed(true))
        }

        "decode false" in {
            assert(summon[HttpCodec[Boolean]].decode("false") == Result.succeed(false))
        }

        "decode invalid fails" in {
            assert(summon[HttpCodec[Boolean]].decode("maybe").isFailure)
        }
    }

    "Double codec" - {
        "encode" in {
            assert(summon[HttpCodec[Double]].encode(3.14) == "3.14")
            assert(summon[HttpCodec[Double]].decode(summon[HttpCodec[Double]].encode(0.0)) == Result.succeed(0.0))
        }

        "decode" in {
            assert(summon[HttpCodec[Double]].decode("3.14") == Result.succeed(3.14))
        }

        "decode invalid fails" in {
            assert(summon[HttpCodec[Double]].decode("not_a_double").isFailure)
        }
    }

    "Float codec" - {
        "encode" in {
            assert(summon[HttpCodec[Float]].encode(1.5f) == "1.5")
        }

        "decode" in {
            assert(summon[HttpCodec[Float]].decode("1.5") == Result.succeed(1.5f))
        }

        "decode invalid fails" in {
            assert(summon[HttpCodec[Float]].decode("not_a_float").isFailure)
        }
    }

    "UUID codec" - {
        "round-trips" in {
            val uuid    = UUID.randomUUID()
            val encoded = summon[HttpCodec[UUID]].encode(uuid)
            val decoded = summon[HttpCodec[UUID]].decode(encoded)
            assert(decoded.getOrThrow.equals(uuid))
        }

        "encode produces standard format" in {
            val uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
            assert(summon[HttpCodec[UUID]].encode(uuid) == "550e8400-e29b-41d4-a716-446655440000")
        }

        "decode valid UUID" in {
            val decoded = summon[HttpCodec[UUID]].decode("550e8400-e29b-41d4-a716-446655440000")
            assert(decoded.getOrThrow.equals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")))
        }

        "decode invalid fails" in {
            assert(summon[HttpCodec[UUID]].decode("not-a-uuid").isFailure)
        }
    }

    "custom codec via apply" - {
        "creates working codec" in {
            given HttpCodec[List[Int]] = HttpCodec(
                _.mkString(","),
                _.split(",").map(_.toInt).toList
            )
            val codec = summon[HttpCodec[List[Int]]]
            assert(codec.encode(List(1, 2, 3)) == "1,2,3")
            assert(codec.decode("1,2,3") == Result.succeed(List(1, 2, 3)))
        }
    }

end HttpCodecTest

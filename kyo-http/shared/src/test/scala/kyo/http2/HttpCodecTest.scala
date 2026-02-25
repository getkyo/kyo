package kyo.http2

import java.util.UUID
import kyo.Test

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
            assert(summon[HttpCodec[Int]].decode("42") == 42)
            assert(summon[HttpCodec[Int]].decode("0") == 0)
            assert(summon[HttpCodec[Int]].decode("-1") == -1)
        }

        "decode invalid throws" in {
            assertThrows[NumberFormatException] {
                summon[HttpCodec[Int]].decode("abc")
            }
        }

        "decode empty throws" in {
            assertThrows[NumberFormatException] {
                summon[HttpCodec[Int]].decode("")
            }
        }
    }

    "Long codec" - {
        "encode" in {
            assert(summon[HttpCodec[Long]].encode(123456789L) == "123456789")
            assert(summon[HttpCodec[Long]].encode(Long.MaxValue) == Long.MaxValue.toString)
        }

        "decode" in {
            assert(summon[HttpCodec[Long]].decode("123456789") == 123456789L)
        }

        "decode invalid throws" in {
            assertThrows[NumberFormatException] {
                summon[HttpCodec[Long]].decode("not_a_long")
            }
        }
    }

    "String codec" - {
        "encode is identity" in {
            assert(summon[HttpCodec[String]].encode("hello") == "hello")
            assert(summon[HttpCodec[String]].encode("") == "")
        }

        "decode is identity" in {
            assert(summon[HttpCodec[String]].decode("hello") == "hello")
            assert(summon[HttpCodec[String]].decode("") == "")
        }

        "preserves special characters" in {
            val s = "hello world/foo?bar=baz&x=1"
            assert(summon[HttpCodec[String]].encode(s) == s)
            assert(summon[HttpCodec[String]].decode(s) == s)
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
            assert(summon[HttpCodec[Boolean]].decode("true") == true)
        }

        "decode false" in {
            assert(summon[HttpCodec[Boolean]].decode("false") == false)
        }

        "decode invalid throws" in {
            assertThrows[IllegalArgumentException] {
                summon[HttpCodec[Boolean]].decode("maybe")
            }
        }
    }

    "Double codec" - {
        "encode" in {
            assert(summon[HttpCodec[Double]].encode(3.14) == "3.14")
            assert(summon[HttpCodec[Double]].decode(summon[HttpCodec[Double]].encode(0.0)) == 0.0)
        }

        "decode" in {
            assert(summon[HttpCodec[Double]].decode("3.14") == 3.14)
        }

        "decode invalid throws" in {
            assertThrows[NumberFormatException] {
                summon[HttpCodec[Double]].decode("not_a_double")
            }
        }
    }

    "Float codec" - {
        "encode" in {
            assert(summon[HttpCodec[Float]].encode(1.5f) == "1.5")
        }

        "decode" in {
            assert(summon[HttpCodec[Float]].decode("1.5") == 1.5f)
        }

        "decode invalid throws" in {
            assertThrows[NumberFormatException] {
                summon[HttpCodec[Float]].decode("not_a_float")
            }
        }
    }

    "UUID codec" - {
        "round-trips" in {
            val uuid    = UUID.randomUUID()
            val encoded = summon[HttpCodec[UUID]].encode(uuid)
            val decoded = summon[HttpCodec[UUID]].decode(encoded)
            assert(decoded.equals(uuid))
        }

        "encode produces standard format" in {
            val uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
            assert(summon[HttpCodec[UUID]].encode(uuid) == "550e8400-e29b-41d4-a716-446655440000")
        }

        "decode valid UUID" in {
            val decoded = summon[HttpCodec[UUID]].decode("550e8400-e29b-41d4-a716-446655440000")
            assert(decoded.equals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")))
        }

        "decode invalid throws" in {
            assertThrows[IllegalArgumentException] {
                summon[HttpCodec[UUID]].decode("not-a-uuid")
            }
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
            assert(codec.decode("1,2,3") == List(1, 2, 3))
        }
    }

end HttpCodecTest

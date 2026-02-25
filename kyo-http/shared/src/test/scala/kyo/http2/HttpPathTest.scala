package kyo.http2

import kyo.Record2.~
import kyo.Test
import scala.language.implicitConversions

class HttpPathTest extends Test:

    import HttpPath.*

    "Literal" - {
        "stores value" in {
            val p = HttpPath.Literal("users")
            p match
                case HttpPath.Literal(v) => assert(v == "users")
                case _                   => fail("Expected Literal")
            end match
        }

        "empty string" in {
            val p = HttpPath.Literal("")
            p match
                case HttpPath.Literal(v) => assert(v == "")
                case _                   => fail("Expected Literal")
            end match
        }

        "special characters" in {
            val p = HttpPath.Literal("users-v2")
            p match
                case HttpPath.Literal(v) => assert(v == "users-v2")
                case _                   => fail("Expected Literal")
            end match
        }

        "type is HttpPath[Any]" in {
            val p = HttpPath.Literal("test")
            typeCheck("""val _: HttpPath[Any] = p""")
        }
    }

    "empty" - {
        "is Literal with empty string" in {
            HttpPath.empty match
                case HttpPath.Literal(v) => assert(v == "")
                case _                   => fail("Expected Literal")
            end match
        }

        "type is HttpPath[Any]" in {
            typeCheck("""val _: HttpPath[Any] = HttpPath.empty""")
        }
    }

    "Capture" - {
        "defaults wireName to empty" in {
            val p = HttpPath.Capture[Int]("id")
            p match
                case HttpPath.Capture(fn, wn, _) =>
                    assert(fn == "id")
                    assert(wn == "")
                case _ => fail("Expected Capture")
            end match
        }

        "custom wireName" in {
            val p = HttpPath.Capture[Int]("id", "user_id")
            p match
                case HttpPath.Capture(fn, wn, _) =>
                    assert(fn == "id")
                    assert(wn == "user_id")
                case _ => fail("Expected Capture")
            end match
        }

        "empty field name" in {
            val p = HttpPath.Capture[Int]("")
            p match
                case HttpPath.Capture(fn, _, _) => assert(fn == "")
                case _                          => fail("Expected Capture")
            end match
        }

        "underscore field name" in {
            val p = HttpPath.Capture[Int]("user_id")
            p match
                case HttpPath.Capture(fn, wn, _) =>
                    assert(fn == "user_id")
                    assert(wn == "")
                case _ => fail("Expected Capture")
            end match
        }

        "capture types" - {
            "Int" in {
                val p = HttpPath.Capture[Int]("id")
                typeCheck("""val _: HttpPath["id" ~ Int] = p""")
            }

            "Long" in {
                val p = HttpPath.Capture[Long]("id")
                typeCheck("""val _: HttpPath["id" ~ Long] = p""")
            }

            "String" in {
                val p = HttpPath.Capture[String]("slug")
                typeCheck("""val _: HttpPath["slug" ~ String] = p""")
            }

            "Boolean" in {
                val p = HttpPath.Capture[Boolean]("active")
                typeCheck("""val _: HttpPath["active" ~ Boolean] = p""")
            }

            "Double" in {
                val p = HttpPath.Capture[Double]("score")
                typeCheck("""val _: HttpPath["score" ~ Double] = p""")
            }

            "Float" in {
                val p = HttpPath.Capture[Float]("weight")
                typeCheck("""val _: HttpPath["weight" ~ Float] = p""")
            }

            "UUID" in {
                val p = HttpPath.Capture[java.util.UUID]("id")
                typeCheck("""val _: HttpPath["id" ~ java.util.UUID] = p""")
            }
        }

        "stores codec" in {
            val p = HttpPath.Capture[Int]("id")
            p match
                case HttpPath.Capture(_, _, codec) =>
                    assert(codec.encode(42) == "42")
                    assert(codec.decode("42").equals(42))
                case _ => fail("Expected Capture")
            end match
        }
    }

    "Rest" - {
        "stores fieldName" in {
            val p = HttpPath.Rest("path")
            p match
                case HttpPath.Rest(fn) => assert(fn == "path")
                case _                 => fail("Expected Rest")
            end match
        }

        "type is String" in {
            val p = HttpPath.Rest("remainder")
            typeCheck("""val _: HttpPath["remainder" ~ String] = p""")
        }
    }

    "Concat" - {
        "two literals" in {
            val p = HttpPath.Concat(HttpPath.Literal("a"), HttpPath.Literal("b"))
            p match
                case HttpPath.Concat(HttpPath.Literal(l), HttpPath.Literal(r)) =>
                    assert(l == "a")
                    assert(r == "b")
                case _ => fail("Expected Concat of Literals")
            end match
        }

        "capture and literal" in {
            val p = HttpPath.Concat(HttpPath.Capture[Int]("id"), HttpPath.Literal("details"))
            p match
                case HttpPath.Concat(HttpPath.Capture(fn, _, _), HttpPath.Literal(v)) =>
                    assert(fn == "id")
                    assert(v == "details")
                case _ => fail("Expected Concat")
            end match
        }

        "tracks intersection type" in {
            val p = HttpPath.Concat(HttpPath.Capture[Int]("a"), HttpPath.Capture[String]("b"))
            typeCheck("""val _: HttpPath["a" ~ Int & "b" ~ String] = p""")
        }
    }

    "/ operator" - {
        "string / string" in {
            val p = "api" / "v1" / "users"
            typeCheck("""val _: HttpPath[Any] = p""")
        }

        "string / capture" in {
            val p = "users" / HttpPath.Capture[Int]("id")
            typeCheck("""val _: HttpPath["id" ~ Int] = p""")
        }

        "capture / string" in {
            val p = HttpPath.Capture[Int]("id") / "details"
            typeCheck("""val _: HttpPath["id" ~ Int] = p""")
        }

        "two captures" in {
            val p = "users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId")
            typeCheck("""val _: HttpPath["userId" ~ Int & "postId" ~ Int] = p""")
        }

        "three captures" in {
            val p = "org" / HttpPath.Capture[Int]("orgId") / "users" / HttpPath.Capture[String](
                "name"
            ) / "items" / HttpPath.Capture[Long]("itemId")
            typeCheck("""val _: HttpPath["orgId" ~ Int & "name" ~ String & "itemId" ~ Long] = p""")
        }

        "four captures" in {
            val p = "a" / HttpPath.Capture[Int]("a") / "b" / HttpPath.Capture[String]("b") / "c" / HttpPath.Capture[Long](
                "c"
            ) / "d" / HttpPath.Capture[Boolean]("d")
            typeCheck("""val _: HttpPath["a" ~ Int & "b" ~ String & "c" ~ Long & "d" ~ Boolean] = p""")
        }

        "adjacent captures" in {
            val p = HttpPath.Capture[Int]("id") / HttpPath.Capture[String]("name")
            typeCheck("""val _: HttpPath["id" ~ Int & "name" ~ String] = p""")
        }

        "capture / rest" in {
            val p = "items" / HttpPath.Capture[Int]("id") / HttpPath.Rest("rest")
            typeCheck("""val _: HttpPath["id" ~ Int & "rest" ~ String] = p""")
        }

        "special characters in literal segments" in {
            val p = "users-v2" / "items_list"
            p match
                case HttpPath.Concat(HttpPath.Literal(l), HttpPath.Literal(r)) =>
                    assert(l == "users-v2")
                    assert(r == "items_list")
                case _ => fail("Expected Concat of two Literals")
            end match
        }
    }

    "implicit string conversion" - {
        "converts string to Literal" in {
            val p: HttpPath[Any] = "users"
            p match
                case HttpPath.Literal(v) => assert(v == "users")
                case _                   => fail("Expected Literal")
            end match
        }
    }

end HttpPathTest

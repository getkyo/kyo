package kyo

import org.scalatest.freespec.AnyFreeSpec

class ReaderTest extends AnyFreeSpec {

    "Reader type class" - {

        "Int reader parses correctly" in {
            assert(Flag.Reader.int("42") == Right(42))
        }

        "Int reader returns Left on non-numeric input" in {
            val result = Flag.Reader.int("notanumber")
            assert(result.isLeft)
            assert(result.left.exists(_.isInstanceOf[NumberFormatException]))
        }

        "String reader is identity" in {
            assert(Flag.Reader.string("hello") == Right("hello"))
            assert(Flag.Reader.string("") == Right(""))
        }

        "Boolean reader parses correctly" in {
            assert(Flag.Reader.boolean("true") == Right(true))
            assert(Flag.Reader.boolean("false") == Right(false))
            // Java parseBoolean: "yes" -> false (only "true" is true)
            assert(Flag.Reader.boolean("yes") == Right(false))
        }

        "Long reader parses correctly" in {
            assert(Flag.Reader.long("9999999999") == Right(9999999999L))
        }

        "Double reader parses correctly" in {
            assert(Flag.Reader.double("3.14") == Right(3.14))
            assert(Flag.Reader.double("1e10") == Right(1.0e10))
        }

        "Seq reader splits on comma" in {
            val listReader = implicitly[Flag.Reader[Seq[Int]]]
            assert(listReader("1,2,3") == Right(Seq(1, 2, 3)))
            assert(listReader("1") == Right(Seq(1)))
        }

        "Reader.typeName returns human-readable name" in {
            assert(Flag.Reader.int.typeName == "Int")
            assert(Flag.Reader.string.typeName == "String")
            assert(Flag.Reader.boolean.typeName == "Boolean")
            assert(Flag.Reader.long.typeName == "Long")
            assert(Flag.Reader.double.typeName == "Double")
            val listReader = implicitly[Flag.Reader[Seq[Int]]]
            assert(listReader.typeName == "Seq[Int]")
        }

        "Flag.Reader is accessible" in {
            assert(Flag.Reader.int("1") == Right(1))
            assert(Flag.Reader.string("hello") == Right("hello"))
            assert(Flag.Reader.long("100") == Right(100L))
            assert(Flag.Reader.double("1.5") == Right(1.5))
            assert(Flag.Reader.boolean("true") == Right(true))
            val listReader = Flag.Reader.seq[Int]
            assert(listReader("1,2,3") == Right(Seq(1, 2, 3)))
        }

        "Seq[Int] empty string returns empty list" in {
            val listReader = implicitly[Flag.Reader[Seq[Int]]]
            assert(listReader("") == Right(Seq.empty))
        }

        "Seq[Int] whitespace-only string returns empty list" in {
            val listReader = implicitly[Flag.Reader[Seq[Int]]]
            assert(listReader("   ") == Right(Seq.empty))
        }

        "Seq reader returns Left on bad element" in {
            val listReader = implicitly[Flag.Reader[Seq[Int]]]
            val result     = listReader("1,bad,3")
            assert(result.isLeft)
            assert(result.left.exists(_.isInstanceOf[NumberFormatException]))
        }

        "custom reader works with Flag" in {
            // Define a custom reader for a simple enum-like type
            implicit val colorReader: Flag.Reader[String] = new Flag.Reader[String] {
                def apply(s: String): Either[Throwable, String] = s.toLowerCase match {
                    case "red" | "green" | "blue" => Right(s.toLowerCase)
                    case other                    => Left(new IllegalArgumentException(s"Unknown color: $other"))
                }
                def typeName: String = "Color"
            }

            assert(colorReader("Red") == Right("red"))
            assert(colorReader("BLUE") == Right("blue"))
            assert(colorReader("yellow").isLeft)
        }
    }

}

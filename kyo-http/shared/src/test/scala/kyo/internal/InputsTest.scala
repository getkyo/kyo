package kyo.internal

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class InputsTest extends AnyFreeSpec with NonImplicitAssertions with BaseKyoDataTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    "duplicate name" - {
        "duplicate query parameter" in {
            typeCheckFailure("""
                kyo.HttpRoute.get("users")
                    .request(_.query[Int]("page").query[Int]("page", default = Some(1)))
            """)("Duplicate request field")
        }

        "query and header with same name" in {
            typeCheckFailure("""
                kyo.HttpRoute.get("users")
                    .request(_.query[String]("token").header[String]("token"))
            """)("Duplicate request field")
        }

        "multiple bodies fail at compile time" in {
            typeCheckFailure("""
                kyo.HttpRoute.post("test")
                    .request(_.bodyJson[String].bodyText)
            """)("Duplicate request field")
        }
    }

end InputsTest

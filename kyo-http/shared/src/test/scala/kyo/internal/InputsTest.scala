package kyo.internal

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class InputsTest extends AnyFreeSpec with NonImplicitAssertions with BaseKyoDataTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    "empty name" - {
        "path capture" in {
            typeCheckFailure("""kyo.HttpPath.int("")""")("Parameter name cannot be empty")
        }

        "query parameter" in {
            typeCheckFailure("""kyo.HttpRoute.get("users").query[Int]("")""")("Parameter name cannot be empty")
        }

        "header" in {
            typeCheckFailure("""kyo.HttpRoute.get("users").header("")""")("Parameter name cannot be empty")
        }

        "cookie" in {
            typeCheckFailure("""kyo.HttpRoute.get("dashboard").cookie("")""")("Parameter name cannot be empty")
        }

        "authApiKey" in {
            typeCheckFailure("""kyo.HttpRoute.get("users").authApiKey("")""")("Parameter name cannot be empty")
        }
    }

    "duplicate name" - {
        "duplicate query parameter" in {
            typeCheckFailure("""
                kyo.HttpRoute.get("users")
                    .query[Int]("page")
                    .query[Int]("page", 1)
            """)("Duplicate input name 'page'")
        }

        "path and query with same name" in {
            typeCheckFailure("""
                import kyo.HttpPath./
                kyo.HttpRoute.get("users" / kyo.HttpPath.int("id"))
                    .query[String]("id")
                    .responseBodyText
            """)("Duplicate input name 'id'")
        }

        "query and header with same name" in {
            typeCheckFailure("""
                kyo.HttpRoute.get("users")
                    .query[String]("token")
                    .header("token")
            """)("Duplicate input name 'token'")
        }

        "path capture named 'body' conflicts with input" in {
            typeCheckFailure("""
                import kyo.HttpPath./
                kyo.HttpRoute.post("data" / kyo.HttpPath.string("body"))
                    .requestBody[String]
            """)("Duplicate input name 'body'")
        }

        "path capture named 'bearer' conflicts with authBearer" in {
            typeCheckFailure("""
                import kyo.HttpPath./
                kyo.HttpRoute.get("auth" / kyo.HttpPath.string("bearer"))
                    .authBearer
            """)("Duplicate input name 'bearer'")
        }

        "query named 'body' conflicts with input" in {
            typeCheckFailure("""
                kyo.HttpRoute.post("data")
                    .query[String]("body")
                    .requestBody[String]
            """)("Duplicate input name 'body'")
        }
    }

    "non-literal name" in {
        // For non-literals, the validation macro fires with a clear error.
        typeCheckFailure("""
            val name = "id"
            kyo.HttpPath.int(name)
        """)("Parameter name must be a string literal")
    }

end InputsTest

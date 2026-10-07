package kyo

import kyo.*

class HttpStatusTest extends BaseHttpTest:

    "code" - {
        "informational codes are in 1xx range" in {
            assert(HttpStatus.Continue.code == 100)
            assert(HttpStatus.SwitchingProtocols.code == 101)
            assert(HttpStatus.Processing.code == 102)
            assert(HttpStatus.EarlyHints.code == 103)
        }

        "success codes are in 2xx range" in {
            assert(HttpStatus.OK.code == 200)
            assert(HttpStatus.Created.code == 201)
            assert(HttpStatus.Accepted.code == 202)
            assert(HttpStatus.NonAuthoritativeInfo.code == 203)
            assert(HttpStatus.NoContent.code == 204)
            assert(HttpStatus.ResetContent.code == 205)
            assert(HttpStatus.PartialContent.code == 206)
        }

        "redirect codes are in 3xx range" in {
            assert(HttpStatus.MultipleChoices.code == 300)
            assert(HttpStatus.MovedPermanently.code == 301)
            assert(HttpStatus.Found.code == 302)
            assert(HttpStatus.SeeOther.code == 303)
            assert(HttpStatus.NotModified.code == 304)
            assert(HttpStatus.UseProxy.code == 305)
            assert(HttpStatus.TemporaryRedirect.code == 307)
            assert(HttpStatus.PermanentRedirect.code == 308)
        }

        "client error codes are in 4xx range" in {
            assert(HttpStatus.BadRequest.code == 400)
            assert(HttpStatus.Unauthorized.code == 401)
            assert(HttpStatus.Forbidden.code == 403)
            assert(HttpStatus.NotFound.code == 404)
            assert(HttpStatus.MethodNotAllowed.code == 405)
            assert(HttpStatus.Conflict.code == 409)
            assert(HttpStatus.Gone.code == 410)
            assert(HttpStatus.ImATeapot.code == 418)
            assert(HttpStatus.UnprocessableEntity.code == 422)
            assert(HttpStatus.TooManyRequests.code == 429)
            assert(HttpStatus.UnavailableForLegalReasons.code == 451)
        }

        "server error codes are in 5xx range" in {
            assert(HttpStatus.InternalServerError.code == 500)
            assert(HttpStatus.NotImplemented.code == 501)
            assert(HttpStatus.BadGateway.code == 502)
            assert(HttpStatus.ServiceUnavailable.code == 503)
            assert(HttpStatus.GatewayTimeout.code == 504)
            assert(HttpStatus.NetworkAuthRequired.code == 511)
        }
    }

    "category predicates" - {
        "isInformational" in {
            assert(HttpStatus.Continue.isInformational)
            assert(!HttpStatus.Continue.isSuccess)
            assert(!HttpStatus.Continue.isRedirect)
            assert(!HttpStatus.Continue.isClientError)
            assert(!HttpStatus.Continue.isServerError)
            assert(!HttpStatus.Continue.isError)
        }

        "isSuccess" in {
            assert(HttpStatus.OK.isSuccess)
            assert(!HttpStatus.OK.isInformational)
            assert(!HttpStatus.OK.isError)
        }

        "isRedirect" in {
            assert(HttpStatus.MovedPermanently.isRedirect)
            assert(!HttpStatus.MovedPermanently.isSuccess)
            assert(!HttpStatus.MovedPermanently.isError)
        }

        "isClientError" in {
            assert(HttpStatus.NotFound.isClientError)
            assert(HttpStatus.NotFound.isError)
            assert(!HttpStatus.NotFound.isServerError)
            assert(!HttpStatus.NotFound.isSuccess)
        }

        "isServerError" in {
            assert(HttpStatus.InternalServerError.isServerError)
            assert(HttpStatus.InternalServerError.isError)
            assert(!HttpStatus.InternalServerError.isClientError)
            assert(!HttpStatus.InternalServerError.isSuccess)
        }

        "isError covers both client and server errors" in {
            assert(HttpStatus.BadRequest.isError)
            assert(HttpStatus.InternalServerError.isError)
            assert(!HttpStatus.OK.isError)
            assert(!HttpStatus.MovedPermanently.isError)
        }

        "Custom status predicates based on code range" in {
            assert(HttpStatus(150).isInformational)
            assert(HttpStatus(250).isSuccess)
            assert(HttpStatus(350).isRedirect)
            assert(HttpStatus(450).isClientError)
            assert(HttpStatus(550).isServerError)
        }
    }

    "message rules" - {
        "forbidsContent holds for every 1xx, 204 and 304 and nothing next to them" in {
            assert(!HttpStatus.forbidsContent(99))
            assert(HttpStatus.Continue.forbidsContent)
            assert(HttpStatus.SwitchingProtocols.forbidsContent)
            assert(HttpStatus(199).forbidsContent)
            assert(!HttpStatus.OK.forbidsContent)
            assert(!HttpStatus.NonAuthoritativeInfo.forbidsContent)
            assert(HttpStatus.NoContent.forbidsContent)
            assert(!HttpStatus.ResetContent.forbidsContent)
            assert(!HttpStatus.SeeOther.forbidsContent)
            assert(HttpStatus.NotModified.forbidsContent)
            assert(!HttpStatus.UseProxy.forbidsContent)
            assert(!HttpStatus.InternalServerError.forbidsContent)
        }

        "isInterim holds for every 1xx but 101" in {
            assert(!HttpStatus.isInterim(99))
            assert(HttpStatus.Continue.isInterim)
            assert(!HttpStatus.SwitchingProtocols.isInterim)
            assert(HttpStatus.Processing.isInterim)
            assert(HttpStatus(199).isInterim)
            assert(!HttpStatus.OK.isInterim)
        }

        "acceptsUpgrade holds for 101 and every 2xx" in {
            assert(!HttpStatus.Continue.acceptsUpgrade)
            assert(HttpStatus.SwitchingProtocols.acceptsUpgrade)
            assert(!HttpStatus.Processing.acceptsUpgrade)
            assert(!HttpStatus(199).acceptsUpgrade)
            assert(HttpStatus.OK.acceptsUpgrade)
            assert(HttpStatus(299).acceptsUpgrade)
            assert(!HttpStatus.MultipleChoices.acceptsUpgrade)
            assert(!HttpStatus.BadRequest.acceptsUpgrade)
        }

        "a Custom status follows the rules of its code" in {
            assert(HttpStatus(150).forbidsContent)
            assert(HttpStatus(150).isInterim)
            assert(HttpStatus(250).acceptsUpgrade)
            assert(!HttpStatus(250).forbidsContent)
        }

        "isValid holds from 100 to 599" in {
            assert(!HttpStatus.isValid(99))
            assert(HttpStatus.isValid(100))
            assert(HttpStatus.isValid(599))
            assert(!HttpStatus.isValid(600))
            assert(!HttpStatus.isValid(0))
        }
    }

    "apply" - {
        "resolves known status codes" in {
            assert(HttpStatus(200) == HttpStatus.OK)
            assert(HttpStatus(404) == HttpStatus.NotFound)
            assert(HttpStatus(500) == HttpStatus.InternalServerError)
            assert(HttpStatus(301) == HttpStatus.MovedPermanently)
            assert(HttpStatus(100) == HttpStatus.Continue)
        }

        "wraps unknown codes in Custom" in {
            val status = HttpStatus(299)
            status match
                case HttpStatus.Custom(code) => assert(code == 299)
                case _                       => fail("Expected Custom")
            end match
        }

        "a literal outside 100 to 599 does not compile" in {
            assert(compiletime.testing.typeChecks("kyo.HttpStatus(200)"))
            assert(!compiletime.testing.typeChecks("kyo.HttpStatus(99)"))
            assert(!compiletime.testing.typeChecks("kyo.HttpStatus(600)"))
        }

        "accepts boundary codes" in {
            assert(HttpStatus(100) == HttpStatus.Continue)
            val custom599 = HttpStatus(599)
            custom599 match
                case HttpStatus.Custom(code) => assert(code == 599)
                case _                       => fail("Expected Custom for 599")
            end match
        }
    }

    "init" - {
        "refuses 99 and 600 with HttpInvalidStatusException" in {
            assert(HttpStatus.init(99).failure.map(_.code) == Present(99))
            assert(HttpStatus.init(600).failure.map(_.code) == Present(600))
        }

        "accepts 100 and 599" in {
            assert(HttpStatus.init(100) == Result.succeed(HttpStatus.Continue))
            assert(HttpStatus.init(599).map(_.code) == Result.succeed(599))
        }

        "resolves a runtime code like a literal one" in {
            val codes = Seq(200, 299, 404)
            assert(codes.map(HttpStatus.init(_).getOrThrow) == Seq(HttpStatus(200), HttpStatus(299), HttpStatus(404)))
        }
    }

    "resolve" - {
        "returns Present for known codes" in {
            assert(HttpStatus.resolve(200) == Present(HttpStatus.OK))
            assert(HttpStatus.resolve(404) == Present(HttpStatus.NotFound))
        }

        "returns Absent for unknown codes" in {
            assert(HttpStatus.resolve(299) == Absent)
            assert(HttpStatus.resolve(999) == Absent)
        }

        "returns Absent for out-of-range codes" in {
            assert(HttpStatus.resolve(0) == Absent)
            assert(HttpStatus.resolve(-1) == Absent)
        }
    }

    "Custom" - {
        "stores arbitrary code" in {
            val c = HttpStatus(299)
            assert(c.code == 299)
        }

        "equality" in {
            assert(HttpStatus(299) == HttpStatus(299))
            assert(HttpStatus(299) != HttpStatus(300))
        }

        "never holds a code a standard case covers" in {
            assert(!HttpStatus(200).isInstanceOf[HttpStatus.Custom])
            assert(!HttpStatus.init(200).getOrThrow.isInstanceOf[HttpStatus.Custom])
        }
    }

    "equality" - {
        "same enum values are equal" in {
            assert(HttpStatus.OK == HttpStatus.OK)
            assert(HttpStatus.NotFound == HttpStatus.NotFound)
        }

        "different enum values are not equal" in {
            assert(HttpStatus.OK != HttpStatus.Created)
        }

        "exported values work" in {
            // Verify exports work - these should be accessible directly
            val ok: HttpStatus = HttpStatus.OK
            assert(ok.code == 200)
        }
    }

    // HttpStatus has no `reason` field currently — reason phrases are set by the backend
    // (Netty uses HttpResponseStatus.valueOf with correct phrases; h2o Native does not).
    // This test validates all standard codes resolve to named enum values, not Custom.
    "reason phrases" - {
        "all standard status codes resolve to named enum values" in {
            val standardCodes = Seq(
                200, 201, 202, 204,
                301, 304,
                400, 401, 403, 404, 405, 409, 415, 422, 429,
                500, 502, 503
            )
            standardCodes.foreach { code =>
                val status = HttpStatus.init(code).getOrThrow
                assert(status.code == code, s"HttpStatus($code) should resolve to code $code")
                assert(
                    !status.isInstanceOf[HttpStatus.Custom],
                    s"HttpStatus($code) should be a named enum value, not Custom"
                )
            }
            ()
        }
    }

end HttpStatusTest

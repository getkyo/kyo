package kyo

import kyo.*

class HttpStatusTest extends Test:

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
            assert(HttpStatus.Custom(150).isInformational)
            assert(HttpStatus.Custom(250).isSuccess)
            assert(HttpStatus.Custom(350).isRedirect)
            assert(HttpStatus.Custom(450).isClientError)
            assert(HttpStatus.Custom(550).isServerError)
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

        "rejects code below 100" in {
            assertThrows[IllegalArgumentException] {
                HttpStatus(99)
            }
        }

        "rejects code above 599" in {
            assertThrows[IllegalArgumentException] {
                HttpStatus(600)
            }
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
            val c = HttpStatus.Custom(299)
            assert(c.code == 299)
        }

        "equality" in {
            assert(HttpStatus.Custom(299) == HttpStatus.Custom(299))
            assert(HttpStatus.Custom(299) != HttpStatus.Custom(300))
        }

        "not equal to standard status with same code" in {
            // Custom(200) is a different instance than Success.OK
            val custom200 = HttpStatus.Custom(200)
            // apply returns the standard enum, not Custom
            assert(HttpStatus(200) != custom200)
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

end HttpStatusTest

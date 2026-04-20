package kyo

import kyo.*

class HttpResponseTest extends Test:

    "construction" - {
        "from status" in {
            val res = HttpResponse.ok
            assert(res.status == HttpStatus.OK)
            assert(res.headers.isEmpty)
            assert(res.fields == Record.empty)
        }

        "full constructor" in {
            val headers = HttpHeaders.empty.add("X-Test", "1")
            val fields  = "key" ~ "val"
            val res     = HttpResponse(HttpStatus.Created, headers, fields)
            assert(res.status == HttpStatus.Created)
            assert(res.headers.get("X-Test") == Present("1"))
            assert(res.fields.key == "val")
        }
    }

    "factory methods" - {
        "ok" in {
            assert(HttpResponse.ok.status == HttpStatus.OK)
        }

        "created" in {
            assert(HttpResponse.created.status == HttpStatus.Created)
        }

        "accepted" in {
            assert(HttpResponse.accepted.status == HttpStatus.Accepted)
        }

        "noContent" in {
            assert(HttpResponse.noContent.status == HttpStatus.NoContent)
        }

        "redirect" in {
            val res = HttpResponse.redirect("https://example.com")
            assert(res.status == HttpStatus.Found)
            assert(res.headers.get("Location") == Present("https://example.com"))
        }

        "movedPermanently" in {
            val res = HttpResponse.movedPermanently("https://example.com")
            assert(res.status == HttpStatus.MovedPermanently)
            assert(res.headers.get("Location") == Present("https://example.com"))
        }

        "notModified" in {
            assert(HttpResponse.notModified.status == HttpStatus.NotModified)
        }

        "badRequest" in {
            assert(HttpResponse.badRequest.status == HttpStatus.BadRequest)
        }

        "unauthorized" in {
            assert(HttpResponse.unauthorized.status == HttpStatus.Unauthorized)
        }

        "forbidden" in {
            assert(HttpResponse.forbidden.status == HttpStatus.Forbidden)
        }

        "notFound" in {
            assert(HttpResponse.notFound.status == HttpStatus.NotFound)
        }

        "conflict" in {
            assert(HttpResponse.conflict.status == HttpStatus.Conflict)
        }

        "unprocessableEntity" in {
            assert(HttpResponse.unprocessableEntity.status == HttpStatus.UnprocessableEntity)
        }

        "tooManyRequests" in {
            assert(HttpResponse.tooManyRequests.status == HttpStatus.TooManyRequests)
        }

        "serverError" in {
            assert(HttpResponse.serverError.status == HttpStatus.InternalServerError)
        }

        "serviceUnavailable" in {
            assert(HttpResponse.serviceUnavailable.status == HttpStatus.ServiceUnavailable)
        }
    }

    "body overloads" - {

        case class Item(id: Int) derives Json, CanEqual

        "ok" - {
            "text" in {
                val res = HttpResponse.ok("hello")
                assert(res.status == HttpStatus.OK)
                assert(res.fields.body == "hello")
            }
            "json" in {
                val res = HttpResponse.ok(Item(1))
                assert(res.status == HttpStatus.OK)
                assert(res.fields.body == Item(1))
            }
            "binary" in {
                val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3))
                val res   = HttpResponse.ok(bytes)
                assert(res.status == HttpStatus.OK)
                assert(res.fields.body.is(bytes))
            }
        }

        "created" - {
            "text" in {
                val res = HttpResponse.created("done")
                assert(res.status == HttpStatus.Created)
                assert(res.fields.body == "done")
            }
            "json" in {
                val res = HttpResponse.created(Item(2))
                assert(res.status == HttpStatus.Created)
                assert(res.fields.body == Item(2))
            }
            "binary" in {
                val bytes = Span.fromUnsafe(Array[Byte](4, 5))
                val res   = HttpResponse.created(bytes)
                assert(res.status == HttpStatus.Created)
                assert(res.fields.body.is(bytes))
            }
        }

        "accepted" - {
            "text" in {
                val res = HttpResponse.accepted("queued")
                assert(res.status == HttpStatus.Accepted)
                assert(res.fields.body == "queued")
            }
            "json" in {
                val res = HttpResponse.accepted(Item(3))
                assert(res.status == HttpStatus.Accepted)
                assert(res.fields.body == Item(3))
            }
            "binary" in {
                val bytes = Span.fromUnsafe(Array[Byte](6))
                val res   = HttpResponse.accepted(bytes)
                assert(res.status == HttpStatus.Accepted)
                assert(res.fields.body.is(bytes))
            }
        }

        "badRequest" - {
            "text" in {
                val res = HttpResponse.badRequest("invalid")
                assert(res.status == HttpStatus.BadRequest)
                assert(res.fields.body == "invalid")
            }
            "json" in {
                val res = HttpResponse.badRequest(Item(4))
                assert(res.status == HttpStatus.BadRequest)
                assert(res.fields.body == Item(4))
            }
            "binary" in {
                val bytes = Span.fromUnsafe(Array[Byte](7))
                val res   = HttpResponse.badRequest(bytes)
                assert(res.status == HttpStatus.BadRequest)
                assert(res.fields.body.is(bytes))
            }
        }

        "unauthorized" - {
            "text" in {
                val res = HttpResponse.unauthorized("denied")
                assert(res.status == HttpStatus.Unauthorized)
                assert(res.fields.body == "denied")
            }
            "json" in {
                val res = HttpResponse.unauthorized(Item(5))
                assert(res.status == HttpStatus.Unauthorized)
                assert(res.fields.body == Item(5))
            }
        }

        "forbidden" - {
            "text" in {
                val res = HttpResponse.forbidden("nope")
                assert(res.status == HttpStatus.Forbidden)
                assert(res.fields.body == "nope")
            }
            "json" in {
                val res = HttpResponse.forbidden(Item(6))
                assert(res.status == HttpStatus.Forbidden)
                assert(res.fields.body == Item(6))
            }
        }

        "notFound" - {
            "text" in {
                val res = HttpResponse.notFound("missing")
                assert(res.status == HttpStatus.NotFound)
                assert(res.fields.body == "missing")
            }
            "json" in {
                val res = HttpResponse.notFound(Item(7))
                assert(res.status == HttpStatus.NotFound)
                assert(res.fields.body == Item(7))
            }
        }

        "conflict" - {
            "text" in {
                val res = HttpResponse.conflict("clash")
                assert(res.status == HttpStatus.Conflict)
                assert(res.fields.body == "clash")
            }
            "json" in {
                val res = HttpResponse.conflict(Item(8))
                assert(res.status == HttpStatus.Conflict)
                assert(res.fields.body == Item(8))
            }
        }

        "unprocessableEntity" - {
            "text" in {
                val res = HttpResponse.unprocessableEntity("bad")
                assert(res.status == HttpStatus.UnprocessableEntity)
                assert(res.fields.body == "bad")
            }
            "json" in {
                val res = HttpResponse.unprocessableEntity(Item(9))
                assert(res.status == HttpStatus.UnprocessableEntity)
                assert(res.fields.body == Item(9))
            }
        }

        "tooManyRequests" - {
            "text" in {
                val res = HttpResponse.tooManyRequests("slow down")
                assert(res.status == HttpStatus.TooManyRequests)
                assert(res.fields.body == "slow down")
            }
            "json" in {
                val res = HttpResponse.tooManyRequests(Item(10))
                assert(res.status == HttpStatus.TooManyRequests)
                assert(res.fields.body == Item(10))
            }
        }

        "serverError" - {
            "text" in {
                val res = HttpResponse.serverError("oops")
                assert(res.status == HttpStatus.InternalServerError)
                assert(res.fields.body == "oops")
            }
            "json" in {
                val res = HttpResponse.serverError(Item(11))
                assert(res.status == HttpStatus.InternalServerError)
                assert(res.fields.body == Item(11))
            }
        }

        "serviceUnavailable" - {
            "text" in {
                val res = HttpResponse.serviceUnavailable("down")
                assert(res.status == HttpStatus.ServiceUnavailable)
                assert(res.fields.body == "down")
            }
            "json" in {
                val res = HttpResponse.serviceUnavailable(Item(12))
                assert(res.status == HttpStatus.ServiceUnavailable)
                assert(res.fields.body == Item(12))
            }
        }
    }

    "addField" in {
        val res = HttpResponse.ok.addField("count", 42)
        assert(res.fields.count == 42)
        assert(res.status == HttpStatus.OK)
    }

    "addFields" in {
        val fields = "a" ~ 1 & "b" ~ "two"
        val res    = HttpResponse.ok.addFields(fields)
        assert(res.fields.a == 1)
        assert(res.fields.b == "two")
    }

    "addHeader" in {
        val res = HttpResponse.ok
            .addHeader("X-A", "1")
            .addHeader("X-A", "2")
        assert(res.headers.getAll("X-A") == Seq("1", "2"))
    }

    "setHeader" in {
        val res = HttpResponse.ok
            .addHeader("X-A", "1")
            .setHeader("X-A", "replaced")
        assert(res.headers.get("X-A") == Present("replaced"))
        assert(res.headers.getAll("X-A") == Seq("replaced"))
    }

    "cacheControl" in {
        val res = HttpResponse.ok.cacheControl("max-age=3600")
        assert(res.headers.get("Cache-Control") == Present("max-age=3600"))
    }

    "noCache" in {
        val res = HttpResponse.ok.noCache
        assert(res.headers.get("Cache-Control") == Present("no-cache"))
    }

    "noStore" in {
        val res = HttpResponse.ok.noStore
        assert(res.headers.get("Cache-Control") == Present("no-store"))
    }

    "etag" - {
        "auto-quotes unquoted value" in {
            val res = HttpResponse.ok.etag("abc123")
            assert(res.headers.get("ETag") == Present("\"abc123\""))
        }

        "preserves already-quoted value" in {
            val res = HttpResponse.ok.etag("\"abc123\"")
            assert(res.headers.get("ETag") == Present("\"abc123\""))
        }
    }

    "contentDisposition" - {
        "attachment by default" in {
            val res = HttpResponse.ok.contentDisposition("report.pdf")
            assert(res.headers.get("Content-Disposition") == Present("""attachment; filename="report.pdf""""))
        }

        "inline" in {
            val res = HttpResponse.ok.contentDisposition("image.png", isInline = true)
            assert(res.headers.get("Content-Disposition") == Present("""inline; filename="image.png""""))
        }
    }

    "immutability" in {
        val res1 = HttpResponse.ok
        val res2 = res1.addHeader("X-Test", "value")
        val res3 = res1.addField("key", "val")
        assert(res1.headers.isEmpty)
        assert(res1.fields == Record.empty)
        assert(res2.headers.get("X-Test") == Present("value"))
        assert(res3.fields.key == "val")
    }

    "chaining builders" in {
        val res = HttpResponse.ok
            .addHeader("X-Custom", "yes")
            .cacheControl("public, max-age=3600")
            .etag("v1")
            .addField("processed", true)
        assert(res.status == HttpStatus.OK)
        assert(res.headers.get("X-Custom") == Present("yes"))
        assert(res.headers.get("Cache-Control") == Present("public, max-age=3600"))
        assert(res.headers.get("ETag") == Present("\"v1\""))
        assert(res.fields.processed == true)
    }

end HttpResponseTest

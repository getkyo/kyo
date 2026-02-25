package kyo

import kyo.*
import kyo.Record2.~

class HttpFilterTest extends Test:

    def req: HttpRequest[Any] = HttpRequest.getRaw(HttpUrl.fromUri("/test"))

    "request filter" - {

        "reads request field" in {
            val filter = new HttpFilter.Request["name" ~ String, Any, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "name" ~ String],
                    next: HttpRequest[In & "name" ~ String] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    val _ = request.fields.name
                    next(request)
                end apply
            typeCheck("""val _: HttpFilter["name" ~ String, Any, Any, Any, Any] = filter""")
        }

        "adds field to request" in {
            val filter = new HttpFilter.Request["name" ~ String, "greeting" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "name" ~ String],
                    next: HttpRequest[In & "name" ~ String & "greeting" ~ String] => HttpResponse[
                        Out
                    ] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("greeting", s"hello ${request.fields.name}"))
            typeCheck("""val _: HttpFilter["name" ~ String, "greeting" ~ String, Any, Any, Any] = filter""")
        }
    }

    "response filter" - {

        "reads response field" in {
            val filter = new HttpFilter.Response["status" ~ Int, Any, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out & "status" ~ Int] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out & "status" ~ Int] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request).map { res =>
                        val _ = res.fields.status
                        res
                    }
            typeCheck("""val _: HttpFilter[Any, Any, "status" ~ Int, Any, Any] = filter""")
        }

        "adds response field" in {
            val filter = new HttpFilter.Response["status" ~ Int, "cached" ~ Boolean, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out & "status" ~ Int] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out & "status" ~ Int & "cached" ~ Boolean] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request).map { res =>
                        res.addField("cached", res.fields.status == 200)
                    }
            typeCheck(
                """val _: HttpFilter[Any, Any, "status" ~ Int, "cached" ~ Boolean, Any] = filter"""
            )
        }
    }

    "passthrough" - {

        "pure passthrough" in {
            val filter = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request)
            typeCheck("""val _: HttpFilter[Any, Any, Any, Any, Any] = filter""")
        }

        "adds header" in {
            val filter = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request).map(_.setHeader("X-Server", "kyo"))
            typeCheck("""val _: HttpFilter[Any, Any, Any, Any, Any] = filter""")
        }
    }

    "noop" - {

        "passes through unchanged" in {
            val filter = HttpFilter.noop
            typeCheck("""val _: HttpFilter[Any, Any, Any, Any, Any] = filter""")
        }
    }

    "andThen" - {

        "composes two passthrough filters" in {
            val f1 = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request.setHeader("X-A", "1"))
            val f2 = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request.setHeader("X-B", "2"))
            val composed = f1.andThen(f2)
            typeCheck("""val _: HttpFilter[Any, Any, Any, Any, Any] = composed""")
        }

        "composes request-reading and passthrough filters" in {
            val reqFilter = new HttpFilter.Request["auth" ~ String, Any, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "auth" ~ String],
                    next: HttpRequest[In & "auth" ~ String] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    val _ = request.fields.auth
                    next(request)
                end apply
            val passFilter = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request)
            val composed = reqFilter.andThen(passFilter)
            typeCheck("""val _: HttpFilter["auth" ~ String, Any, Any, Any, Any] = composed""")
        }

        "composes request-adding filters" in {
            val f1 = new HttpFilter.Request[Any, "a" ~ Int, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "a" ~ Int] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("a", 1))
            val f2 = new HttpFilter.Request[Any, "b" ~ Int, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "b" ~ Int] => HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (kyo.Async & kyo.Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("b", 2))
            val composed = f1.andThen(f2)
            typeCheck("""val _: HttpFilter[Any, "a" ~ Int & "b" ~ Int, Any, Any, Any] = composed""")
        }
    }

end HttpFilterTest

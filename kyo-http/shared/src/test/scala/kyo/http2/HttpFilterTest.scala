package kyo.http2

import kyo.Record
import kyo.Record.~
import kyo.Tag
import kyo.Test

class HttpFilterTest extends Test:

    "request" - {

        "read-only passthrough" in {
            val filter = HttpFilter.request["name" ~ String, Any] {
                [In, Out, S2] =>
                    (req, next) =>
                        val _ = req.name
                        next(req)
            }
            typeCheck("""val _: HttpFilter["name" ~ String, Any, Any, Any, Any] = filter""")
        }

        "adds field to request" in {
            val filter = HttpFilter.request["name" ~ String, "greeting" ~ String] {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req & ("greeting" ~ s"hello ${req.name}"))
            }
            typeCheck("""val _: HttpFilter["name" ~ String, "greeting" ~ String, Any, Any, Any] = filter""")
        }

        "adds both request and response fields" in {
            val filter = HttpFilter.request["name" ~ String, "user" ~ String] {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req & ("user" ~ req.name)).map(_ & ("server" ~ "kyo"))
            }
            typeCheck(
                """val _: HttpFilter["name" ~ String, "user" ~ String, Any, "server" ~ String, Any] = filter"""
            )
        }
    }

    "response" - {

        "reads and returns response unchanged" in {
            val filter = HttpFilter.response["status" ~ Int] {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req).map { res =>
                            val _ = res.status
                            res
                    }
            }
            typeCheck("""val _: HttpFilter[Any, Any, "status" ~ Int, "status" ~ Int, Any] = filter""")
        }

        "reads and adds response field" in {
            val filter = HttpFilter.response["status" ~ Int] {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req).map { res =>
                            res & ("cached" ~ (res.status == 200))
                    }
            }
            typeCheck(
                """val _: HttpFilter[Any, Any, "status" ~ Int, "status" ~ Int & "cached" ~ Boolean, Any] = filter"""
            )
        }
    }

    "transparent" - {

        "pure passthrough" in {
            val filter = HttpFilter.transparent {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req)
            }
            typeCheck("""val _: HttpFilter[Any, Any, Any, Any, Any] = filter""")
        }

        "adds response field" in {
            val filter = HttpFilter.transparent {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req).map(_ & ("server" ~ "kyo"))
            }
            typeCheck("""val _: HttpFilter[Any, Any, Any, "server" ~ String, Any] = filter""")
        }
    }

    "apply" - {

        "reads request and response, adds response field" in {
            val filter = HttpFilter["origin" ~ String, Any, "status" ~ Int] {
                [In, Out, S2] =>
                    (req, next) =>
                        val origin = req.origin
                        next(req).map { res =>
                            res & ("allowOrigin" ~ origin)
                    }
            }
            typeCheck(
                """val _: HttpFilter["origin" ~ String, Any, "status" ~ Int, "status" ~ Int & "allowOrigin" ~ String, Any] = filter"""
            )
        }

        "adds request and response fields" in {
            val filter = HttpFilter["auth" ~ String, "user" ~ String, Any] {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req & ("user" ~ req.auth)).map(_ & ("server" ~ "kyo"))
            }
            typeCheck(
                """val _: HttpFilter["auth" ~ String, "user" ~ String, Any, "server" ~ String, Any] = filter"""
            )
        }
    }

    "andThen" - {

        "composes two response-adding filters" in {
            val f1 = HttpFilter.transparent {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req).map(_ & ("a" ~ 1))
            }
            val f2 = HttpFilter.transparent {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req).map(_ & ("b" ~ 2))
            }
            val composed = f1.andThen(f2)
            typeCheck("""val _: HttpFilter[Any, Any, Any, "a" ~ Int & "b" ~ Int, Any] = composed""")
        }

        "composes request-reading and response-adding filters" in {
            val reqFilter = HttpFilter.request["auth" ~ String, Any] {
                [In, Out, S2] =>
                    (req, next) =>
                        val _ = req.auth
                        next(req)
            }
            val resFilter = HttpFilter.transparent {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req).map(_ & ("server" ~ "kyo"))
            }
            val composed = reqFilter.andThen(resFilter)
            typeCheck("""val _: HttpFilter["auth" ~ String, Any, Any, "server" ~ String, Any] = composed""")
        }

        "composes request-adding filters" in {
            val f1 = HttpFilter.request[Any, "a" ~ Int] {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req & ("a" ~ 1))
            }
            val f2 = HttpFilter.request[Any, "b" ~ Int] {
                [In, Out, S2] =>
                    (req, next) =>
                        next(req & ("b" ~ 2))
            }
            val composed = f1.andThen(f2)
            typeCheck("""val _: HttpFilter[Any, "a" ~ Int & "b" ~ Int, Any, Any, Any] = composed""")
        }
    }

end HttpFilterTest

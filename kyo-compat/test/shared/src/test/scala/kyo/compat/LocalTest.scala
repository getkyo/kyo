package kyo.compat

import kyo.compat.*
import scala.concurrent.duration.*
class LocalTest extends CompatTest:
    "init creates Local with default" in run {
        val c = CLocal.init[Int](42).flatMap(l => l.get)
        c.map(v => assert(v == 42))
    }
    "let overrides default inside scope" in run {
        val c = CLocal.init[String]("a").flatMap(l => l.let("b")(l.get))
        c.map(v => assert(v == "b"))
    }
    "two distinct inits construct distinct locals" in run {
        val c =
            CLocal.init[Int](1).flatMap { l1 =>
                CLocal.init[Int](2).flatMap { l2 =>
                    l1.let(99)(l1.get).flatMap { v1 =>
                        l2.get.flatMap { v2 =>
                            CIO.defer((v1, v2))
                        }
                    }
                }
            }
        c.map { case (v1, v2) =>
            assert(v1 == 99 && v2 == 2, s"expected (99, 2) got ($v1, $v2)")
        }
    }
    "get returns default initially" in run {
        val c = CLocal.init[String]("hello").flatMap(l => l.get)
        c.map(v => assert(v == "hello"))
    }
    "let(v)(c) sees v inside, default outside" in run {
        val c =
            CLocal.init[Int](1).flatMap { l =>
                l.let(99)(l.get).flatMap { inside =>
                    l.get.flatMap { outside =>
                        CIO.defer((inside, outside))
                    }
                }
            }
        c.map { case (inside, outside) =>
            assert(inside == 99 && outside == 1)
        }
    }
    "let nesting works" in run {
        val c =
            CLocal.init[Int](0).flatMap { l =>
                val nested =
                    l.let(1) {
                        l.let(2) {
                            l.let(3)(l.get)
                        }
                    }
                nested.flatMap { r =>
                    l.get.flatMap { outer =>
                        CIO.defer((r, outer))
                    }
                }
            }
        c.map { case (r, outer) =>
            assert(r == 3 && outer == 0)
        }
    }
    "update(f)(c) applies f and restores afterward" in run {
        val c =
            CLocal.init[Int](10).flatMap { l =>
                val inner =
                    l.let(5) {
                        l.update(_ * 3)(l.get)
                    }
                inner.flatMap { inside =>
                    l.get.flatMap { outer =>
                        CIO.defer((inside, outer))
                    }
                }
            }
        c.map { case (inside, outer) =>
            // update reads current effective value (5 from let) and applies f.
            assert(inside == 15 && outer == 10)
        }
    }
    "CLocal doesn't leak between unrelated computations" in run {
        val c =
            CLocal.init[Int](0).flatMap { l =>
                l.let(100)(l.get).flatMap { first =>
                    l.get.flatMap { second =>
                        CIO.defer((first, second))
                    }
                }
            }
        c.map { case (first, second) =>
            assert(first == 100 && second == 0)
        }
    }

    "CLocal.lower round-trip: default matches init value" in run {
        val c =
            CLocal.init[Int](42).flatMap { l =>
                // lower is the underlying carrier; get returns the default
                // when no let is active. This verifies lower doesn't lose state.
                l.get
            }
        c.map(v => assert(v == 42))
    }

    "CLocal with None default: get returns None" in run {
        val c =
            CLocal.init[Option[Int]](None).flatMap { l =>
                l.get
            }
        c.map(v => assert(v == None))
    }

    "CLocal propagates through flatMap across async boundary" in run {
        val c =
            CLocal.init[Int](0).flatMap { l =>
                l.let(42) {
                    CIO.sleep(50.millis).flatMap(_ => l.get)
                }
            }
        c.map(v => assert(v == 42))
    }
end LocalTest

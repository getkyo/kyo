package kyo

import scala.util.*

class CacheTest extends Test:

    "sync" in run {
        var calls = 0
        for
            c <- Caches.init(_.maxSize(4))
            m = c.memo { (v: Int) =>
                calls += 1
                v + 1
            }
            v1 <- m(1)
            v2 <- m(1)
        yield assert(calls == 1 && v1 == 2 && v2 == 2)
        end for
    }

    "async" in run {
        var calls = 0
        for
            c <- Caches.init(_.maxSize(4))
            m = c.memo { (v: Int) =>
                Async.run {
                    calls += 1
                    v + 1
                }.map(_.get)
            }
            v1 <- m(1)
            v2 <- m(1)
        yield assert(calls == 1 && v1 == 2 && v2 == 2)
        end for
    }

    "failure" in run {
        val ex    = new Exception
        var calls = 0
        for
            c <- Caches.init(_.maxSize(4))
            m = c.memo { (v: Int) =>
                Async.run {
                    calls += 1
                    if calls == 1 then
                        throw ex
                    else
                        v + 1
                    end if
                }.map(_.get)
            }
            v1 <- Abort.run[Throwable](m(1))
            v2 <- Abort.run[Throwable](m(1))
        yield assert(calls == 2 && v1 == Result.fail(ex) && v2 == Result.success(2))
        end for
    }
end CacheTest

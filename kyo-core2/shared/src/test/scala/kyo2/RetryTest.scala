package kyo2

class RetryTest extends Test:

    val ex = new Exception

    "no retries" - {
        "ok" in run {
            var calls = 0
            Retry[Any](_.limit(0)) {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }
        "nok" in run {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception](_.limit(0)) {
                    calls += 1
                    throw ex
                }
            }.map { v =>
                assert(v.isFail && calls == 1)
            }
        }
    }

    "retries" - {
        "ok" in run {
            var calls = 0
            Retry[Any](_.limit(3)) {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }
        "nok" in run {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception](_.limit(3)) {
                    calls += 1
                    throw ex
                }
            }.map { v =>
                assert(v.isFail && calls == 4)
            }
        }
    }

    "backoff" in run {
        var calls = 0
        val start = System.currentTimeMillis()
        Abort.run[Exception] {
            Retry[Exception](_.limit(4).exponential(1.milli)) {
                calls += 1
                throw ex
            }
        }.map { v =>
            assert(v.isFail && calls == 5 && (System.currentTimeMillis() - start) >= 15)
        }
    }
end RetryTest

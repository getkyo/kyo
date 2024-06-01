package kyoTest

import kyo.*

class retriesTest extends KyoTest:

    val ex = new Exception

    "no retries" - {
        "ok" in run {
            var calls = 0
            Retries(_.limit(0)) {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }
        "nok" in run {
            var calls = 0
            IOs.attempt {
                Retries(_.limit(0)) {
                    calls += 1
                    throw ex
                }
            }.map { v =>
                assert(v.isFailure && calls == 1)
            }
        }
    }

    "retries" - {
        "ok" in run {
            var calls = 0
            Retries(_.limit(3)) {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }
        "nok" in run {
            var calls = 0
            IOs.attempt {
                Retries(_.limit(3)) {
                    calls += 1
                    throw ex
                }
            }.map { v =>
                assert(v.isFailure && calls == 4)
            }
        }
    }

    "backoff" in run {
        var calls = 0
        val start = System.currentTimeMillis()
        IOs.attempt {
            Retries(_.limit(4).exponential(1.milli)) {
                calls += 1
                throw ex
            }
        }.map { v =>
            assert(v.isFailure && calls == 5 && (System.currentTimeMillis() - start) >= 15)
        }
    }
end retriesTest

package kyo

class RetryTest extends Test:

    val ex = new Exception

    "no retries" - {
        "ok" in run {
            var calls = 0
            Retry[Any](Schedule.never) {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }
        "nok" in run {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception](Schedule.never) {
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
            Retry[Any](Schedule.repeat(3)) {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }
        "nok" in run {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception](Schedule.repeat(3)) {
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
        val start = java.lang.System.currentTimeMillis()
        Abort.run[Exception] {
            Retry[Exception](Schedule.exponentialBackoff(1.milli, 2.0, Duration.Infinity).take(4)) {
                calls += 1
                throw ex
            }
        }.map { v =>
            assert(v.isFailure && calls == 5 && (java.lang.System.currentTimeMillis() - start) >= 15)
        }
    }

    "default schedule" - {
        "succeeds immediately without retries" in run {
            var calls = 0
            Retry[Any] {
                calls += 1
                42
            }.map { v =>
                assert(v == 42 && calls == 1)
            }
        }

        "retries up to max attempts" in run {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception] {
                    calls += 1
                    throw ex
                }
            }.map { v =>
                assert(v.isFailure && calls == 4)
            }
        }
    }

    "panics" - {
        "should not retry on panic" in run {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception](Schedule.repeat(3)) {
                    calls += 1
                    Abort.panic(new RuntimeException("panic"))
                }
            }.map { v =>
                assert(v.isPanic && calls == 1)
            }
        }

        "should not retry on panic with default schedule" in run {
            var calls = 0
            Abort.run[Exception] {
                Retry[Exception] {
                    calls += 1
                    Abort.panic(new RuntimeException("panic"))
                }
            }.map { v =>
                assert(v.isPanic && calls == 1)
            }
        }
    }
end RetryTest

package kyo.ffi.codegen

class AllowlistTest extends kyo.test.Test[Any]:

    "BlockingAllowlist" - {
        "contains common POSIX blocking syscalls" in {
            assert(BlockingAllowlist.contains("read") == true)
            assert(BlockingAllowlist.contains("write") == true)
            assert(BlockingAllowlist.contains("connect") == true)
            assert(BlockingAllowlist.contains("poll") == true)
            assert(BlockingAllowlist.contains("getaddrinfo") == true)
        }
        "does not contain non-blocking symbols" in {
            assert(BlockingAllowlist.contains("crc32") == false)
            assert(BlockingAllowlist.contains("htons") == false)
            assert(BlockingAllowlist.contains("") == false)
        }
        "does not flag bind/listen, which assign an address / mark a socket passive and never block" in {
            // Removing these stops a false-positive @Ffi.blocking warning on socket-server bindings: neither call waits.
            assert(BlockingAllowlist.contains("bind") == false)
            assert(BlockingAllowlist.contains("listen") == false)
        }
    }

    "RetentionAllowlist" - {
        "contains common retaining callback registrars" in {
            assert(RetentionAllowlist.contains("epoll_ctl") == true)
            assert(RetentionAllowlist.contains("pthread_create") == true)
            assert(RetentionAllowlist.contains("signal") == true)
            assert(RetentionAllowlist.contains("atexit") == true)
        }
        "does not contain transient-callback APIs" in {
            assert(RetentionAllowlist.contains("qsort") == false)
            assert(RetentionAllowlist.contains("bsearch") == false)
            assert(RetentionAllowlist.contains("") == false)
        }
    }
end AllowlistTest

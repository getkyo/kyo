package kyo.http2

import kyo.Absent
import kyo.Duration
import kyo.Present
import kyo.Test
import kyo.seconds

class HttpEventTest extends Test:

    override protected def useTestClient: Boolean = false

    "construction" - {
        "data only" in {
            val e = HttpEvent("hello")
            assert(e.data == "hello")
            assert(e.event == Absent)
            assert(e.id == Absent)
            assert(e.retry == Absent)
        }

        "with all fields" in {
            val e = HttpEvent(
                data = 42,
                event = Present("update"),
                id = Present("evt-1"),
                retry = Present(5.seconds)
            )
            assert(e.data == 42)
            assert(e.event == Present("update"))
            assert(e.id == Present("evt-1"))
            assert(e.retry == Present(5.seconds))
        }

        "with event name" in {
            val e = HttpEvent("data", event = Present("message"))
            assert(e.event == Present("message"))
        }

        "with id" in {
            val e = HttpEvent("data", id = Present("123"))
            assert(e.id == Present("123"))
        }

        "with retry" in {
            val e = HttpEvent("data", retry = Present(10.seconds))
            assert(e.retry == Present(10.seconds))
        }
    }

    "covariance" - {
        "data type is covariant" in {
            val e: HttpEvent[Any] = HttpEvent(42)
            assert(e.data.equals(42))
        }
    }

    "equality" - {
        "same events are equal" in {
            assert(HttpEvent("a") == HttpEvent("a"))
            assert(HttpEvent("a", Present("e")) == HttpEvent("a", Present("e")))
        }

        "different events are not equal" in {
            assert(HttpEvent("a") != HttpEvent("b"))
            assert(HttpEvent("a") != HttpEvent("a", Present("e")))
        }

        "unequal event name" in {
            val a = HttpEvent("hello", event = Present("a"))
            val b = HttpEvent("hello", event = Present("b"))
            assert(a != b)
        }

        "present vs absent id" in {
            val a = HttpEvent("hello", id = Present("1"))
            val b = HttpEvent("hello")
            assert(a != b)
        }
    }

    "copy" - {
        "modifies single field" in {
            val e1 = HttpEvent("data")
            val e2 = e1.copy(event = Present("update"))
            assert(e1.event == Absent)
            assert(e2.event == Present("update"))
            assert(e2.data == "data")
        }

        "preserves all unchanged fields" in {
            val original = HttpEvent("data", event = Present("type"), id = Present("1"))
            val copied   = original.copy(data = "new-data")
            assert(copied.data == "new-data")
            assert(copied.event == Present("type"))
            assert(copied.id == Present("1"))
        }
    }

end HttpEventTest

package kyo

class HttpEventTest extends Test:

    "construction" - {
        "data only" in {
            val event = HttpEvent("hello")
            assert(event.data == "hello")
            assert(event.event == Absent)
            assert(event.id == Absent)
            assert(event.retry == Absent)
        }

        "all fields" in {
            val event = HttpEvent(
                data = "payload",
                event = Present("update"),
                id = Present("123"),
                retry = Present(5.seconds)
            )
            assert(event.data == "payload")
            assert(event.event == Present("update"))
            assert(event.id == Present("123"))
            assert(event.retry == Present(5.seconds))
        }

        "typed data" in {
            val event = HttpEvent(42)
            assert(event.data == 42)
        }
    }

    "equality" - {
        "equal events" in {
            val a = HttpEvent("hello", event = Present("test"))
            val b = HttpEvent("hello", event = Present("test"))
            assert(a == b)
        }

        "unequal data" in {
            val a = HttpEvent("hello")
            val b = HttpEvent("world")
            assert(a != b)
        }

        "unequal event name" in {
            val a = HttpEvent("hello", event = Present("a"))
            val b = HttpEvent("hello", event = Present("b"))
            assert(a != b)
        }

        "present vs absent" in {
            val a = HttpEvent("hello", id = Present("1"))
            val b = HttpEvent("hello")
            assert(a != b)
        }
    }

    "copy" - {
        "preserves unchanged fields" in {
            val original = HttpEvent("data", event = Present("type"), id = Present("1"))
            val copied   = original.copy(data = "new-data")
            assert(copied.data == "new-data")
            assert(copied.event == Present("type"))
            assert(copied.id == Present("1"))
        }
    }

end HttpEventTest

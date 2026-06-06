package kyo

import kyo.internal.tasty.binary.ByteView

class ByteViewTest extends Test:

    // Test 1: peekByte reads at offset without advancing position
    "peekByte reads byte at offset without advancing position" in run {
        val bytes = Array[Byte](10, 20, 30, 40)
        val view  = ByteView(bytes)
        val b     = view.peekByte(2)
        assert(b == 30.toByte)
        assert(view.position == 0)
    }

    // Test 2: readByte advances position by 1 and returns correct byte
    "readByte advances position by 1 and returns correct byte" in run {
        // §839 case 3; direct ByteView cursor test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val bytes = Array[Byte](0x5c.toByte, 0xa1.toByte, 0xab.toByte)
        val view  = ByteView(bytes)
        val b0    = view.readByte()
        assert(b0 == 0x5c.toByte)
        assert(view.position == 1)
        val b1 = view.readByte()
        assert(b1 == 0xa1.toByte)
        assert(view.position == 2)
    }

    // Test 3: readByte at end produces ArrayIndexOutOfBoundsException (or a wrapping Error on Scala.js)
    "readByte at end produces ArrayIndexOutOfBoundsException" in run {
        // §839 case 3; direct ByteView cursor test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val bytes = Array[Byte](0x80.toByte)
        val view  = ByteView(bytes)
        view.readByte() // consume the only byte
        assert(view.remaining == 0)
        try
            view.readByte()
            fail("Expected ArrayIndexOutOfBoundsException but no exception was thrown")
        catch
            case _: ArrayIndexOutOfBoundsException => succeed
            case ex: java.lang.Error
                if ex.getCause != null && ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] => succeed
        end try
    }

    // Test 4: subView shares underlying array, has correct start/end/position
    "subView shares underlying array with correct bounds" in run {
        val bytes = Array[Byte](1, 2, 3, 4, 5)
        val view  = ByteView(bytes)
        val sub   = view.subView(1, 4)
        assert(sub.start == 1)
        assert(sub.end == 4)
        assert(sub.position == 1)
        // Verify same underlying array reference
        assert(sub.bytes eq bytes)
    }

    // Test 5: goto sets position to addr
    "goto sets position to given address" in run {
        // §839 case 3; direct ByteView cursor test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val bytes = Array[Byte](0, 1, 2, 3, 4, 5)
        val view  = ByteView(bytes)
        view.goto(3)
        assert(view.position == 3)
        val b = view.readByte()
        assert(b == 3.toByte)
        assert(view.position == 4)
    }

    // Test 6: remaining returns end - position
    "remaining returns end minus current position" in run {
        // §839 case 3; direct ByteView cursor test, single-threaded, no suspension.
        import AllowUnsafe.embrace.danger
        val bytes = Array[Byte](10, 20, 30, 40, 50)
        val view  = ByteView(bytes)
        assert(view.remaining == 5)
        view.readByte()
        assert(view.remaining == 4)
        view.goto(3)
        assert(view.remaining == 2)
    }

    "subView rejects negative from" in run {
        val bytes = Array.fill(10)(0.toByte)
        val view  = ByteView(bytes)
        try
            view.subView(-1, 5)
            fail("Expected ArrayIndexOutOfBoundsException but no exception was thrown")
        catch
            case ex: ArrayIndexOutOfBoundsException =>
                assert(ex.getMessage.contains("from=-1"), s"Expected message to contain 'from=-1' but was: ${ex.getMessage}")
        end try
    }

    "subView rejects until greater than length" in run {
        val bytes = Array.fill(10)(0.toByte)
        val view  = ByteView(bytes)
        try
            view.subView(0, 11)
            fail("Expected ArrayIndexOutOfBoundsException but no exception was thrown")
        catch
            case ex: ArrayIndexOutOfBoundsException =>
                assert(ex.getMessage.contains("until=11"), s"Expected message to contain 'until=11' but was: ${ex.getMessage}")
        end try
    }

end ByteViewTest

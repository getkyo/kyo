package kyo

import kyo.internal.tasty.symbol.SingleAssign

/** Tests for SingleAssign write-once slot behavior.
  *
  * Phase 21g (T2). Covers set/get round trip and double-set exception.
  */
class SingleAssignTest extends Test:

    import AllowUnsafe.embrace.danger

    // Test 5 (T2, SingleAssign): set/get round trip returns the assigned value.
    // Given: SingleAssign[Int]; slot.set(7).
    // When: slot.get().
    // Then: returns 7.
    // Pins: T2.
    "SingleAssign set/get round trip returns assigned value" in {
        val slot = new SingleAssign[Int]
        slot.set(7)
        val result = slot.get()
        assert(result == 7, s"Expected 7 but got $result")
    }

    // Test 6 (T2, SingleAssign): second set throws IllegalStateException containing "already set".
    // The plan draft said "already assigned" but SingleAssign.scala line 26 says "SingleAssign already set".
    // Test matches the actual implementation message.
    // Given: SingleAssign[Int] with prior set(7).
    // When: second set(8).
    // Then: throws IllegalStateException whose message contains "already set".
    // Pins: T2.
    "SingleAssign second set throws IllegalStateException with already-set message" in {
        val slot = new SingleAssign[Int]
        slot.set(7)
        var thrown = false
        var msg    = ""
        try
            slot.set(8)
        catch
            case ex: IllegalStateException =>
                thrown = true
                msg = ex.getMessage
        end try
        assert(thrown, "Expected IllegalStateException on second set but nothing was thrown")
        assert(
            msg.contains("already set"),
            s"Expected message to contain 'already set' but got: $msg"
        )
    }

    // Bonus: get() before set throws IllegalStateException containing "not yet set".
    // Given: fresh SingleAssign[Int] (never assigned).
    // When: slot.get().
    // Then: throws IllegalStateException whose message contains "not yet set".
    "SingleAssign get before set throws IllegalStateException" in {
        val slot   = new SingleAssign[Int]
        var thrown = false
        try
            slot.get()
        catch
            case _: IllegalStateException =>
                thrown = true
        end try
        assert(thrown, "Expected IllegalStateException on get before set but nothing was thrown")
    }

    // isSet returns false before assignment, true after.
    "SingleAssign isSet returns false before set and true after set" in {
        val slot = new SingleAssign[String]
        assert(!slot.isSet, "Expected isSet == false before assignment")
        slot.set("hi")
        assert(slot.isSet, "Expected isSet == true after assignment")
    }

end SingleAssignTest

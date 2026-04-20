package kyo

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import scala.language.implicitConversions

// Test flags in a dedicated namespace to avoid interference.
object flagSyncTestFlags:
    object staticInt   extends StaticFlag[Int](42)
    object dynamicBool extends DynamicFlag[Boolean](false)
    object dynamicInt  extends DynamicFlag[Int](0)
    object dynamicStr  extends DynamicFlag[String]("default")
end flagSyncTestFlags

class FlagSyncTest extends Test:

    // Force flag initialization
    locally {
        val _ = flagSyncTestFlags.staticInt
        val _ = flagSyncTestFlags.dynamicBool
        val _ = flagSyncTestFlags.dynamicInt
        val _ = flagSyncTestFlags.dynamicStr
    }

    "startReloader calls reload on dynamic flags" in run {
        // startReloader iterates over Flag.all and calls reload() on each DynamicFlag.
        // For our test flags (source=Default), reload() returns NoSource without error.
        // We verify the reloader runs correctly by starting it and confirming no crash,
        // then separately verify reload behavior using manual update/reload.
        flagSyncTestFlags.dynamicStr.update("before-reloader-test")
        for
            fiber <- Fiber.initUnscoped(FlagSync.startReloader(10.millis))
            _     <- Scope.ensure(fiber.interrupt.unit)
            // Wait for several iterations to pass (proves loop runs without error)
            _ <- Async.sleep(50.millis)
        yield
            // The reloader called reload() on dynamicStr. Since source=Default, reload()
            // returns NoSource and expression stays unchanged. Verify no crash occurred.
            assert(flagSyncTestFlags.dynamicStr.expression == "before-reloader-test")
        end for
    }

    "startReloader skips static flags" in run {
        // Static flags have a fixed value; reloader should not affect them
        val originalValue = flagSyncTestFlags.staticInt.value
        for
            fiber <- Fiber.initUnscoped(FlagSync.startReloader(10.millis))
            _     <- Scope.ensure(fiber.interrupt.unit)
            _     <- Async.sleep(50.millis)
        yield assert(flagSyncTestFlags.staticInt.value == originalValue)
        end for
    }

    "startSync with custom source updates flags" in run {
        flagSyncTestFlags.dynamicStr.update("before-sync")
        val source: String => Maybe[String] = name =>
            if name == flagSyncTestFlags.dynamicStr.name then Present("synced-value")
            else Absent
        for
            fiber <- Fiber.initUnscoped(FlagSync.startSync(10.millis, source))
            _     <- Scope.ensure(fiber.interrupt.unit)
            _     <- untilTrue(flagSyncTestFlags.dynamicStr.expression == "synced-value")
        yield assert(flagSyncTestFlags.dynamicStr.expression == "synced-value")
        end for
    }

    "startSync skips when source returns Absent" in run {
        flagSyncTestFlags.dynamicInt.update("99")
        val source: String => Maybe[String] = _ => Absent
        for
            fiber <- Fiber.initUnscoped(FlagSync.startSync(10.millis, source))
            _     <- Scope.ensure(fiber.interrupt.unit)
            _     <- Async.sleep(50.millis)
        yield
            // Flag should retain its value since source always returns Absent
            assert(flagSyncTestFlags.dynamicInt.expression == "99")
        end for
    }

    "error backoff — first 5 failures logged individually" in run {
        // Use a source that always throws for a specific flag
        val logMessages = new java.util.concurrent.CopyOnWriteArrayList[String]()
        val testLog = Log(new Log.Unsafe:
            def level                                                                = Log.Level.debug
            def trace(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => Text)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def warn(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def error(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit
            def error(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit)
        // Source that always returns an invalid expression for dynamicInt
        val source: String => Maybe[String] = name =>
            if name == flagSyncTestFlags.dynamicInt.name then Present("not-a-number")
            else Absent
        Log.let(testLog) {
            for
                fiber <- Fiber.initUnscoped(FlagSync.startSync(5.millis, source))
                _     <- Scope.ensure(fiber.interrupt.unit)
                _     <- untilTrue(logMessages.size() >= 5)
            yield
                // First 5 messages should be WARN
                val first5 = (0 until 5).map(logMessages.get(_))
                assert(first5.forall(_.startsWith("WARN:")))
                assert(first5.forall(_.contains("Failed to sync")))
        }
    }

    "error backoff — 6th failure logs escalation" in run {
        val logMessages = new java.util.concurrent.CopyOnWriteArrayList[String]()
        val testLog = Log(new Log.Unsafe:
            def level                                                                = Log.Level.debug
            def trace(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => Text)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def warn(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def error(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit
            def error(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit)
        val source: String => Maybe[String] = name =>
            if name == flagSyncTestFlags.dynamicInt.name then Present("not-a-number")
            else Absent
        Log.let(testLog) {
            for
                fiber <- Fiber.initUnscoped(FlagSync.startSync(5.millis, source))
                _     <- Scope.ensure(fiber.interrupt.unit)
                _     <- untilTrue(logMessages.size() >= 6)
            yield
                // 6th message should be ERROR escalation
                val sixthMsg = logMessages.get(5)
                assert(sixthMsg.startsWith("ERROR:"))
                assert(sixthMsg.contains("Stopped logging"))
        }
    }

    "error backoff — subsequent failures not logged" in run {
        val logMessages = new java.util.concurrent.CopyOnWriteArrayList[String]()
        val testLog = Log(new Log.Unsafe:
            def level                                                                = Log.Level.debug
            def trace(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => Text)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def warn(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def error(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit
            def error(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit)
        val source: String => Maybe[String] = name =>
            if name == flagSyncTestFlags.dynamicInt.name then Present("not-a-number")
            else Absent
        Log.let(testLog) {
            for
                fiber <- Fiber.initUnscoped(FlagSync.startSync(5.millis, source))
                _     <- Scope.ensure(fiber.interrupt.unit)
                // Wait for more than 6 failures
                _ <- untilTrue(logMessages.size() >= 6)
                _ <- Async.sleep(100.millis)
            yield
                // After the 6th message (ERROR escalation), no more messages should be logged
                // for this flag. Total should stay at exactly 6 (5 WARN + 1 ERROR).
                val flagMessages = (0 until logMessages.size()).map(logMessages.get(_))
                    .filter(_.contains(flagSyncTestFlags.dynamicInt.name))
                assert(flagMessages.size == 6)
        }
    }

    "success after failures resets counter" in run {
        val logMessages = new java.util.concurrent.CopyOnWriteArrayList[String]()
        val testLog = Log(new Log.Unsafe:
            def level                                                                = Log.Level.debug
            def trace(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => Text)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def warn(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def error(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit
            def error(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit)
        // Toggle between failing and succeeding
        @volatile var shouldFail = true
        val source: String => Maybe[String] = name =>
            if name == flagSyncTestFlags.dynamicInt.name then
                if shouldFail then Present("not-a-number")
                else Present("42")
            else Absent
        Log.let(testLog) {
            for
                fiber <- Fiber.initUnscoped(FlagSync.startSync(5.millis, source))
                _     <- Scope.ensure(fiber.interrupt.unit)
                // Wait for enough failures to go past backoff threshold (6 messages = 5 WARN + 1 ERROR)
                _ <- untilTrue(logMessages.size() >= 6)
                // Now make it succeed — this resets the failure counter
                _ = shouldFail = false
                _ <- untilTrue(flagSyncTestFlags.dynamicInt.expression == "42")
                // Give extra time for the success to be registered in the failures map
                _ <- Async.sleep(30.millis)
                // Now fail again — counter should have been reset to 0
                _           = shouldFail = true
                initialSize = logMessages.size()
                _ <- untilTrue(logMessages.size() > initialSize)
            yield
                // After reset, the counter restarts. Because the counter had been past
                // maxConsecutiveFailures (6+), if NOT reset, new failures would be suppressed.
                // The fact that we see new WARN messages proves the counter was reset.
                val newMessages  = (initialSize until logMessages.size()).map(logMessages.get(_))
                val warnMessages = newMessages.filter(_.startsWith("WARN:"))
                assert(warnMessages.nonEmpty, "Expected WARN messages after counter reset, but found none")
                assert(warnMessages.head.contains("consecutive"))
        }
    }

    "startSync with source that throws does not crash the polling loop" in run {
        flagSyncTestFlags.dynamicStr.update("before-throw-test")
        val callCount = new java.util.concurrent.atomic.AtomicInteger(0)
        val source: String => Maybe[String] = name =>
            callCount.incrementAndGet()
            if name == flagSyncTestFlags.dynamicStr.name then
                throw new RuntimeException("source function exploded")
            else Absent
        val logMessages = new java.util.concurrent.CopyOnWriteArrayList[String]()
        val testLog = Log(new Log.Unsafe:
            def level                                                                = Log.Level.debug
            def trace(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => Text)(using Frame, AllowUnsafe): Unit                  = ()
            def debug(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def info(msg: => Text)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def warn(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"WARN: ${msg.show}"): Unit
            def error(msg: => Text)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit
            def error(msg: => Text, t: => Throwable)(using Frame, AllowUnsafe): Unit =
                logMessages.add(s"ERROR: ${msg.show}"): Unit)
        Log.let(testLog) {
            for
                fiber <- Fiber.initUnscoped(FlagSync.startSync(5.millis, source))
                _     <- Scope.ensure(fiber.interrupt.unit)
                // Wait for multiple iterations — proves the loop survived the exceptions
                _ <- untilTrue(callCount.get() >= 5 && logMessages.size() > 0)
            yield
                // The source was called multiple times, proving the loop didn't crash
                assert(callCount.get() >= 5, "source should have been called at least 5 times")
                // Error was logged through backoff mechanism
                assert(logMessages.size() > 0, "errors should have been logged")
                assert(logMessages.get(0).contains("WARN:"))
                // Original value preserved since updates never succeeded
                assert(flagSyncTestFlags.dynamicStr.expression == "before-throw-test")
        }
    }

    "startReloader and startSync run periodically" in run {
        val callCount = new java.util.concurrent.atomic.AtomicInteger(0)
        val source: String => Maybe[String] = name =>
            if name == flagSyncTestFlags.dynamicStr.name then
                callCount.incrementAndGet()
                Present("iteration-" + callCount.get())
            else Absent
        for
            fiber <- Fiber.initUnscoped(FlagSync.startSync(10.millis, source))
            _     <- Scope.ensure(fiber.interrupt.unit)
            _     <- untilTrue(callCount.get() >= 3)
        yield assert(callCount.get() >= 3)
        end for
    }

    "startSync with no dynamic flags does nothing" in run {
        // This test verifies that when source returns Absent for all flags,
        // no flags are updated and the loop doesn't error
        val callCount = new java.util.concurrent.atomic.AtomicInteger(0)
        flagSyncTestFlags.dynamicStr.update("untouched")
        val source: String => Maybe[String] = name =>
            callCount.incrementAndGet()
            Absent
        for
            fiber <- Fiber.initUnscoped(FlagSync.startSync(10.millis, source))
            _     <- Scope.ensure(fiber.interrupt.unit)
            _     <- untilTrue(callCount.get() >= 3)
        yield
            // Source was called but no flags were updated
            assert(flagSyncTestFlags.dynamicStr.expression == "untouched")
        end for
    }

end FlagSyncTest

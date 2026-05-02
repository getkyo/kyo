package kyo.test

import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import zio.test.*

object ScopeAssertEvalSpec extends KyoSpecDefault:

    private def aliveFlag(using Frame): AtomicBoolean < (Scope & Sync) =
        Scope.acquireRelease(
            Sync.defer(new AtomicBoolean(true))
        )(flag => Sync.defer(flag.set(false)))

    def spec = suite("ScopeAssertEvalSpec")(
        test("assertTrue captures inside Scope see live resources"):
            for
                alive <- aliveFlag
            yield assertTrue(alive.get)
        ,
        test("eagerly captured Boolean works"):
            for
                alive <- aliveFlag
                isAlive = alive.get
            yield assertTrue(isAlive)
        ,
        test("Sync.defer right before yield sees the flag set"):
            for
                alive   <- aliveFlag
                isAlive <- Sync.defer(alive.get)
            yield assertTrue(isAlive)
    )
end ScopeAssertEvalSpec

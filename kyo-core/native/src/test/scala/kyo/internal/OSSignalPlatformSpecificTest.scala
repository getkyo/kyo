package kyo.internal

import kyo.*

class OSSignalPlatformSpecificTest extends kyo.test.Test[Any]:

    private class Probe(deliveries: Int*):
        private val pending = scala.collection.mutable.Queue(deliveries*)
        val resets          = scala.collection.mutable.ListBuffer.empty[Int]
        val dispatcher      = new NativeSignalDispatcher(
            () => if pending.isEmpty then -1 else pending.dequeue(),
            _ => 0,
            signum => resets += signum
        )
    end Probe

    "a dispatcher whose pipe ends restores the default action of every installed signal" in {
        val probe = new Probe(10)
        var runs  = 0
        assert(probe.dispatcher.register(10, () => runs += 1))
        assert(probe.dispatcher.register(12, () => ()))
        probe.dispatcher.run()
        assert(runs == 1)
        assert(probe.resets.toSet == Set(10, 12))
    }

    "a fatal escaping a handler restores the defaults and still propagates" in {
        val probe = new Probe(10, 10)
        val fatal = new InternalError("fatal on purpose")
        var runs  = 0
        assert(probe.dispatcher.register(
            10,
            () =>
                runs += 1; throw fatal
        ))
        val thrown =
            try
                probe.dispatcher.run()
                Absent
            catch case e: InternalError => Present(e)
        assert(thrown.exists(_ eq fatal))
        assert(runs == 1)
        assert(probe.resets.toList == List(10))
    }

    "a non-fatal handler failure does not stop the dispatcher" in {
        val probe = new Probe(10, 10)
        var runs  = 0
        val _     = probe.dispatcher.register(
            10,
            () =>
                runs += 1; if runs == 1 then throw new RuntimeException("on purpose")
        )
        val thread = Thread.currentThread()
        val prior  = thread.getUncaughtExceptionHandler
        thread.setUncaughtExceptionHandler((_, _) => ())
        try probe.dispatcher.run()
        finally thread.setUncaughtExceptionHandler(prior)
        assert(runs == 2)
    }

    "a handler registered after the dispatcher stopped is reset at once" in {
        val probe = new Probe()
        probe.dispatcher.run()
        assert(!probe.dispatcher.register(14, () => ()))
        assert(probe.resets.toList == List(14))
    }
end OSSignalPlatformSpecificTest

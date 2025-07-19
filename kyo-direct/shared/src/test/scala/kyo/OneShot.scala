package kyo

object IterableTest:

    // fake Iterable, that cannot be replayed
    def oneShot[A](a: A*): Iterable[A] =
        val used = new java.util.concurrent.atomic.AtomicBoolean(false)
        new Iterable[A]:
            def iterator: Iterator[A] =
                if !used.compareAndSet(false, true) then
                    throw new IllegalStateException("Already consumed!")
                else Iterator(a*)

        end new
    end oneShot
end IterableTest

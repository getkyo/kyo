package kyo.internal

/** This class provides a platform-specific implementation of signal handling.
  */
private[kyo] object Signal extends SignalPlatformSpecific:
    /** A handler for signals. */
    abstract class Handler:
        def apply(signal: String, handle: => Unit): Unit

    object Handler:
        /** A no-op handler for signals. Does nothing. */
        object Noop extends Handler:
            def apply(signal: String, handle: => Unit): Unit = ()

            override def toString = s"Signal.Handler.Noop"
        end Noop
    end Handler
end Signal

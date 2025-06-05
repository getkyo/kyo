package io.aeron

// Forward declaration for FragmentAssembler, will be properly stubbed later
// import io.aeron.logbuffer.Header // Required by FragmentAssembler signature if we were to detail it here
// import org.agrona.DirectBuffer // Required by FragmentAssembler signature

// It's common for such types to be interfaces or abstract classes in Java,
// but a simple class stub is fine for Scala Native if we don't implement it.
class Subscription private[aeron] ():
    def isConnected(): Boolean                                            = ???
    def poll(fragmentHandler: FragmentAssembler, fragmentLimit: Int): Int = ???
    def close(): Unit                                                     = ???
end Subscription

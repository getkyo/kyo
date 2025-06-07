package io.aeron

import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import scala.collection.mutable

// Forward declaration for FragmentAssembler, will be properly stubbed later
// import io.aeron.logbuffer.Header // Required by FragmentAssembler signature if we were to detail it here
// import org.agrona.DirectBuffer // Required by FragmentAssembler signature

// It's common for such types to be interfaces or abstract classes in Java,
// but a simple class stub is fine for Scala Native if we don't implement it.
class Subscription:
    private val messageQueue = mutable.Queue[Array[Byte]]()

    private[aeron] def receive(bytes: Array[Byte]): Unit =
        messageQueue.enqueue(bytes)

    def isConnected(): Boolean = true

    def poll(fragmentHandler: FragmentAssembler, fragmentLimit: Int): Int =
        var fragmentsRead = 0
        while fragmentsRead < fragmentLimit && messageQueue.nonEmpty do
            val msgBytes = messageQueue.dequeue()
            val buffer   = DirectBuffer(msgBytes)
            // The Header is currently null, which might be an issue.
            // For now, we'll pass null and see if the tests require a mock Header.
            fragmentHandler.handler(buffer, 0, msgBytes.length, null)
            fragmentsRead += 1
        end while
        fragmentsRead
    end poll

    def close(): Unit = {}
end Subscription

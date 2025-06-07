package io.aeron

import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer

// Scala's SAM (Single Abstract Method) conversion should handle this for a Java interface.
// For the stub, we define the constructor parameter explicitly as a function type.
class FragmentAssembler(
    val handler: (DirectBuffer, Int, Int, Header) => Unit
) {
    // after instantiation. It's passed to Subscription.poll.
    // This simple constructor should suffice for stubbing purposes.
}

package kyo

import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.driver.MediaDriver
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import upickle.default.*

/** High-performance publish-subscribe messaging for local and distributed systems.
  *
  * Topic provides reliable, typed messaging built on Aeron's efficient transport protocol. It excels at ultra-low latency inter-process
  * communication (IPC) on the same machine through shared memory, while also supporting efficient UDP multicast for message distribution
  * and reliable UDP unicast between remote services.
  *
  * Messages are automatically serialized and deserialized using upickle, requiring only a ReadWriter type class instance (aliased as
  * [[Topic.AsMessage]]). The transport layer handles message fragmentation and flow control automatically.
  *
  * Publishing messages is done through [[Topic.publish]], which handles backpressure and connection management automatically. Subscribers
  * use [[Topic.stream]] to receive typed messages with automatic reassembly and connection handling.
  *
  * Type safety is enforced by using the message type's Tag to generate unique Aeron stream IDs - this means each exact type gets its own
  * channel, with no subtype polymorphism. A stream of a parent type cannot receive messages published as a subtype, and vice versa. Since
  * stream IDs are generated using a hash function, there is a theoretical possibility of hash collisions between different types. To
  * mitigate this, a runtime type check is performed on message receipt to ensure the received message type matches the expected type.
  *
  * @see
  *   [[https://aeron.io/]] for documentation on Aeron URIs and more.
  * @see
  *   [[https://github.com/com-lihaoyi/upickle]] for documentation on serialization.
  */
opaque type Topic <: Env[Aeron] = Env[Aeron]

object Topic:

    /** Exception indicating backpressure from the messaging system.
      *
      * Thrown when the system cannot immediately handle more messages and needs to apply backpressure for flow control.
      */
    case class Backpressured()(using Frame) extends KyoException

    /** Type alias for upickle serialization.
      *
      * Messages must have a ReadWriter instance to be published or consumed.
      */
    type AsMessage[A] = ReadWriter[A]

    /** Default retry schedule for handling backpressure scenarios.
      */
    val defaultRetrySchedule = Schedule.linear(10.millis).min(Schedule.fixed(1.second)).jitter(0.2)

    /** Handles Topic with an embedded Aeron MediaDriver.
      *
      * Creates and manages the lifecycle of an embedded MediaDriver, ensuring proper cleanup through IO.ensure.
      *
      * @param v
      *   The computation requiring Topic capabilities
      * @return
      *   The computation result within Async context
      */
    def run[A: Flat, S](v: A < (Topic & S))(using Frame): A < (Async & S) =
        IO {
            val driver = MediaDriver.launchEmbedded()
            IO.ensure(driver.close()) {
                run(driver)(v)
            }
        }

    /** Handles Topic with a provided MediaDriver.
      *
      * Uses an existing MediaDriver instance, allowing for more control over the Aeron setup. The caller is responsible for closing the
      * provided MediaDriver instance.
      *
      * @param driver
      *   The MediaDriver instance to use
      * @param v
      *   The computation requiring Topic capabilities
      * @return
      *   The computation result within Async context
      */
    def run[A: Flat, S](driver: MediaDriver)(v: A < (Topic & S))(using Frame): A < (Async & S) =
        IO {
            val aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()))
            IO.ensure(aeron.close()) {
                run(aeron)(v)
            }
        }

    /** Handles Topic with a provided Aeron instance.
      *
      * Directly uses an existing Aeron instance for maximum configuration flexibility. The caller is responsible for closing the provided
      * Aeron instance.
      *
      * @param aeron
      *   The Aeron instance to use
      * @param v
      *   The computation requiring Topic capabilities
      * @return
      *   The computation result within Async context
      */
    def run[A: Flat, S](aeron: Aeron)(v: A < (Topic & S))(using Frame): A < (Async & S) =
        Env.run(aeron)(v)

    /** Publishes a stream of messages to a specified Aeron URI.
      *
      * Messages are published with automatic handling of backpressure and connection issues. The stream is typed and uses efficient binary
      * serialization for message transport.
      *
      * @param uri
      *   The Aeron URI to publish to. Examples:
      *   - "aeron:ipc" for efficient inter-process communication on same machine
      *   - "aeron:udp?endpoint=localhost:40123" for UDP unicast
      *   - "aeron:udp?endpoint=224.1.1.1:40123|interface=192.168.1.1" for UDP multicast
      * @param retrySchedule
      *   Schedule for retrying on backpressure
      * @param stream
      *   The stream of messages to publish
      * @tparam A
      *   The type of messages being published
      * @tparam S
      *   Additional effects in the computation
      * @return
      *   Unit wrapped in Topic effect with potential Closed or Backpressured aborts
      */
    def publish[A: ReadWriter](
        aeronUri: String,
        retrySchedule: Schedule = defaultRetrySchedule
    )[S](stream: Stream[A, S])(using frame: Frame, tag: Tag[A]): Unit < (Topic & S & Abort[Closed | Backpressured] & Async) =
        Env.use[Aeron] { aeron =>
            IO {
                // register the publication with Aeron using type's hash as stream ID
                val publication = aeron.addPublication(aeronUri, tag.hash.abs)

                // reuse buffer claim to avoid allocations on hot path
                val bufferClaim = new BufferClaim

                // cache backpressure failure for performance
                val backpressured = Abort.fail(Backpressured())

                // ensure publication is closed after use
                IO.ensure(IO(publication.close())) {
                    stream.foreachChunk { messages =>
                        Retry[Backpressured](retrySchedule) {
                            IO {
                                if !publication.isConnected() then backpressured
                                else
                                    // serialize messages with type tag for runtime verification
                                    val bytes  = writeBinary((tag.raw, messages))
                                    val result = publication.tryClaim(bytes.length, bufferClaim)
                                    if result > 0 then
                                        // write directly to claimed buffer region
                                        val buffer = bufferClaim.buffer()
                                        val offset = bufferClaim.offset()
                                        buffer.putBytes(offset, bytes)
                                        bufferClaim.commit()
                                    else
                                        result match
                                            case Publication.BACK_PRESSURED =>
                                                // triggers a retry if the schedule allows
                                                backpressured
                                            case Publication.NOT_CONNECTED =>
                                                Abort.fail(Closed("Not connected", frame))
                                            case Publication.ADMIN_ACTION =>
                                                Abort.fail(Closed("Admin action", frame))
                                            case Publication.CLOSED =>
                                                Abort.fail(Closed("Publication closed", frame))
                                            case _ =>
                                                Abort.fail(Closed(s"Unknown error: $result", frame))
                                    end if
                            }
                        }
                    }
                }
            }
        }

    /** Creates a stream of messages from a specified Aeron URI.
      *
      * Subscribes to messages with automatic handling of backpressure and connection issues. Messages are typed and automatically
      * deserialized from binary format. The stream automatically reassembles fragmented messages, verifies message types match the expected
      * type, handles connection issues with configurable retry behavior, and cleans up resources when closed.
      *
      * @param uri
      *   The Aeron URI to subscribe to. Examples:
      *   - "aeron:ipc" for efficient inter-process communication on same machine
      *   - "aeron:udp?endpoint=localhost:40123" for UDP unicast
      *   - "aeron:udp?endpoint=224.1.1.1:40123|interface=192.168.1.1" for UDP multicast
      * @param retrySchedule
      *   Schedule for retrying on backpressure
      * @tparam A
      *   The type of messages to receive
      * @return
      *   A stream of messages within Topic effect with potential Backpressured aborts
      */
    def stream[A: ReadWriter](
        aeronUri: String,
        retrySchedule: Schedule = defaultRetrySchedule
    )(using tag: Tag[A], frame: Frame): Stream[A, Topic & Abort[Backpressured] & Async] =
        Stream {
            Env.use[Aeron] { aeron =>
                IO {
                    // register subscription with Aeron using type's hash as stream ID
                    val subscription = aeron.addSubscription(aeronUri, tag.hash.abs)

                    // cache backpressure failure for performance
                    val backpressured = Abort.fail(Backpressured())

                    // temporary storage for reassembled message
                    var result: Maybe[(String, Chunk[A])] = Absent

                    // handler that reassembles message fragments
                    val handler =
                        new FragmentAssembler((buffer: DirectBuffer, offset: Int, length: Int, header: Header) =>
                            val bytes = new Array[Byte](length)
                            buffer.getBytes(offset, bytes)
                            result = Maybe(readBinary[(String, Chunk[A])](bytes))
                        )

                    // ensure subscription is closed after use
                    IO.ensure(IO(subscription.close())) {
                        def loop(): Unit < (Emit[Chunk[A]] & Async & Abort[Backpressured]) =
                            Retry[Backpressured](retrySchedule) {
                                IO {
                                    if !subscription.isConnected() then backpressured
                                    else
                                        // clear previous result before polling
                                        result = Absent
                                        val fragmentsRead = subscription.poll(handler, 1)
                                        if fragmentsRead == 0 then
                                            backpressured
                                        else
                                            result match
                                                case Present((tag2, messages)) =>
                                                    // verify message type matches expected type
                                                    if tag2 != tag.raw then
                                                        Abort.panic(
                                                            new IllegalStateException(
                                                                s"Expected messages of type ${tag.show} but got ${Tag.fromRaw(tag2).show}"
                                                            )
                                                        )
                                                    else
                                                        result = Absent
                                                        Emit.valueWith(messages)(loop())
                                                    end if
                                                case Absent =>
                                                    Abort.panic(new IllegalStateException(s"No results"))
                                            end match
                                        end if
                                }
                            }
                        end loop
                        loop()
                    }
                }
            }
        }
    end stream

    given isolate: Isolate.Contextual[Topic, Any] = Isolate.Contextual[Topic, Any]

end Topic

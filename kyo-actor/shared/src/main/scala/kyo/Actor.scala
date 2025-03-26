package kyo

import java.io.IOException
import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import scala.annotation.*

/** An actor that processes messages asynchronously through a mailbox until completion or failure.
  *
  * WARNING: Actor is a low-level primitive with complex semantics. For most concurrent programming needs, consider using simpler primitives
  * like kyo.Async or kyo.Stream instead.
  *
  * Actors provide a message-based concurrency model where each instance:
  *   - Maintains a private mailbox for receiving messages
  *   - Processes messages sequentially in FIFO order
  *   - Can communicate with other actors by sending messages
  *   - Can maintain and modify internal state between messages
  *   - Can spawn new child actors
  *   - Completes with either a success value of type B or failure of type E
  *
  * Messages can be sent to an actor using its `subject` interface, which provides both fire-and-forget `send` operations and
  * request-response style `ask` operations. The actor processes these messages one at a time until it either successfully completes,
  * encounters an error, or is explicitly closed.
  *
  * Actors can form parent-child hierarchies where:
  *   - Parent actors can spawn and supervise child actors
  *   - Child actors inherit the resource scope of their parent
  *   - When a parent actor completes or fails, its children are automatically shut down
  *
  * Graceful shutdown can be initiated by:
  *   - Calling `close()` on the actor, which prevents new messages from being accepted
  *   - Allowing the actor to process any remaining messages in its mailbox
  *   - Awaiting the actor's completion via its `fiber` or `result`
  *
  * If an error of type E occurs during message processing and is not handled within the actor's implementation (via Abort), the actor will
  * fail and complete with that error. The actor's lifecycle can be monitored through its underlying `fiber`, or by awaiting its final
  * `result`.
  *
  * @tparam E
  *   The type of errors that can terminate the actor if not handled internally
  * @tparam A
  *   The type of messages this actor can receive
  * @tparam B
  *   The type of result this actor produces upon completion
  */
sealed abstract class Actor[+E, A, B](_subject: Subject[A], _fiber: Fiber[Closed | E, B]):

    /** Returns the message subject interface for sending messages to this actor.
      *
      * Messages sent through this subject will be queued in the actor's mailbox and processed sequentially in FIFO order.
      *
      * @return
      *   A Subject[A] that can be used to send messages to this actor
      */
    def subject: Subject[A] = _subject

    export _subject.*

    /** Returns the fiber executing this actor's message processing.
      *
      * The fiber completes when the actor finishes processing all messages and produces its final result. It will fail with Closed if the
      * actor's mailbox is closed, or with error E if an unhandled error occurs during message processing.
      *
      * @return
      *   A Fiber containing the actor's execution
      */
    def fiber: Fiber[Closed | E, B] = _fiber

    /** Retrieves the final result of this actor.
      *
      * Waits for the actor to complete processing all messages and return its final value. Will fail with error E if an unhandled error
      * occurs during message processing, or with Closed if the actor is closed prematurely.
      *
      * @return
      *   The actor's final result of type B
      */
    def await(using Frame): B < (Async & Abort[Closed | E]) = fiber.get

    /** Closes the actor's mailbox, preventing it from receiving any new messages.
      *
      * When called, this method:
      *   - Prevents new messages from being sent to the actor
      *   - Returns any messages that were queued but not yet processed
      *   - Does not interrupt the processing of the current message if one is being handled
      *
      * @return
      *   A Maybe containing a sequence of any messages that were in the mailbox when it was closed
      */
    def close(using Frame): Maybe[Seq[A]] < IO

end Actor

object Actor:

    /** Default mailbox capacity for actors.
      *
      * This value can be configured through the system property "kyo.actor.capacity.default". If not specified, it defaults to 100
      * messages.
      */
    val defaultCapacity =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        IO.Unsafe.evalOrThrow(System.property[Int]("kyo.actor.capacity.default", 128))
    end defaultCapacity

    /** The execution context for actor behaviors, providing the essential capabilities for actor-based concurrency.
      *
      * Actor.Context is a combination of five foundational effect types that together create the environment in which actor behaviors
      * operate:
      *
      *   - [[Poll]]: Allows receiving and processing messages from the actor's mailbox. Used by `receiveAll`, `receiveMax`, and
      *     `receiveLoop` methods.
      *   - [[Env[Subject[A]]]]: Provides access to the actor's own subject, enabling self-reference and communication with itself. Used by
      *     `self` and `selfWith` methods.
      *   - [[Abort[Closed]]]: Supports handling of mailbox closure situations with the specialized Closed error type. Triggered when
      *     `close` is called on the actor.
      *   - [[Resource]]: Enables proper management and cleanup of acquired resources. Used within the actor implementation for mailbox
      *     cleanup and for maintaining actor hierarchies where child actors are automatically cleaned up when their parent completes or
      *     fails.
      *   - [[Async]]: Provides asynchronous execution capabilities. Used to run the actor's processing loop concurrently.
      *
      * @tparam A
      *   The type of messages this actor context can process
      */
    opaque type Context[A] <: Poll[A] & Env[Subject[A]] & Abort[Closed] & Resource & Async =
        Poll[A] & Env[Subject[A]] & Abort[Closed] & Resource & Async

    /** Retrieves the current actor's Subject from the environment.
      *
      * This method is designed be called within an Actor.run body, where the type parameter A matches the Poll message type of that Actor.
      *
      * @tparam A
      *   The type of messages the Subject can receive - should match the Actor's Poll type
      * @return
      *   A Subject[A] representing the current actor's message interface
      */
    def self[A: Tag](using Frame): Subject[A] < Context[A] =
        Env.get

    /** Retrieves the current actor's Subject from the environment and applies a function to it.
      *
      * This method is designed to be called within an Actor.run body, providing a convenient way to access the actor's Subject and perform
      * operations on it in a single call.
      *
      * @param f
      *   A function that takes the actor's Subject and returns a value of type B with effects S
      * @tparam A
      *   The type of messages the Subject can receive - should match the Actor's Poll type
      * @return
      *   The result of applying function f to the actor's Subject
      */
    def selfWith[A: Tag](using Frame)[B, S](f: Subject[A] => B < S): B < (Context[A] & S) =
        Env.use(f)

    /** Sends a message to the actor designated as the current subject in the environment.
      *
      * This method is designed be called within an Actor.run body and to re-enqueue messages for later processing by the actor itself.
      *
      * @param msg
      *   The message to re-enqueue
      * @tparam A
      *   The type of the message
      * @return
      *   An effect representing the message enqueuing
      */
    def reenqueue[A: Tag](msg: A)(using Frame): Unit < Context[A] =
        Env.use[Subject[A]](_.send(msg))

    /** Receives and processes a single message from the actor's mailbox.
      *
      * This method polls for the next available message and applies the provided processing function. Message processing is done
      * sequentially, ensuring only one message is handled at a time.
      *
      * @param f
      *   The function to process each received message
      * @tparam A
      *   The type of messages being received
      */
    def receiveAll[A](using Tag[A])[B, S](f: A => B < S)(using Frame): Unit < (Context[A] & S) =
        Poll.values[A](f)

    /** Receives and processes up to n messages from the actor's mailbox.
      *
      * This method polls for messages and applies the provided processing function to each one, up to the specified limit. Message
      * processing is done sequentially.
      *
      * @param max
      *   The maximum number of messages to process
      * @param f
      *   The function to process each received message
      * @tparam A
      *   The type of messages being received
      */
    def receiveMax[A: Tag](max: Int)[S](f: A => Any < S)(using Frame): Unit < (Context[A] & S) =
        Poll.values[A](max)(f)

    /** Receives and processes messages from the actor's mailbox in a loop until a termination condition is met.
      *
      * This method continuously polls for messages and applies the provided processing function to each one. The function returns a
      * Loop.Outcome that determines whether to continue processing more messages or stop.
      *
      * To control the loop:
      *   - Return `Loop.continue` to process the next message
      *   - Return `Loop.done` to stop processing and complete the receive loop
      *
      * Use this when you need fine-grained control over message processing termination conditions beyond what receiveAll or receiveMax
      * provide.
      *
      * @param f
      *   A function that processes each received message and returns a Loop.Outcome indicating whether to continue or stop
      * @tparam A
      *   The type of messages being received
      * @return
      *   An effect representing the message processing loop
      */
    def receiveLoop[A](using Tag[A])[S](f: A => Loop.Outcome[Unit, Unit] < S)(using Frame): Unit < (Context[A] & S) =
        Loop(()) { _ =>
            Poll.one[A].map {
                case Absent     => Loop.done
                case Present(v) => f(v)
            }
        }

    /** Creates and starts a new actor with default capacity from a message processing behavior.
      *
      * This is a convenience method that calls `run(defaultCapacity)(behavior)`. It creates an actor with the default mailbox capacity as
      * specified by `defaultCapacity`.
      *
      * @param behavior
      *   The behavior defining how messages are processed
      * @tparam E
      *   The type of errors that can occur
      * @tparam A
      *   The type of messages accepted
      * @tparam B
      *   The type of result produced
      * @tparam S
      *   Additional context effects required by the behavior
      * @return
      *   A new Actor instance in an async effect
      */
    def run[E, A: Tag, B: Flat, S](
        using Isolate.Contextual[S, IO]
    )(behavior: B < (Context[A] & Abort[E] & S))(
        using
        Tag[Poll[A]],
        Tag[Emit[A]],
        Frame
    ): Actor[E, A, B] < (Resource & Async & S) =
        run(defaultCapacity)(behavior)

    /** Creates and starts new actor from a message processing behavior.
      *
      * The behavior defines how messages are processed and can utilize several effects:
      *   - Poll[A]: For receiving messages from the actor's mailbox
      *   - Env[Subject[A]]: For accessing self-reference to send messages to self via Actor.reenqueue
      *   - Abort[E]: For handling potential errors during message processing
      *   - S: For any additional context effects needed by the behavior
      *
      * Message processing continues until either:
      *   - The behavior completes normally, producing a final result
      *   - The behavior explicitly stops polling for messages
      *   - An unhandled error of type E occurs during message processing
      *   - The actor's mailbox is closed
      *
      * Messages are processed sequentially in FIFO order, with the behavior having full control over when to receive the next message
      * through polling.
      *
      * @param b
      *   The behavior defining how messages are processed
      * @tparam E
      *   The type of errors that can occur
      * @tparam A
      *   The type of messages accepted
      * @tparam B
      *   The type of result produced
      * @tparam Ctx
      *   Additional context effects required by the behavior
      * @return
      *   A new Actor instance in an async effect
      */
    def run[E, A: Tag, B: Flat, S](
        using Isolate.Contextual[S, IO]
    )(capacity: Int)(behavior: B < (Context[A] & Abort[E] & S))(
        using
        Tag[Poll[A]],
        Tag[Emit[A]],
        Frame
    ): Actor[E, A, B] < (Resource & Async & S) =
        for
            mailbox <-
                // Create a bounded channel to serve as the actor's mailbox
                Channel.init[A](capacity, Access.MultiProducerSingleConsumer)
            _subject =
                // Create the actor's message interface (Subject)
                // Messages sent through this subject are queued in the mailbox
                Subject.init(mailbox)
            _consumer <-
                Loop(behavior) { b =>
                    Poll.runFirst(b).map {
                        case Left(r) =>
                            Loop.done(r)
                        case Right(cont) =>
                            mailbox.take.map(v => Loop.continue(cont(Maybe(v))))
                    }
                }.pipe(
                    IO.ensure(mailbox.close), // Ensure mailbox cleanup by closing it when the actor completes or fails
                    Env.run(_subject),        // Provide the actor's Subject to the environment so it can be accessed via Actor.self
                    Resource.run,             // Close used resources
                    Async.run                 // Start the actor's processing loop in an async context
                )
            _ <- Resource.ensure(mailbox.close) // Registers a finalizer in the outer scope to provide the actor hierarchy behavior
        yield new Actor[E, A, B](_subject, _consumer):
            def close(using Frame) = mailbox.close
end Actor

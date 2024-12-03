package kyo

import java.io.IOException
import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Boundary
import kyo.kernel.ContextEffect
import scala.annotation.targetName

/** An actor that processes messages asynchronously through a mailbox until completion or failure.
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
abstract class Actor[+E, A, B]:

    /** Returns the message subject interface for sending messages to this actor.
      *
      * Messages sent through this subject will be queued in the actor's mailbox and processed sequentially in FIFO order.
      *
      * @return
      *   A Subject[A] that can be used to send messages to this actor
      */
    def subject: Subject[A]

    /** Returns the fiber executing this actor's message processing.
      *
      * The fiber completes when the actor finishes processing all messages and produces its final result. It will fail with Closed if the
      * actor's mailbox is closed, or with error E if an unhandled error occurs during message processing.
      *
      * @return
      *   A Fiber containing the actor's execution
      */
    def fiber: Fiber[Closed | E, B]

    /** Retrieves the final result of this actor.
      *
      * Waits for the actor to complete processing all messages and return its final value. Will fail with error E if an unhandled error
      * occurs during message processing, or with Closed if the actor is closed prematurely.
      *
      * @return
      *   The actor's final result of type B
      */
    def result(using Frame): B < (Async & Abort[Closed | E]) = fiber.get
end Actor

export Actor.Subject

object Actor:

    /** Interface for sending messages to an actor.
      *
      * @tparam A
      *   The type of messages that can be sent
      */
    trait Subject[A]:

        /** Sends a message to the actor.
          *
          * The message will be queued in the actor's mailbox and processed asynchronously in FIFO order along with other pending messages.
          *
          * @param message
          *   The message to send
          * @return
          *   An async effect representing the enqueuing of the message
          */
        def send(message: A): Unit < (Async & Abort[Closed])

        /** Sends a message to an actor and waits for a response.
          *
          * This method implements the request-response pattern by automatically creating a temporary reply channel. It's useful when you
          * need to get a response back from an actor after sending it a message.
          *
          * For a message type like:
          *
          * case class GetUserInfo(userId: Int, replyTo: Subject[UserData])
          *
          * You can use:
          *
          * subject.ask(GetUserInfo(123, _))
          *
          * The returned type (UserData) is determined by the reply channel type in the message.
          *
          * @param f
          *   A function that takes a reply Subject[B] and returns the message to send
          * @tparam B
          *   The type of response expected
          * @return
          *   The response of type B from the actor
          * @throws Closed
          *   if the actor's mailbox is closed
          */
        def ask[B](f: Subject[B] => A)(using Frame): B < (Async & Abort[Closed]) =
            for
                promise <- Promise.init[Nothing, B]
                replyTo: Subject[B] = r => promise.completeDiscard(Result.success(r))
                _      <- send(f(replyTo))
                result <- promise.get
            yield result
    end Subject

    object Subject:
        private val _noop: Subject[Any] = _ => {}

        /** Creates a no-operation Subject that discards all messages.
          *
          * This Subject implementation ignores any messages sent to it and performs no action. It can be useful for testing or when you
          * need a placeholder Subject implementation.
          *
          * @tparam A
          *   The type of messages this Subject can receive (and discard)
          * @return
          *   A Subject[A] that discards all messages
          */
        def noop[A]: Subject[A] = _noop.asInstanceOf[Subject[A]]
    end Subject

    /** Retrieves the current actor's Subject from the environment.
      *
      * This method is designed be called within an Actor.run body, where the type parameter A matches the Poll message type of that Actor.
      *
      * @tparam A
      *   The type of messages the Subject can receive - should match the Actor's Poll type
      * @return
      *   A Subject[A] representing the current actor's message interface
      */
    def self[A: Tag](using Frame): Subject[A] < Env[Subject[A]] =
        Env.get

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
    def resend[A: Tag](msg: A)(using Frame): Unit < (Abort[Closed] & Env[Subject[A]] & Async) =
        Env.use[Subject[A]](_.send(msg))

    /** Creates and starts new actor from a message processing behavior.
      *
      * The behavior defines how messages are processed and can utilize several effects:
      *   - Poll[A]: For receiving messages from the actor's mailbox
      *   - Env[Subject[A]]: For accessing self-reference to send messages to self via Actor.reenqueue
      *   - Abort[E]: For handling potential errors during message processing
      *   - Ctx: For any additional context effects needed by the behavior
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
    inline def run[E, A, B: Flat, Ctx](
        behavior: B < (Poll[A] & Env[Subject[A]] & Abort[E | Closed] & Ctx)
    )(
        using
        pollTag: Tag[Poll[A]],
        emitTag: Tag[Emit[A]]
    ): Actor[E, A, B] < (Async & Ctx) =
        _run(behavior)

    // Indirection to avoid inference issues with Boundary
    private def _run[E, A: Tag, B: Flat, Ctx](
        behavior: B < (Poll[A] & Env[Subject[A]] & Abort[E | Closed] & Ctx)
    )(using Boundary[Ctx, IO & Abort[E | Closed]], Frame): Actor[E, A, B] < (IO & Ctx) =
        for
            mailbox <-
                // Create an unbounded channel to serve as the actor's mailbox
                // Unbounded capacity in this initial prototype
                Channel.init[A](Int.MaxValue, Access.MultiProducerSingleConsumer)
            _subject: Subject[A] =
                // Create the actor's message interface (Subject)
                // Messages sent through this subject are queued in the mailbox
                msg => mailbox.put(msg)
            _consumer <-
                // Start the actor's processing loop in an async context
                Async._run {
                    // Provide the actor's Subject to the environment so it can be accessed via Actor.self
                    Env.run(_subject) {
                        // Ensure mailbox cleanup by closing it when the actor completes or fails
                        IO.ensure(mailbox.close.unit) {
                            Loop(behavior) { b =>
                                Poll.runFirst(b).map {
                                    case (Ack.Stop, cont) =>
                                        // Actor has signaled it wants to stop receiving messages
                                        // Discard any remaining polls
                                        Poll.run(Chunk.empty)(cont(Maybe.empty)).map(Loop.done)
                                    case (_, cont) =>
                                        // Actor is still actively processing messages
                                        // Take the next message from mailbox and continue the loop
                                        // with the continuation function
                                        mailbox.take.map(v => Loop.continue(cont(Maybe(v))))
                                }
                            }
                        }
                    }
                }
        yield new Actor[E, A, B]:
            def subject = _subject
            def fiber   = _consumer
end Actor

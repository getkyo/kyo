package kyo

import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.Actor.Subject
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import scala.annotation.*
import scala.annotation.nowarn

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
sealed abstract class Actor[+E, A, B](
    _subject: Subject[A],
    _fiber: Fiber[B, Abort[Closed | E]],
    _pending: Actor.PendingReplies
):

    /** Returns the message subject interface for sending messages to this actor.
      *
      * Messages sent through this subject will be queued in the actor's mailbox and processed sequentially in FIFO order.
      *
      * @return
      *   A Subject[A] that can be used to send messages to this actor
      */
    def subject: Subject[A] = _subject

    export _subject.send
    export _subject.trySend

    /** Sends a request and awaits the reply, completing even if the actor terminates first.
      *
      * Unlike the bare `Subject.ask`, this surfaces the actor's failure `E` or a panic when the actor ends before replying, instead of
      * collapsing to `Closed`. The caller is never stranded.
      */
    def ask[C](f: Subject[C] => A)(using frame: Frame): C < (Async & Abort[Closed | E]) =
        Promise.init[C, Any].map { reply =>
            _pending.awaitReply[C, E](reply, _subject.send(f(Subject.init(reply)))) {
                // The actor ended before replying: surface its terminal outcome via non-blocking polls,
                // which register no interrupts and so leave the actor fiber untouched. An interruption
                // (scope close / mailbox close) reads as Closed from the caller's perspective; a genuine
                // handler panic stays a panic.
                reply.poll.map {
                    case Present(Result.Success(value)) => value: C < Abort[Closed | E]
                    case Present(Result.Failure(e))     => Abort.fail(e)
                    case Present(Result.Panic(ex))      => Abort.panic(ex)
                    case Absent =>
                        _fiber.poll.map {
                            case Present(Result.Success(_)) | Absent   => Abort.fail(Closed("Actor", frame))
                            case Present(Result.Failure(e))            => Abort.fail(e)
                            case Present(Result.Panic(_: Interrupted)) => Abort.fail(Closed("Actor", frame))
                            case Present(Result.Panic(ex))             => Abort.panic(ex)
                        }
                }
            }
        }

    /** The number of in-flight `ask` replies currently registered with this actor.
      *
      * Exposed for tests: it must be bounded by concurrent in-flight asks and must drop back to zero once they resolve, never growing with
      * the actor's lifetime.
      */
    private[kyo] def pendingReplies: Int = _pending.size

    /** Returns the fiber executing this actor's message processing.
      *
      * The fiber completes when the actor finishes processing all messages and produces its final result. It will fail with Closed if the
      * actor's mailbox is closed, or with error E if an unhandled error occurs during message processing.
      *
      * @return
      *   A Fiber containing the actor's execution
      */
    def fiber: Fiber[B, Abort[Closed | E]] = _fiber

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
      *   A Maybe containing a sequence of any messages that were in the mailbox if the close is successful
      */
    def close(using Frame): Maybe[Seq[A]] < Sync

end Actor

object Actor:

    /** Per-actor registry of in-flight `ask` reply waiters, used to keep `ask` strand-safe without leaking.
      *
      * Each `ask` registers a fresh `signal` promise here before awaiting and removes it once the await resolves (reply, actor termination,
      * or caller interrupt), so the set is bounded by concurrently in-flight asks and never grows with the actor's lifetime. A single
      * termination sweep (installed once on the consumer fiber, not per ask) completes every registered signal so no caller is stranded when
      * the actor ends without replying.
      *
      * The `terminated` flag is set before the sweep iterates. An ask that registers its signal then observes the flag set will complete its
      * own signal, closing the add-after-termination race: either the sweep sees the freshly added signal, or the ask self-completes.
      */
    final private[kyo] class PendingReplies:
        private val waiters    = new CopyOnWriteArraySet[Promise[Unit, Any]]
        private val terminated = new AtomicBoolean(false)

        def size: Int = waiters.size

        /** Completes every registered waiter. Called once when the actor's consumer fiber ends. */
        def terminate(using AllowUnsafe): Unit =
            terminated.set(true)
            // Unsafe: runs inside the fiber's Sync-only onComplete callback, which cannot suspend; each
            // completeUnitDiscard is idempotent and the COWArraySet iteration is snapshot-safe.
            waiters.forEach(signal => signal.unsafe.completeUnitDiscard())
        end terminate

        /** Awaits a reply, completing even if the actor terminates first, with no per-ask accumulation.
          *
          * Registers a fresh signal, runs `send`, then races `reply.get` against the signal. `onTerminated` interprets the actor's terminal
          * outcome (the bare subject collapses to `Closed`; `Actor.ask` refines to `E`/panic). The waiter is removed when the race resolves.
          *
          * @param reply
          *   The reply promise the recipient completes
          * @param send
          *   The send action that enqueues the request; run after the signal is registered
          * @param onTerminated
          *   The continuation evaluated when the actor terminates before the reply arrives
          */
        def awaitReply[C, E](reply: Promise[C, Any], send: => Unit < (Async & Abort[Closed]))(
            onTerminated: => C < (Async & Abort[Closed | E])
        )(using frame: Frame): C < (Async & Abort[Closed | E]) =
            Promise.init[Unit, Any].map { signal =>
                val register: Unit < Sync =
                    Sync.defer(discard(waiters.add(signal))).andThen {
                        // Close the add-after-termination race: if the sweep already set the flag, complete
                        // our own signal so the terminated branch fires instead of awaiting a reply that
                        // never comes. Either the sweep sees this freshly added signal, or we self-complete.
                        if terminated.get() then signal.completeUnitDiscard else Kyo.unit
                    }
                val raced: C < (Async & Abort[Closed | E]) =
                    Sync.ensure(Sync.defer(discard(waiters.remove(signal)))) {
                        // raceFirst (not race): complete on the first branch to finish, success or failure.
                        // A plain race only completes on the first success, so a stranded caller whose actor
                        // terminated without replying (a failure on the terminated branch) would hang forever.
                        Async.raceFirst[Closed | E, C, Any](
                            reply.get,
                            signal.get.andThen(onTerminated)
                        )
                    }
                register.andThen(send).andThen(raced)
            }
    end PendingReplies

    /** Default mailbox capacity for actors.
      *
      * This value can be configured through the system property "kyo.actor.capacity.default". If not specified, it defaults to 100
      * messages.
      */
    val defaultCapacity =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        Sync.Unsafe.evalOrThrow(System.property[Int]("kyo.actor.capacity.default", 128))
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
      *   - [[Scope]]: Enables proper management and cleanup of acquired resources. Used within the actor implementation for mailbox cleanup
      *     and for maintaining actor hierarchies where child actors are automatically cleaned up when their parent completes or fails.
      *   - [[Async]]: Provides asynchronous execution capabilities. Used to run the actor's processing loop concurrently.
      *
      * @tparam A
      *   The type of messages this actor context can process
      */
    opaque type Context[A] <: Poll[A] & Env[Subject[A]] & Abort[Closed] & Scope & Async =
        Poll[A] & Env[Subject[A]] & Abort[Closed] & Scope & Async

    /** Retrieves the current actor's Subject from the environment.
      *
      * This method is designed be called within an Actor.run body, where the type parameter A matches the Poll message type of that Actor.
      *
      * @tparam A
      *   The type of messages the Subject can receive - should match the Actor's Poll type
      * @return
      *   A Subject[A] representing the current actor's message interface
      */
    def self[A](using Frame, Tag[Subject[A]]): Subject[A] < Context[A] =
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
    def selfWith[A](using Frame)[B, S](f: Subject[A] => B < S)(using Tag[Subject[A]]): B < (Context[A] & S) =
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
    def reenqueue[A](msg: A)(using Frame, Tag[Subject[A]]): Unit < Context[A] =
        Env.use[Subject[A]](_.send(msg))

    /** Receives and processes a single message from the actor's mailbox.
      *
      * This method polls for the next available message and applies the provided processing function. Message processing is done
      * sequentially, ensuring only one message is handled at a time. The result of the processing function is discarded.
      *
      * @param f
      *   The function to process each received message
      * @tparam A
      *   The type of messages being received
      */
    def receiveAll[A](using Frame)[S](f: A => Any < S)(using Tag[Poll[A]]): Unit < (Context[A] & S) =
        Poll.values[A](f)

    /** Receives and processes up to n messages from the actor's mailbox.
      *
      * This method polls for messages and applies the provided processing function to each one, up to the specified limit. Message
      * processing is done sequentially. The result of the processing function is discarded.
      *
      * @param max
      *   The maximum number of messages to process
      * @param f
      *   The function to process each received message
      * @tparam A
      *   The type of messages being received
      */
    def receiveMax[A](max: Int)[S](f: A => Any < S)(using Frame, Tag[Poll[A]]): Unit < (Context[A] & S) =
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
    def receiveLoop[A](using Frame)[S](f: A => Loop.Outcome[Unit, Unit] < S)(using Tag[Poll[A]]): Unit < (Context[A] & S) =
        Loop.foreach {
            Poll.one[A].map {
                case Absent     => Loop.done
                case Present(v) => f(v)
            }
        }

    /** Receives and processes messages from the actor's mailbox in a loop with a single state value.
      *
      * This method continuously polls for messages and applies the provided processing function to each one, maintaining a state value
      * between iterations. The function returns a Loop.Outcome that determines whether to continue processing more messages with an updated
      * state or stop with a final result.
      *
      * To control the loop:
      *   - Return `Loop.continue(newState)` to continue processing with an updated state
      *   - Return `Loop.done(finalState)` to stop processing and return the final state
      *
      * When the loop completes, the final state value is returned as the result.
      *
      * @param initialState
      *   The initial state value to use for the first message
      * @param f
      *   A function that processes each received message with the current state and returns a Loop.Outcome
      * @tparam A
      *   The type of messages being received
      * @return
      *   The final state value after the loop completes
      */
    def receiveLoop[A](
        using Frame
    )[State, S](state: State)(
        f: (A, State) => Loop.Outcome[State, State] < S
    )(using Tag[Poll[A]]): State < (Context[A] & S) =
        Loop(state) { state =>
            Poll.one[A].map {
                case Absent     => Loop.done(state)
                case Present(v) => f(v, state)
            }
        }

    /** Receives and processes messages from the actor's mailbox in a loop with two state values.
      *
      * This method continuously polls for messages and applies the provided processing function to each one, maintaining two state values
      * between iterations. The function returns a Loop.Outcome that determines whether to continue processing more messages with updated
      * states or stop with a final result.
      *
      * To control the loop:
      *   - Return `Loop.continue(newState1, newState2)` to continue processing with updated states
      *   - Return `Loop.done((finalState1, finalState2))` to stop processing and return the final states
      *
      * When the loop completes, the final state values are returned as a tuple.
      *
      * @param state1
      *   The first initial state value
      * @param state2
      *   The second initial state value
      * @param f
      *   A function that processes each received message with the current states and returns a Loop.Outcome
      * @tparam A
      *   The type of messages being received
      * @return
      *   A tuple containing the final state values after the loop completes
      */
    def receiveLoop[A](
        using Frame
    )[State1, State2, S](state1: State1, state2: State2)(
        f: (A, State1, State2) => Loop.Outcome2[State1, State2, (State1, State2)] < S
    )(using Tag[Poll[A]]): (State1, State2) < (Context[A] & S) =
        Loop(state1, state2) { (s1, s2) =>
            Poll.one[A].map {
                case Absent     => Loop.done((s1, s2))
                case Present(v) => f(v, s1, s2)
            }
        }

    /** Receives and processes messages from the actor's mailbox in a loop with three state values.
      *
      * This method continuously polls for messages and applies the provided processing function to each one, maintaining three state values
      * between iterations. The function returns a Loop.Outcome that determines whether to continue processing more messages with updated
      * states or stop with a final result.
      *
      * To control the loop:
      *   - Return `Loop.continue(newState1, newState2, newState3)` to continue processing with updated states
      *   - Return `Loop.done((finalState1, finalState2, finalState3))` to stop processing and return the final states
      *
      * When the loop completes, the final state values are returned as a tuple.
      *
      * @param state1
      *   The first initial state value
      * @param state2
      *   The second initial state value
      * @param state3
      *   The third initial state value
      * @param f
      *   A function that processes each received message with the current states and returns a Loop.Outcome
      * @tparam A
      *   The type of messages being received
      * @return
      *   A tuple containing the final state values after the loop completes
      */
    def receiveLoop[A](
        using Frame
    )[State1, State2, State3, S](state1: State1, state2: State2, state3: State3)(
        f: (A, State1, State2, State3) => Loop.Outcome3[State1, State2, State3, (State1, State2, State3)] < S
    )(using Tag[Poll[A]]): (State1, State2, State3) < (Context[A] & S) =
        Loop(state1, state2, state3) { (s1, s2, s3) =>
            Poll.one[A].map {
                case Absent     => Loop.done((s1, s2, s3))
                case Present(v) => f(v, s1, s2, s3)
            }
        }

    /** Receives and processes messages from the actor's mailbox in a loop with four state values.
      *
      * This method continuously polls for messages and applies the provided processing function to each one, maintaining four state values
      * between iterations. The function returns a Loop.Outcome that determines whether to continue processing more messages with updated
      * states or stop with a final result.
      *
      * To control the loop:
      *   - Return `Loop.continue(newState1, newState2, newState3, newState4)` to continue processing with updated states
      *   - Return `Loop.done((finalState1, finalState2, finalState3, finalState4))` to stop processing and return the final states
      *
      * When the loop completes, the final state values are returned as a tuple.
      *
      * @param state1
      *   The first initial state value
      * @param state2
      *   The second initial state value
      * @param state3
      *   The third initial state value
      * @param state4
      *   The fourth initial state value
      * @param f
      *   A function that processes each received message with the current states and returns a Loop.Outcome
      * @tparam A
      *   The type of messages being received
      * @return
      *   A tuple containing the final state values after the loop completes
      */
    def receiveLoop[A](
        using Frame
    )[State1, State2, State3, State4, S](state1: State1, state2: State2, state3: State3, state4: State4)(
        f: (A, State1, State2, State3, State4) => Loop.Outcome4[State1, State2, State3, State4, (State1, State2, State3, State4)] < S
    )(using Tag[Poll[A]]): (State1, State2, State3, State4) < (Context[A] & S) =
        Loop(state1, state2, state3, state4) { (s1, s2, s3, s4) =>
            Poll.one[A].map {
                case Absent     => Loop.done((s1, s2, s3, s4))
                case Present(v) => f(v, s1, s2, s3, s4)
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
    def run[E, A: Tag, B, S](
        using Isolate[S, Sync, Any]
    )(behavior: B < (Context[A] & Abort[E] & S))(
        using
        Tag[Poll[A]],
        Tag[Emit[A]],
        Tag[Subject[A]],
        Frame
    ): Actor[E, A, B] < (Scope & Async & S) =
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
    def run[E, A: Tag, B, S](
        using Isolate[S, Sync, Any]
    )(capacity: Int)(behavior: B < (Context[A] & Abort[E] & S))(
        using
        Tag[Poll[A]],
        Tag[Emit[A]],
        Tag[Subject[A]],
        Frame
    ): Actor[E, A, B] < (Scope & Async & S) =
        for
            mailbox <-
                // Create a bounded channel to serve as the actor's mailbox
                Channel.init[A](capacity, Access.MultiProducerSingleConsumer)
            pending =
                // Per-actor registry of in-flight ask waiters; entries are removed as each ask resolves so
                // the set is bounded by concurrent in-flight asks, never by the actor's lifetime.
                new PendingReplies
            _subject =
                // Create the actor's message interface (Subject)
                // Messages sent through this subject are queued in the mailbox; awaitReply also
                // completes when the consumer fiber ends so an ask caller is never stranded
                new Subject[A]:
                    def send(message: A)(using Frame): Unit < (Async & Abort[Closed])      = mailbox.put(message)
                    def trySend(message: A)(using Frame): Boolean < (Sync & Abort[Closed]) = mailbox.offer(message)
                    override private[kyo] def awaitReply[C](reply: Promise[C, Any])(using frame: Frame): C < (Async & Abort[Closed]) =
                        // Subject.ask has already sent, so no send action here. The bare subject contract only
                        // distinguishes "replied" from "recipient closed": any terminal outcome reads as Closed.
                        pending.awaitReply[C, Closed](reply, Kyo.unit) {
                            reply.poll.map {
                                case Present(Result.Success(value)) => value: C < Abort[Closed]
                                case Present(Result.Failure(_))     => Abort.fail(Closed("Actor", frame))
                                case Present(Result.Panic(ex))      => Abort.panic(ex)
                                case Absent                         => Abort.fail(Closed("Actor", frame))
                            }
                        }
            _consumer <-
                Loop(behavior) { b =>
                    Poll.runFirst(b).map {
                        case Left(r) =>
                            Loop.done(r)
                        case Right(cont) =>
                            mailbox.take.map(v => Loop.continue(cont(Maybe(v))))
                    }
                }.handle(
                    Sync.ensure(mailbox.close), // Ensure mailbox cleanup by closing it when the actor completes or fails
                    Env.run(_subject),          // Provide the actor's Subject to the environment so it can be accessed via Actor.self
                    Scope.run,                  // Close used resources
                    Fiber.init                  // Start the actor's processing loop in an async context
                )
            _ <-
                // Single termination hook (installed once, not per ask): sweep the pending registry so every
                // outstanding ask completes when the actor's consumer fiber ends.
                _consumer.onComplete(_ => Sync.Unsafe.defer(pending.terminate))
        yield new Actor[E, A, B](_subject, _consumer, pending):
            def close(using Frame) = mailbox.close

    /** Interface for sending messages to a recipient.
      *
      * A Subject represents any entity that can receive messages. While commonly used with actors, it can be used with any
      * message-processing system.
      *
      * @tparam A
      *   The type of messages that can be sent
      */
    sealed abstract class Subject[A]:

        /** Sends a message to the recipient.
          *
          * This method guarantees delivery of the message if the recipient is available, potentially by asynchronously blocking until the
          * message can be processed. It represents a "reliable send" operation that will wait as needed to ensure delivery.
          *
          * Depending on the implementation, the message might be processed synchronously or asynchronously. In actor systems, messages are
          * typically queued in a mailbox and processed in FIFO order.
          *
          * @param message
          *   The message to send
          * @return
          *   An async effect representing the sending of the message
          */
        def send(message: A)(using Frame): Unit < (Async & Abort[Closed])

        /** Attempts to send a message to the recipient without blocking.
          *
          * This is a non-blocking alternative to `send`. It immediately returns a boolean indicating whether the message was successfully
          * delivered. If the recipient cannot immediately accept the message (e.g., due to capacity constraints), this method returns false
          * rather than waiting.
          *
          * This method is useful when you want to avoid blocking the current fiber when the recipient cannot immediately accept the message.
          *
          * @param message
          *   The message to send
          * @return
          *   true if the message was successfully sent, false if the recipient couldn't accept it immediately
          */
        def trySend(message: A)(using Frame): Boolean < (Sync & Abort[Closed])

        /** Awaits the reply for an `ask`. Default: wait for the reply promise.
          *
          * The actor mailbox subject overrides this to also complete when the actor terminates without replying, so a caller is never
          * stranded. A bare sink (channel, queue, hub, promise, custom) has no recipient-terminated signal, so the default simply waits.
          */
        private[kyo] def awaitReply[B](reply: Promise[B, Any])(using Frame): B < (Async & Abort[Closed]) =
            reply.get

        /** Sends a message and waits for a response.
          *
          * This method implements the request-response pattern by automatically creating a temporary reply channel. It's useful when you
          * need to get a response back after sending a message.
          *
          * For example, with a message type like:
          *
          * case class GetData(id: Int, replyTo: Subject[ResponseData])
          *
          * You can use:
          *
          * subject.ask(GetData(123, _))
          *
          * The returned type (ResponseData) is determined by the reply channel type in the message.
          *
          * @param f
          *   A function that takes a reply Subject[B] and returns the message to send
          * @tparam B
          *   The type of response expected
          * @return
          *   The response of type B
          */
        def ask[B](f: Subject[B] => A)(using Frame): B < (Async & Abort[Closed]) =
            Promise.init[B, Any].map(promise => send(f(Subject.init(promise))).andThen(awaitReply(promise)))

    end Subject

    object Subject:

        private val _noop =
            init(
                send = _ => (),
                trySend = _ => false
            )

        /** Creates a no-operation Subject that discards all messages.
          *
          * This Subject implementation ignores any messages sent to it and performs no action. It can be useful for testing, as a
          * placeholder, or when you need to explicitly discard messages.
          *
          * @tparam A
          *   The type of messages this Subject can receive (and discard)
          * @return
          *   A Subject[A] that discards all messages
          */
        def noop[A]: Subject[A] = _noop.asInstanceOf[Subject[A]]

        /** Creates a Subject that completes a Promise with each received message.
          *
          * This method creates a Subject that will attempt to complete the provided Promise with any message it receives. The Promise is
          * completed with a successful Result containing the message.
          *
          * Important: Only the first message sent to this Subject will successfully complete the Promise. Any subsequent messages will
          * result in an `Abort[Closed]` effect, indicating that the Subject is closed (since the Promise can only be completed once).
          *
          * @param promise
          *   The Promise to complete when messages are received
          * @tparam E
          *   The error type of the Promise
          * @tparam A
          *   The type of messages this Subject can receive
          * @return
          *   A Subject[A] that completes the Promise with the first received message and aborts with Closed for subsequent messages
          */
        def init[A](promise: Promise[A, Any])(using frame: Frame): Subject[A] =
            def tryComplete(r: A) =
                Abort.unless(promise.complete(Result.succeed(r)))(Closed("Subject", frame))
            init(
                send = tryComplete,
                trySend = tryComplete(_).andThen(true)
            )
        end init

        /** Creates a Subject that puts received messages into a Channel.
          *
          * This method creates a Subject that will put any message it receives into the provided Channel. The `send` operation uses the
          * Channel's blocking `put` method, while `trySend` uses the non-blocking `offer` method.
          *
          * @param channel
          *   The Channel to put messages into
          * @tparam A
          *   The type of messages this Subject can receive
          * @return
          *   A Subject[A] that puts received messages into the Channel
          */
        def init[A](channel: Channel[A]): Subject[A] =
            init(
                send = channel.put,
                trySend = channel.offer
            )

        /** Creates a Subject that adds received messages to an Unbounded Queue.
          *
          * This method creates a Subject that will add any message it receives into the provided Unbounded Queue. The `send` operation uses
          * the Queue's `add` method, while `trySend` also uses `add` and always returns true since unbounded queues never reject messages.
          *
          * @param queue
          *   The Unbounded Queue to add messages into
          * @tparam A
          *   The type of messages this Subject can receive
          * @return
          *   A Subject[A] that adds received messages into the Unbounded Queue
          */
        def init[A](queue: Queue.Unbounded[A]): Subject[A] =
            init(
                send = queue.add,
                trySend = queue.add(_).andThen(true)
            )

        /** Creates a Subject that publishes received messages to a Hub.
          *
          * The `send` operation uses the Hub's blocking `put`, while `trySend` uses the non-blocking `offer`. This is the publish side of
          * actor pub/sub: any number of listeners created with `hub.listen` observe each published message.
          *
          * @param hub
          *   The Hub to publish messages to
          * @tparam A
          *   The type of messages this Subject can receive
          * @return
          *   A Subject[A] that publishes received messages to the Hub
          */
        def init[A](hub: Hub[A]): Subject[A] =
            init(
                send = hub.put,
                trySend = hub.offer
            )

        /** Creates a custom Subject by directly specifying its send and trySend behaviors.
          *
          * This is a lower-level constructor that allows direct implementation of a Subject's behavior through its send and trySend
          * operations. It's primarily intended for implementing custom Subject types with specific message handling logic.
          *
          * @param send
          *   The implementation of send, handling reliable message delivery
          * @param trySend
          *   The implementation of trySend, handling non-blocking message delivery attempts
          * @tparam A
          *   The type of messages this Subject can receive
          * @return
          *   A new Subject with the specified behavior
          */
        inline def init[A](
            inline send: Frame ?=> A => Unit < (Async & Abort[Closed]),
            inline trySend: Frame ?=> A => Boolean < (Sync & Abort[Closed])
        ): Subject[A] =
            _init(send, trySend)

        @nowarn("msg=anonymous")
        // Indirection to avoid parameter shaddowing
        private inline def _init[A](
            inline _send: Frame ?=> A => Unit < (Async & Abort[Closed]),
            inline _trySend: Frame ?=> A => Boolean < (Sync & Abort[Closed])
        ): Subject[A] =
            new Subject[A]:
                def send(message: A)(using Frame)    = _send(message)
                def trySend(message: A)(using Frame) = _trySend(message)

    end Subject

end Actor

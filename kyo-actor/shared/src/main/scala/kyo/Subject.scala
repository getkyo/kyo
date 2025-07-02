package kyo

import scala.annotation.nowarn

/** Interface for sending messages to a recipient.
  *
  * A Subject represents any entity that can receive messages. While commonly used with actors, it can be used with any message-processing
  * system.
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

    /** Sends a message and waits for a response.
      *
      * This method implements the request-response pattern by automatically creating a temporary reply channel. It's useful when you need
      * to get a response back after sending a message.
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
        for
            promise <- Promise.init[Nothing, B]
            _       <- send(f(Subject.init(promise)))
            result  <- promise.get
        yield result

end Subject

object Subject:

    private val _noop =
        init(
            send = _ => (),
            trySend = _ => false
        )

    /** Creates a no-operation Subject that discards all messages.
      *
      * This Subject implementation ignores any messages sent to it and performs no action. It can be useful for testing, as a placeholder,
      * or when you need to explicitly discard messages.
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
      * Important: Only the first message sent to this Subject will successfully complete the Promise. Any subsequent messages will result
      * in an `Abort[Closed]` effect, indicating that the Subject is closed (since the Promise can only be completed once).
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
    def init[E, A](promise: Promise[E, A])(using frame: Frame): Subject[A] =
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
      * This method creates a Subject that will add any message it receives into the provided Unbounded Queue. The `send` operation uses the
      * Queue's `add` method, while `trySend` also uses `add` and always returns true since unbounded queues never reject messages.
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

    /** Creates a custom Subject by directly specifying its send and trySend behaviors.
      *
      * This is a lower-level constructor that allows direct implementation of a Subject's behavior through its send and trySend operations.
      * It's primarily intended for implementing custom Subject types with specific message handling logic.
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

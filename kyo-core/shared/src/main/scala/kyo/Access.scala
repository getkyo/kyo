package kyo

/** Represents concurrency access patterns for thread-safe data structures.
  *
  * This enum defines the different producer-consumer access patterns that can be used with concurrent collections like [[Queue]] and
  * [[Channel]] in Kyo. Specifying the correct access pattern allows the library to use optimized implementations for better performance.
  *
  * Generally, more restrictive patterns (like SingleProducerSingleConsumer) can offer better performance, but require you to maintain these
  * access constraints in your application code. When in doubt, use MultiProducerMultiConsumer as the most flexible but potentially less
  * performant pattern.
  *
  * @see
  *   [[kyo.Queue]] A thread-safe queue that uses access patterns
  * @see
  *   [[kyo.Channel]] A multi-producer, multi-consumer primitive for message passing that uses access patterns
  */
enum Access derives CanEqual:

    /** Multiple Producer Multiple Consumer access pattern
      *
      * Allows multiple threads to add items and multiple threads to remove items concurrently. This is the most flexible pattern but
      * potentially less performant than more restrictive options.
      */
    case MultiProducerMultiConsumer

    /** Multiple Producer Single Consumer access pattern
      *
      * Allows multiple threads to add items concurrently, but only one thread should remove items. Useful for scenarios where many
      * producers feed work to a single consumer thread.
      */
    case MultiProducerSingleConsumer

    /** Single Producer Multiple Consumer access pattern
      *
      * Allows only one thread to add items, but multiple threads can remove items concurrently. Appropriate for broadcast-like patterns
      * where a single source distributes work to many workers.
      */
    case SingleProducerMultiConsumer

    /** Single Producer Single Consumer access pattern
      *
      * Allows only one thread to add items and only one thread to remove items. This is the most restrictive pattern but often offers the
      * best performance when the access constraints can be maintained.
      */
    case SingleProducerSingleConsumer
end Access

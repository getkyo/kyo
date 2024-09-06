package kyo

/** Represents different access patterns for concurrent queues.
  */
enum Access derives CanEqual:
    /** Multiple Producer Multiple Consumer access pattern */
    case Mpmc

    /** Multiple Producer Single Consumer access pattern */
    case Mpsc

    /** Single Producer Multiple Consumer access pattern */
    case Spmc

    /** Single Producer Single Consumer access pattern */
    case Spsc
end Access

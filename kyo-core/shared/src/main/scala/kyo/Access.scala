package kyo

/** Represents different access patterns for concurrent queues.
  */
enum Access derives CanEqual:
    /** Multiple Producer Multiple Consumer access pattern */
    case MultiProducerMultiConsumer

    /** Multiple Producer Single Consumer access pattern */
    case MultiProducerSingleConsumer

    /** Single Producer Multiple Consumer access pattern */
    case SingleProducerMultiConsumer

    /** Single Producer Single Consumer access pattern */
    case SingleProducerSingleConsumer
end Access

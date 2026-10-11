package kyo

import scala.collection.immutable.Map

/** A log of transactional operations performed on TRefs within an STM transaction.
  *
  * TRefLog maintains a mapping from transactional references to their pending read/write operations within a transaction. It tracks both
  * read entries (which record the tick when the data was read) and write entries (which contain the new values to be committed).
  *
  * This type is used internally by the STM implementation and should not be accessed directly by application code.
  *
  * @note
  *   This is a private implementation detail of the STM system
  */
opaque type TRefLog = Map[TRef[Any], TRefLog.Entry[Any]]

private[kyo] object TRefLog:

    val empty: TRefLog = Map.empty

    extension (self: TRefLog)

        def put[A](ref: TRef[A], entry: Entry[A]): TRefLog =
            val refAny   = ref.asInstanceOf[TRef[Any]]
            val entryAny = entry.asInstanceOf[TRefLog.Entry[Any]]
            self.updated(refAny, entryAny)
        end put

        def get[A](ref: TRef[A]): Maybe[Entry[A]] =
            val refAny = ref.asInstanceOf[TRef[Any]]
            Maybe.when(self.contains(refAny))(self(refAny).asInstanceOf[Entry[A]])

        def toMap: Map[TRef[Any], TRefLog.Entry[Any]] = self

        /** This log after a nested block that started from it failed with `inner` as its log.
          *
          * The block's writes are discarded but every ref it touched that this log does not hold stays as a `Read` of what the block
          * observed: the parent continues based on the failure, so it must keep seeing that version of the ref and its commit must
          * validate it.
          */
        def rollback(inner: TRefLog): TRefLog =
            inner.foldLeft(self) { case (log, (ref, entry)) =>
                if log.contains(ref) then log
                else
                    entry match
                        case read: Read[Any]   => log.updated(ref, read)
                        case write: Write[Any] => log.updated(ref, Read(write.tick, write.observed))
            }
    end extension

    sealed abstract class Entry[A] extends Serializable:
        def tick: STM.Tick
        def value: A

        /** The committed value at `tick`, which the transaction saw before it wrote anything. */
        def observed: A
    end Entry

    /** Also the committed state of a `TRef`, so a first read logs that object instead of allocating an entry. */
    case class Read[A](tick: STM.Tick, value: A) extends Entry[A]:
        def observed: A = value

    /** `observed` lets a rolled-back write become the `Read` it was based on. */
    case class Write[A](tick: STM.Tick, value: A, observed: A) extends Entry[A]
end TRefLog

package kyo

import scala.collection.immutable.Map
import scala.collection.mutable.TreeMap

/** A log of transactional operations performed on TRefs within an STM transaction.
  *
  * TRefLog maintains a mapping from transactional references to their pending read/write operations within a transaction. It tracks both
  * read entries (which record the version of data read) and write entries (which contain the new values to be committed).
  *
  * This type is used internally by the STM implementation and should not be accessed directly by application code.
  *
  * @note
  *   This is a private implementation detail of the STM system
  */
opaque type TRefLog = Map[TRef[Any], TRefLog.Entry[Any]]

private[kyo] object TRefLog:

    given tag: Tag[Var[TRefLog]] = Tag[Var[Map[TRef[Any], TRefLog.Entry[Any]]]]

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
    end extension

    val isolate = Var.isolate.update[TRefLog](using tag)

    sealed abstract class Entry[A]:
        def tid: Long
        def value: A

    case class Read[A](tid: Long, value: A)  extends Entry[A]
    case class Write[A](tid: Long, value: A) extends Entry[A]
end TRefLog

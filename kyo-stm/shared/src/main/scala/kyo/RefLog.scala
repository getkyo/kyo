package kyo

import STM.internal.*

/** A log of transactional operations performed on TRefs within an STM transaction.
  *
  * RefLog maintains a mapping from transactional references to their pending read/write operations within a transaction. It tracks both
  * read entries (which record the version of data read) and write entries (which contain the new values to be committed).
  *
  * This type is used internally by the STM implementation and should not be accessed directly by application code.
  *
  * @note
  *   This is a private implementation detail of the STM system
  */
opaque type RefLog = Map[TRef[Any], Entry[Any]]

private[kyo] object RefLog:

    given Tag[RefLog] = Tag[Map[TRef[Any], Entry[Any]]]

    val empty: RefLog = Map.empty

    extension (self: RefLog)

        def put[A](ref: TRef[A], entry: Entry[A]): RefLog =
            self.updated(ref.asInstanceOf[TRef[Any]], entry.asInstanceOf[Entry[Any]])

        def get[A](ref: TRef[A]): Maybe[Entry[A]] =
            val refAny = ref.asInstanceOf[TRef[Any]]
            Maybe.when(self.contains(refAny))(self(refAny).asInstanceOf[Entry[A]])

        def toSeq: Seq[(TRef[Any], Entry[Any])] =
            self.toSeq
    end extension
end RefLog

package kyo

import STM.internal.*

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

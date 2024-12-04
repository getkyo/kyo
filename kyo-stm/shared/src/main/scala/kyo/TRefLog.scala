package kyo

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

    val empty: TRefLog = Map.empty

    extension (self: TRefLog)

        def put[A](ref: TRef[A], entry: Entry[A]): TRefLog =
            self.updated(ref.asInstanceOf[TRef[Any]], entry.asInstanceOf[Entry[Any]])

        def get[A](ref: TRef[A]): Maybe[Entry[A]] =
            val refAny = ref.asInstanceOf[TRef[Any]]
            Maybe.when(self.contains(refAny))(self(refAny).asInstanceOf[Entry[A]])

        def toSeq: Seq[(TRef[Any], Entry[Any])] =
            self.toSeq
    end extension

    def use[A, S](f: TRefLog => A < S)(using Frame): A < (S & Var[TRefLog]) =
        Var.use(f)

    def isolate[A: Flat, S](v: A < (S & Var[TRefLog]))(using Frame): A < (S & Var[TRefLog]) =
        Var.isolate.update[TRefLog].run(v)

    def runWith[A: Flat, B, S, S2](v: A < (S & Var[TRefLog]))(f: (TRefLog, A) => B < S2)(using Frame): B < (S & S2) =
        Var.runWith(empty)(v)(f(_, _))

    def setAndThen[A, S](log: TRefLog)(f: => A < S)(using Frame): A < (S & Var[TRefLog]) =
        Var.setAndThen(log)(f)

    def setDiscard(log: TRefLog)(using Frame): Unit < Var[TRefLog] =
        Var.setDiscard(log)

    sealed abstract class Entry[A]:
        def tid: Long
        def value: A

    case class Read[A](tid: Long, value: A)  extends Entry[A]
    case class Write[A](tid: Long, value: A) extends Entry[A]
end TRefLog

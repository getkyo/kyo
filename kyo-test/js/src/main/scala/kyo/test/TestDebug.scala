package kyo.test

// TODO Implement this with appropriate JS filesystem APIs after JVM version is finalized
private[test] object TestDebug:
    def print(executionEvent: ExecutionEvent, lock: TestDebugFileLock): Unit < Any =
        executionEvent match
            case t: ExecutionEvent.TestStarted =>
                write(t.fullyQualifiedName, s"${t.labels.mkString(" - ")} STARTED\n", true, lock)

            case t: ExecutionEvent.Test[?] =>
                removeLine(t.fullyQualifiedName, t.labels.mkString(" - ") + " STARTED", lock)

            case _ => ()

    private def write(
        fullyQualifiedTaskName: String,
        content: => String,
        append: Boolean,
        lock: TestDebugFileLock
    ): Unit < Any =
        ()

    private def removeLine(fullyQualifiedTaskName: String, searchString: String, lock: TestDebugFileLock): Unit < Any =
        ()

    def createDebugFile(fullyQualifiedTaskName: String): Unit < Any =
        ()

    def deleteIfEmpty(fullyQualifiedTaskName: String): Unit < Any = ()
end TestDebug

package kyo

sealed abstract class WorkersException(message: String)(using Frame) extends KyoException(message)

sealed abstract class InfraWorkersException(message: String)(using Frame) extends WorkersException(message)

sealed abstract class TaskWorkersException(message: String)(using Frame) extends WorkersException(message)

final case class WorkerCrashedException(label: String, attempts: Int)(using Frame)
    extends InfraWorkersException(s"worker '$label' crashed after $attempts attempt(s)")

final case class WorkerSpawnException(label: String, cause: Throwable)(using Frame)
    extends InfraWorkersException(s"could not spawn a worker '$label'")

final case class WorkerTransportException(label: String)(using Frame)
    extends InfraWorkersException(s"lost the transport to a worker '$label'")

final case class TaskTimeoutException(label: String, limit: Duration)(using Frame)
    extends TaskWorkersException(s"task in '$label' exceeded $limit and was killed")

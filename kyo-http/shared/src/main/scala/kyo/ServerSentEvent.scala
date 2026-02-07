package kyo

// TODO
case class ServerSentEvent[+A](
    data: A,
    event: Maybe[String] = Absent,
    id: Maybe[String] = Absent,
    retry: Maybe[Duration] = Absent
)

package kyo

case class HttpEvent[+A](
    data: A,
    event: Maybe[String] = Absent,
    id: Maybe[String] = Absent,
    retry: Maybe[Duration] = Absent
)

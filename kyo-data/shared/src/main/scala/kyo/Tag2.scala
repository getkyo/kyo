opaque type Tag[A] = Long

object Tag:

    val cacheSize = 1000

    def register(hash: Long, payload: String): Unit = ???

    extension [A](self: Tag[A])
        def <:<(other: Tag[A]): Boolean = ???

end Tag

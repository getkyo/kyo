package kyo

import _root_.caliban.introspection.adt.__Type
import _root_.caliban.schema.*
import _root_.caliban.schema.Step.QueryStep
import kyo.*
import zio.Task
import zio.ZIO
import zio.query.ZQuery

given [R, T: Flat, S](using ev: Schema[R, T], ev2: (T < S) <:< (T < (Aborts[Throwable] & ZIOs))): Schema[R, T < S] =
    new Schema[R, T < S]:
        override def optional: Boolean = true

        override def toType(isInput: Boolean, isSubscription: Boolean): __Type =
            ev.toType_(isInput, isSubscription)

        override def resolve(value: T < S): Step[R] =
            QueryStep(ZQuery.fromZIONow(ZIOs.run(ev2(value).map(ev.resolve))))

end given

trait Runner[S]:
    def apply[T: Flat](v: T < S): Task[T]

given [R, T: Flat, S](using ev: Schema[R, T], tag: zio.Tag[Runner[S]]): Schema[R & Runner[S], T < S] =
    new Schema[R & Runner[S], T < S]:
        override def optional: Boolean = true

        override def toType(isInput: Boolean, isSubscription: Boolean): __Type =
            ev.toType_(isInput, isSubscription)

        override def resolve(value: T < S): Step[R & Runner[S]] =
            QueryStep(ZQuery.fromZIONow(
                ZIO.serviceWithZIO[Runner[S]](runner =>
                    runner(value.map(ev.resolve))
                )
            ))

end given
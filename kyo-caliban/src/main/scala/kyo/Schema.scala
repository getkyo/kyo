package kyo

import caliban.introspection.adt.__Type
import caliban.schema.*
import caliban.schema.Step.QueryStep
import kyo.*
import zio.Task
import zio.ZIO
import zio.query.ZQuery

given zioSchema[R, A: Flat, S](using ev: Schema[R, A], ev2: (A < S) <:< (A < (Abort[Throwable] & ZIOs))): Schema[R, A < S] =
    new Schema[R, A < S]:
        override def nullable: Boolean = ev.nullable
        override def canFail: Boolean  = ev.canFail

        override def toType(isInput: Boolean, isSubscription: Boolean): __Type =
            ev.toType_(isInput, isSubscription)

        override def resolve(value: A < S): Step[R] =
            given Frame = Frame.internal
            QueryStep(ZQuery.fromZIONow(ZIOs.run(ev2(value).map(ev.resolve))))

end zioSchema

trait Runner[S]:
    def apply[A: Flat](v: A < S): Task[A]

given runnerSchema[R, A: Flat, S](using ev: Schema[R, A], tag: zio.Tag[Runner[S]]): Schema[R & Runner[S], A < S] =
    new Schema[R & Runner[S], A < S]:
        override def nullable: Boolean = ev.nullable
        override def canFail: Boolean  = ev.canFail

        override def toType(isInput: Boolean, isSubscription: Boolean): __Type =
            ev.toType_(isInput, isSubscription)

        override def resolve(value: A < S): Step[R & Runner[S]] =
            given Frame = Frame.internal
            QueryStep(ZQuery.fromZIONow(ZIO.serviceWithZIO[Runner[S]](_(value.map(ev.resolve)))))

end runnerSchema

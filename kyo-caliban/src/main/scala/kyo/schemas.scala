package kyo

import _root_.caliban.introspection.adt.__Type
import _root_.caliban.schema.*
import _root_.caliban.schema.Step.QueryStep
import kyo.*
import zio.query.ZQuery

given [R, T: Flat, S](using ev: Schema[R, T], ev2: (T < S) <:< (T < (Aborts[Throwable] & ZIOs))): Schema[R, T < S] =
    new Schema[R, T < S]:
        override def optional: Boolean = true

        override def toType(isInput: Boolean, isSubscription: Boolean): __Type =
            ev.toType_(isInput, isSubscription)

        override def resolve(value: T < S): Step[R] =
            QueryStep(ZQuery.fromZIONow(ZIOs.run(ev2(value).map(ev.resolve))))

end given

package kyo.internal

import scala.scalajs.js

/** The raw `Map` members. `js.Map`'s own `Raw` facade is `private[js]`, and the public Scala view
  * returns an `Option`, which would allocate on every dispatch. `js.Map.apply` casts the same way.
  */
@js.native
private trait RawTagMemo extends js.Object:
    def get(key: String): js.UndefOr[Int]  = js.native
    def set(key: String, value: Int): Unit = js.native
end RawTagMemo

/** A `Tag`'s hash code, memoized for the encoded `String` form.
  *
  * Scala.js compiles `String.hashCode` to a loop over the characters and nothing memoizes the result:
  * a JS string has nowhere to keep one, unlike the JVM's `String.hash` field. `Tag`'s dispatch path
  * asks for it up to four times per comparison that misses the identity check, twice in the fast path
  * and twice more to build the subtype cache's key, over encoded types that run to a few hundred
  * characters. Hashing was the largest single cost of effect dispatch on JS because of it.
  *
  * Keys are the statically derived tag strings, a set the program fixes at compile time, so this grows
  * no further than the decode cache it sits beside. Dynamic tags carry their own `hashCode` and pass
  * straight through.
  */
private[kyo] object TagHash:

    private val memo = js.Map.empty[String, Int].asInstanceOf[RawTagMemo]

    def of(tag: Any): Int =
        tag match
            case tag: String =>
                val cached = memo.get(tag)
                if cached.isDefined then cached.get
                else
                    val hash = tag.hashCode
                    memo.set(tag, hash)
                    hash
                end if
            case tag => tag.hashCode
end TagHash

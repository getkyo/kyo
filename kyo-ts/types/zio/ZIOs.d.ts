export declare class ZIOs {

  static get<R, E, A>(v: ZIO<R, E, A>): Kyo<A, Env<R> & Abort<E> & Async>;
  static toCause<E>(ex: Error<E>): Cause<E>;
  static toError<E>(cause: Cause<E>): Error<E>;
  static toResult<E, A>(exit: Exit<E, A>): Result<E, A>;
};
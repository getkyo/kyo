export declare class Abort {

  static catching<E, A, S, E1>(f: (x1: E) => E1, v: () => Kyo<A, S>): Kyo<A, Abort<E1> & S>;
  readonly eliminateAbort: eliminateAbort;
  static error<E>(error: Error<E>): Kyo<never, Abort<E>>;
  static fail<E>(value: E): Kyo<never, Abort<E>>;
  static fold<E, A, B, S, ER>(onSuccess: (x1: A) => Kyo<B, S>, onFail: (x1: E) => Kyo<B, S>, v: () => Kyo<A, Abort<E | ER> & S>): Kyo<B, S & Abort<ER>>;
  static fold<E, A, B, S, ER>(onSuccess: (x1: A) => Kyo<B, S>, onFail: (x1: E) => Kyo<B, S>, onPanic: (x1: Throwable) => Kyo<B, S>, v: () => Kyo<A, Abort<E | ER> & S>): Kyo<B, S & SReduced>;
  static foldError<E, A, B, S, ER>(onSuccess: (x1: A) => Kyo<B, S>, onError: (x1: Error<E>) => Kyo<B, S>, v: () => Kyo<A, Abort<E | ER> & S>): Kyo<B, S & SReduced>;
  static foldOrThrow<A, B, E, S>(onSuccess: (x1: A) => Kyo<B, S>, onFail: (x1: E) => Kyo<B, S>, v: () => Kyo<A, Abort<E> & S>): Kyo<B, S>;
  static get<E, A>(either: Either<E, A>): Kyo<A, Abort<E>>;
  readonly literal: literal;
  static panic<E>(ex: Throwable): Kyo<never, Abort<E>>;
  static recover<E, A, B, S, ER>(onFail: (x1: E) => Kyo<B, S>, onPanic: (x1: Throwable) => Kyo<B, S>, v: () => Kyo<A, Abort<E | ER> & S>): Kyo<A | B, S & SReduced>;
  static recover<E, A, B, S, ER>(onFail: (x1: E) => Kyo<B, S>, v: () => Kyo<A, Abort<E | ER> & S>): Kyo<A | B, S & SReduced & Abort<never>>;
  static recoverError<E, A, B, S, ER>(onError: (x1: Error<E>) => Kyo<B, S>, v: () => Kyo<A, Abort<E | ER> & S>): Kyo<A | B, S & SReduced & Abort<never>>;
  static recoverOrThrow<A, E, B, S>(onFail: (x1: E) => Kyo<B, S>, v: () => Kyo<A, Abort<E> & S>): Kyo<A | B, S>;
};
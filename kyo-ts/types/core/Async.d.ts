export declare class Async {

  static collect<E, A, B, S>(iterable: Iterable<A>, concurrency: number, f: (x1: A) => Kyo<Maybe<B>, Abort<E> & Async & S>): Kyo<Chunk<B>, Abort<E> & Async & S>;
  static collectAll<E, A, S>(iterable: Iterable<Kyo<A, Abort<E> & Async & S>>, concurrency: number): Kyo<Chunk<A>, Abort<E> & Async & S>;
  static collectAllDiscard<E, A, S>(iterable: Iterable<Kyo<A, Abort<E> & Async & S>>, concurrency: number): Kyo<void, Abort<E> & Async & S>;
  readonly defaultConcurrency: number;
  static filter<E, A, S>(iterable: Iterable<A>, concurrency: number, f: (x1: A) => Kyo<boolean, Abort<E> & Async & S>): Kyo<Chunk<A>, Abort<E> & Async & S>;
  static foreach<E, A, B, S>(iterable: Iterable<A>, concurrency: number, f: (x1: A) => Kyo<B, Abort<E> & Async & S>): Kyo<Chunk<B>, Abort<E> & Async & S>;
  static foreachDiscard<E, A, B, S>(iterable: Iterable<A>, concurrency: number, f: (x1: A) => Kyo<B, Abort<E> & Async & S>): Kyo<void, Abort<E> & Async & S>;
  static foreachIndexed<E, A, B, S>(iterable: Iterable<A>, concurrency: number, f: (x1: number, x2: A) => Kyo<B, Abort<E> & Async & S>): Kyo<Chunk<B>, Abort<E> & Async & S>;
  static gather<E, A, S>(iterable: Iterable<Kyo<A, Abort<E> & Async & S>>): Kyo<Chunk<A>, Abort<E> & Async & S>;
  static never<A>(): Kyo<A, Async>;
  static race<E, A, S>(iterable: Iterable<Kyo<A, Abort<E> & Async & S>>): Kyo<A, Abort<E> & Async & S>;
  static raceFirst<E, A, S>(iterable: Iterable<Kyo<A, Abort<E> & Async & S>>): Kyo<A, Abort<E> & Async & S>;
};
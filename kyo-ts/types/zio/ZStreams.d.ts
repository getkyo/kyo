export declare class ZStreams {

  static get<E, A>(stream: () => ZStream<unknown, E, A>): Stream<A, Abort<E> & Async>;
};
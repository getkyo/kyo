export declare class StreamHub {

  static collectAll<V, E, S>(streamObj: Stream, streams: Stream<V, Abort<E> & S & Async>[], bufferSize: number): Stream<V, S & Async>;
  static collectAllHalting<V, E, S>(streamObj: Stream, streams: Stream<V, S & Abort<E> & Async>[], bufferSize: number): Stream<V, S & Async>;
  readonly defaultAsyncStreamBufferSize: defaultAsyncStreamBufferSize;
  static fromIterator<V>(streamObj: Stream, v: () => Iterator<V>, chunkSize: number): Stream<V, Sync>;
  static fromIteratorCatching<E, V>(streamObj: Stream, v: () => Iterator<V>, chunkSize: number): Stream<V, Sync & Abort<E>>;
};
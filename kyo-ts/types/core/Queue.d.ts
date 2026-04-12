export declare class Queue {
  capacity(): number;
  close(): Kyo<Maybe<A[]>, Sync>;
  closeAwaitEmpty(): Kyo<boolean, Async>;
  closed(): Kyo<boolean, Sync>;
  drain(): Kyo<Chunk<A>, Sync & Abort<Closed>>;
  drainUpTo(max: number): Kyo<Chunk<A>, Sync & Abort<Closed>>;
  empty(): Kyo<boolean, Sync & Abort<Closed>>;
  full(): Kyo<boolean, Sync & Abort<Closed>>;
  offer(v: A): Kyo<boolean, Sync & Abort<Closed>>;
  offerDiscard(v: A): Kyo<void, Sync & Abort<Closed>>;
  peek(): Kyo<Maybe<A>, Sync & Abort<Closed>>;
  poll(): Kyo<Maybe<A>, Sync & Abort<Closed>>;
  size(): Kyo<number, Sync & Abort<Closed>>;
  unsafe(): Unsafe<A>;
};
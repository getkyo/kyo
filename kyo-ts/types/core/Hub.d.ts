export declare class Hub {
  close(): Kyo<Maybe<A[]>, Sync>;
  closed(): Kyo<boolean, Sync>;
  empty(): Kyo<boolean, Sync & Abort<Closed>>;
  full(): Kyo<boolean, Sync & Abort<Closed>>;
  listen(): Kyo<Listener<A>, Sync & Abort<Closed> & Scope>;
  listen(bufferSize: number, filter: (x1: A) => boolean): Kyo<Listener<A>, Sync & Abort<Closed> & Scope>;
  listen(bufferSize: number): Kyo<Listener<A>, Sync & Abort<Closed> & Scope>;
  offer(v: A): Kyo<boolean, Sync & Abort<Closed>>;
  offerDiscard(v: A): Kyo<void, Sync & Abort<Closed>>;
  put(v: A): Kyo<void, Async & Abort<Closed>>;
  putBatch(values: A[]): Kyo<void, Abort<Closed> & Async>;

  static init<A>(): Kyo<Hub<A>, Sync & Scope>;
};
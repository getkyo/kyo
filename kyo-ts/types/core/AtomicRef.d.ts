export declare class AtomicRef {
  compareAndSet(curr: A, next: A): Kyo<boolean, Sync>;
  get(): Kyo<A, Sync>;
  getAndSet(v: A): Kyo<A, Sync>;
  getAndUpdate(f: (x1: A) => A): Kyo<A, Sync>;
  lazySet(v: A): Kyo<void, Sync>;
  set(v: A): Kyo<void, Sync>;
  readonly unsafe: Unsafe<A>;
  updateAndGet(f: (x1: A) => A): Kyo<A, Sync>;
  use<B, S>(f: (x1: A) => Kyo<B, S>): Kyo<B, S & Sync>;

  static init<A>(initialValue: A): Kyo<AtomicRef<A>, Sync>;
  static initWith<A, B, S>(initialValue: A, f: (x1: AtomicRef<A>) => Kyo<B, S>): Kyo<B, S & Sync>;
};
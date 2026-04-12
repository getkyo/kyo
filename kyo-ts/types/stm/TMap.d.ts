export declare class TMap {
  clear(): Kyo<void, STM>;
  contains(key: K): Kyo<boolean, STM>;
  entries(): Kyo<Iterable<[K, V]>, STM>;
  filter<S>(p: (x1: K, x2: V) => Kyo<boolean, S>): Kyo<void, STM & S>;
  findFirst<A, S>(f: (x1: K, x2: V) => Kyo<Maybe<A>, S>): Kyo<Maybe<A>, STM & S>;
  fold<A, B, S>(acc: A, f: (x1: A, x2: K, x3: V) => Kyo<A, S>): Kyo<A, STM & S>;
  get(key: K): Kyo<Maybe<V>, STM>;
  getOrElse<A, S>(key: K, orElse: () => Kyo<V, S>): Kyo<V, STM & S>;
  isEmpty(): Kyo<boolean, STM>;
  keys(): Kyo<Iterable<K>, STM>;
  nonEmpty(): Kyo<boolean, STM>;
  put(key: K, value: V): Kyo<void, STM>;
  remove(key: K): Kyo<Maybe<V>, STM>;
  removeAll(keys: K[]): Kyo<void, STM>;
  removeDiscard(key: K): Kyo<void, STM>;
  size(): Kyo<number, STM>;
  snapshot(): Kyo<Map<K, V>, STM>;
  updateWith<S>(key: K, f: (x1: Maybe<V>) => Kyo<Maybe<V>, S>): Kyo<void, STM & S>;
  use<A, S>(key: K, f: (x1: Maybe<V>) => Kyo<A, S>): Kyo<A, STM & S>;

  static init<K, V>(entries: [K, V][]): Kyo<TMap<K, V>, Sync>;
  static init<K, V>(): Kyo<TMap<K, V>, Sync>;
  static initWith<K, V, A, S>(entries: [K, V][], f: (x1: TMap<K, V>) => Kyo<A, S>): Kyo<TMap<K, V>, Sync & S>;
};
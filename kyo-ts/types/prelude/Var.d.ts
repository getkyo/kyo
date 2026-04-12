export declare class Var {

  static get<V>(): Kyo<V, Var<V>>;
  readonly internal: internal;
  readonly isolate: isolate;
  static run<V, A, S>(state: V, v: Kyo<A, Var<V> & S>): Kyo<A, S>;
  static runTuple<V, A, S>(state: V, v: Kyo<A, Var<V> & S>): Kyo<[V, A], S>;
  static set<V>(value: V): Kyo<V, Var<V>>;
  static setDiscard<V>(value: V): Kyo<void, Var<V>>;
  static setWith<V, A, S>(value: V, f: () => Kyo<A, S>): Kyo<A, Var<V> & S>;
  static update<V>(update: (x1: V) => V): Kyo<V, Var<V>>;
  static updateDiscard<V>(f: (x1: V) => V): Kyo<void, Var<V>>;
  static updateWith<V, A, S>(update: (x1: V) => V, f: (x1: V) => Kyo<A, S>): Kyo<A, Var<V> & S>;
  static use<V, A, S>(f: (x1: V) => Kyo<A, S>): Kyo<A, Var<V> & S>;
};
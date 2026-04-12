export declare class DictBuilder {
  add(key: K, value: V): this;
  clear(): void;
  result(): Dict<K, V>;
  readonly size: number;

  static init<K, V>(): DictBuilder<K, V>;
  static initTransform<K, V, K2, V2>(f: (x1: DictBuilder<K2, V2>, x2: K, x3: V) => void): (x1: K, x2: V) => void & DictBuilder<K2, V2>;
};
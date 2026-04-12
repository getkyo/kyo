export declare class Field {
  readonly name: Name;
  readonly nested: Field<unknown, unknown>[];
  readonly tag: Tag<Value>;

  static apply<Name, Value>(): Field<Name, Value>;
  static apply<Name, Value>(name: Name, nested: Field<unknown, unknown>[]): Field<Name, Value>;
};
export declare class TTable {
  get(id: Id): Kyo<Maybe<Record<Fields>>, STM>;
  insert(record: Record<Fields>): Kyo<Id, STM>;
  isEmpty(): Kyo<boolean, STM>;
  remove(id: Id): Kyo<Maybe<Record<Fields>>, STM>;
  size(): Kyo<number, STM>;
  snapshot(): Kyo<Map<Id, Record<Fields>>, STM>;
  unsafeId(id: number): Id;
  update(id: Id, record: Record<Fields>): Kyo<Maybe<Record<Fields>>, STM>;
  upsert(id: Id, record: Record<Fields>): Kyo<void, STM>;
};
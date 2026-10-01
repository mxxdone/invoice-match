export type DraftLine = {
  id: number;
  name: string;
  quantity: number;
  price: number;
  item: string;
  edited?: boolean;
};

// Only a newly added, untouched row is disposable. An edited or persisted
// row must never silently disappear, even when its values look like defaults.
export function isUnusedDraftLine(row: DraftLine): boolean {
  return !row.edited && !row.name.trim() && !row.item.trim()
    && row.quantity === 1 && row.price === 0;
}

export function isValidDraftLine(row: DraftLine): boolean {
  return Boolean(row.name.trim()) && Number.isSafeInteger(row.quantity)
    && row.quantity > 0 && Number.isSafeInteger(row.price) && row.price >= 0;
}

export function enteredDraftLines(rows: DraftLine[]): DraftLine[] {
  return rows.filter(row => !isUnusedDraftLine(row));
}

export function draftLinePayload(rows: DraftLine[]) {
  return enteredDraftLines(rows).map((row, index) => ({
    lineNumber: index + 1,
    rawItemName: row.name,
    quantity: row.quantity,
    unitPrice: row.price,
    confirmedItemId: row.item || null,
  }));
}

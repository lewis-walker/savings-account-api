/** What the API returns for an account. Mirrors AccountResponse on the server. */
export type Account = {
  id: string;
  accountNumber: string;
  customerName: string;
  nickname: string | null;
  openedAt: string;
}

/**
 * An account as the UI holds it: either a row this client invented, or one the server
 * has confirmed. The union says which fields exist in each case, rather than making all
 * of them optional and leaving every reader to guess.
 *
 * <p>`clientRef` is the React key on both arms, and deliberately not the account id. A
 * pending row exists before the server has minted an id - the API generates them, which
 * the brief requires - so keying on the id would change the key the moment the response
 * landed, and React would unmount and remount the row. The ref is minted once and never
 * changes for as long as the row exists.
 *
 * <p>The key is a rendering concern. The id is a domain concern. Conflating them is what
 * makes optimistic lists flicker.
 */
export type AccountRow = PendingAccountRow | OpenedAccountRow;

/** On screen, not yet anywhere else. It has no id, number or opening date to show. */
export type PendingAccountRow = {
  clientRef: string;
  nickname: string | null;
  pending: true;
};

/** Committed, so every field the API returns is present. */
export type OpenedAccountRow = Account & {
  clientRef: string;
  pending: false;
};

/** RFC 7807. One shape for every error the API returns. */
export type Problem = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  correlationId?: string;
  retryable?: boolean;
  limit?: number;
  errors?: { field: string; message: string }[];
}

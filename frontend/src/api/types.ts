/** What the API returns for an account. Mirrors AccountResponse on the server. */
export interface Account {
  id: string;
  accountNumber: string;
  customerName: string;
  nickname: string | null;
  openedAt: string;
}

/**
 * An account as the UI holds it.
 *
 * <p>`clientRef` is the React key, and it is deliberately not the account id. An
 * optimistic row exists before the server has given it an id — the API generates
 * account ids, which the brief requires — so keying on `id` would mean the key changing
 * from undefined to a real value the moment the response lands, and React would unmount
 * and remount the row. The ref is minted when the row is created and never changes for
 * as long as that row exists.
 *
 * The key is a rendering concern. The id is a domain concern. Conflating them is what
 * makes optimistic lists flicker.
 */
export interface AccountRow extends Partial<Account> {
  clientRef: string;
  nickname: string | null;
  /** True while the server has not yet confirmed this account exists. */
  pending?: boolean;
}

/** RFC 7807. One shape for every error the API returns. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  correlationId?: string;
  retryable?: boolean;
  limit?: number;
  errors?: { field: string; message: string }[];
}

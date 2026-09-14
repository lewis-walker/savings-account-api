import { useState, type FormEvent } from 'react';
import { nanoid } from 'nanoid';
import { asProblem, useListAccountsQuery, useOpenAccountMutation } from '../api/api';
import type { AccountRow } from '../api/types';
import Problem from './Problem';

const MAXIMUM_ACCOUNTS = 5;

/** One in-flight or failed attempt to open an account. */
interface Attempt {
  nickname: string | null;
  clientRef: string;
  /**
   * Stable across retries of the same logical request.
   *
   * <p>This is the whole reason a retry is safe. Without it, "try again" after a
   * timeout is indistinguishable from "open another account" — and against a cap of
   * five, a network blip would silently cost the customer a slot.
   */
  idempotencyKey: string;
}

export default function Accounts() {
  const { data: accounts = [], isLoading, error: listError } = useListAccountsQuery();
  const [openAccount, { isLoading: opening }] = useOpenAccountMutation();

  const [nickname, setNickname] = useState('');
  const [failed, setFailed] = useState<{ attempt: Attempt; error: unknown } | null>(null);

  const confirmed = accounts.filter((account) => !account.pending).length;
  const full = confirmed >= MAXIMUM_ACCOUNTS;

  async function attempt(next: Attempt) {
    setFailed(null);
    try {
      await openAccount(next).unwrap();
      setNickname('');
    } catch (error) {
      // The optimistic row has already been rolled back by the mutation. What is kept
      // is the idempotency key, so retrying is a retry rather than a second request.
      setFailed({ attempt: next, error });
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void attempt({
      nickname: nickname.trim() === '' ? null : nickname.trim(),
      clientRef: nanoid(),
      idempotencyKey: nanoid(),
    });
  }

  function retry() {
    if (!failed) return;
    void attempt({
      ...failed.attempt,
      // A new row on screen, so a new render key - but the same idempotency key, so
      // the server treats it as the same request it already saw.
      clientRef: nanoid(),
    });
  }

  return (
    <div className="stack">
      <section className="card">
        <div className="card-head">
          <h1>Your savings accounts</h1>
          <span className={`count ${full ? 'full' : ''}`}>
            {confirmed} of {MAXIMUM_ACCOUNTS}
          </span>
        </div>

        {isLoading && <p className="muted">Loading…</p>}
        {listError && <Problem problem={asProblem(listError)} />}

        {!isLoading && accounts.length === 0 && (
          <p className="muted">No accounts yet. Open your first below.</p>
        )}

        <ul className="accounts">
          {accounts.map((account) => (
            // Keyed by clientRef, never by id. An optimistic row has no id yet, and the
            // key must not change when one arrives.
            <Row key={account.clientRef} account={account} />
          ))}
        </ul>
      </section>

      <section className="card">
        <h2>Open another account</h2>
        {full ? (
          <p className="muted">
            You are holding the maximum of {MAXIMUM_ACCOUNTS} savings accounts. The
            server enforces this too — this message is a courtesy, not the rule.
          </p>
        ) : (
          <form onSubmit={submit}>
            <label>
              Nickname <span className="optional">optional, 5–30 characters</span>
              <input
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                placeholder="Holiday fund"
                maxLength={60}
              />
            </label>
            <button type="submit" disabled={opening}>
              {opening ? 'Opening…' : 'Open account'}
            </button>
          </form>
        )}

        {failed && (
          <div className="failure">
            <Problem problem={asProblem(failed.error)} />
            {asProblem(failed.error).retryable && (
              <button type="button" className="secondary" onClick={retry}>
                Try again
              </button>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

function Row({ account }: { account: AccountRow }) {
  return (
    <li className={account.pending ? 'account pending' : 'account'}>
      <div className="account-main">
        <span className="nickname">{account.nickname ?? 'Savings account'}</span>
        <span className="number">
          {/* An optimistic row has no account number: the API allocates it, and until it
              answers there is nothing honest to display. Showing a placeholder number
              would be inventing a banking identifier, which is worse than a dash. */}
          {account.accountNumber ?? '—'}
        </span>
      </div>
      {account.pending ? (
        <span className="badge">Opening…</span>
      ) : (
        <span className="opened">
          {account.openedAt
            ? new Date(account.openedAt).toLocaleDateString('en-NZ', {
                day: 'numeric', month: 'short', year: 'numeric',
              })
            : ''}
        </span>
      )}
    </li>
  );
}

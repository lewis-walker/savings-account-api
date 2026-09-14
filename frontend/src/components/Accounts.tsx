import { asProblem, useListAccountsQuery } from '../api/api';
import AccountRow from './AccountRow';
import Card from './Card';
import Problem from './Problem';
import styles from './Accounts.module.css';
import { useOpenAccount } from './useOpenAccount';

const MAXIMUM_ACCOUNTS = 5;

export default function Accounts() {
  const { data: accounts = [], isLoading, error: listError } = useListAccountsQuery();
  const { nickname, setNickname, submit, retry, failure, opening } = useOpenAccount();

  const confirmed = accounts.filter((account) => !account.pending).length;
  const full = confirmed >= MAXIMUM_ACCOUNTS;

  return (
    <div className={styles.stack}>
      <Card>
        <div className={styles.head}>
          <h1>Your savings accounts</h1>
          <span className={full ? `${styles.count} ${styles.full}` : styles.count}>
            {confirmed} of {MAXIMUM_ACCOUNTS}
          </span>
        </div>

        {isLoading && <p className={styles.muted}>Loading…</p>}
        {listError && <Problem problem={asProblem(listError)} />}
        {!isLoading && accounts.length === 0 && (
          <p className={styles.muted}>No accounts yet. Open your first below.</p>
        )}

        <ul className={styles.list}>
          {accounts.map((account) => (
            // Keyed by clientRef, never by id: a pending row has no id yet, and the key
            // must not change when one arrives.
            <AccountRow key={account.clientRef} account={account} />
          ))}
        </ul>
      </Card>

      <Card>
        <h2>Open another account</h2>
        {full ? (
          <p className={styles.muted}>
            You are holding the maximum of {MAXIMUM_ACCOUNTS} savings accounts. The
            server enforces this too — this message is a courtesy, not the rule.
          </p>
        ) : (
          <form onSubmit={submit}>
            <label>
              Nickname <span className={styles.optional}>optional, 5–30 characters</span>
              <input
                value={nickname}
                onChange={(event) => setNickname(event.target.value)}
                placeholder="Holiday fund"
                maxLength={60}
              />
            </label>
            <button type="submit" disabled={opening}>
              {opening ? 'Opening…' : 'Open account'}
            </button>
          </form>
        )}

        {failure && (
          <div className={styles.failure}>
            <Problem problem={asProblem(failure.error)} />
            {asProblem(failure.error).retryable && (
              <button type="button" className={styles.retry} onClick={retry}>
                Try again
              </button>
            )}
          </div>
        )}
      </Card>
    </div>
  );
}

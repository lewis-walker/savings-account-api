import type { AccountRow as Row } from '../api/types';
import styles from './AccountRow.module.css';

const OPENED_ON = new Intl.DateTimeFormat('en-NZ', {
  day: 'numeric', month: 'short', year: 'numeric',
});

export default function AccountRow({ account }: { account: Row }) {
  return (
    <li className={account.pending ? `${styles.row} ${styles.pending}` : styles.row}>
      <div className={styles.main}>
        <span className={styles.nickname}>{account.nickname ?? 'Savings account'}</span>
        {/* A pending row has no account number: the API allocates it, and a placeholder
            would be inventing a banking identifier. */}
        <span className={styles.number}>{account.accountNumber ?? '—'}</span>
      </div>
      {account.pending ? (
        <span className={styles.badge}>Opening…</span>
      ) : (
        <span className={styles.opened}>
          {account.openedAt ? OPENED_ON.format(new Date(account.openedAt)) : ''}
        </span>
      )}
    </li>
  );
}

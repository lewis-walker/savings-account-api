import type { Problem as ProblemDetail } from '../api/types';
import styles from './Problem.module.css';

/**
 * Renders an RFC 7807 problem document.
 *
 * <p>One component for every failure in the application, because the API returns one
 * shape for every failure. A validation error, a refused nickname, a full customer and a
 * database outage all arrive here and all render without a special case.
 *
 * <p>The correlation id is shown deliberately. It is on this response and on every log
 * line the request produced, so a customer reading it out is the fastest route from
 * "it did not work" to the exact failure.
 */
export default function Problem({ problem }: { problem: ProblemDetail }) {
  return (
    <div className={styles.problem} role="alert">
      <strong>{problem.title ?? 'Something went wrong'}</strong>
      {problem.detail && <p>{problem.detail}</p>}
      {problem.errors && problem.errors.length > 0 && (
        <ul>
          {problem.errors.map((error) => (
            <li key={error.field}>
              <span className={styles.field}>{error.field}</span> {error.message}
            </li>
          ))}
        </ul>
      )}
      {problem.correlationId && (
        <p className={styles.reference}>
          Reference <code>{problem.correlationId}</code>
        </p>
      )}
    </div>
  );
}

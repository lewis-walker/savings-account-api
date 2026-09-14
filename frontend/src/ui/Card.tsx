import type { ReactNode } from 'react';
import styles from './Card.module.css';

/**
 * The panel surface, as a component rather than a class three files reach for by name.
 */
export default function Card({ narrow, children }: { narrow?: boolean; children: ReactNode }) {
  return <section className={narrow ? `${styles.card} ${styles.narrow}` : styles.card}>{children}</section>;
}

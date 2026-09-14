import { useState, type SubmitEvent } from 'react';
import { nanoid } from 'nanoid';
import { useOpenAccountMutation } from '../api/api';

/** One in-flight or failed attempt to open an account. */
export type Attempt = {
  nickname: string | null;
  clientRef: string;
  /**
   * Stable across retries of the same logical request, which is what makes "try again"
   * safe. Without it a retry after a timeout is indistinguishable from opening a second
   * account, and against a cap of five that silently costs the customer a slot.
   */
  idempotencyKey: string;
}

/**
 * Opening an account, separated from drawing it: the form field, the failed attempt kept
 * for retry, and the two ways a request starts.
 */
export function useOpenAccount() {
  const [openAccount, { isLoading: opening }] = useOpenAccountMutation();
  const [nickname, setNickname] = useState('');
  const [failure, setFailure] = useState<{ attempt: Attempt; error: unknown } | null>(null);

  async function attemptOpenAccount(next: Attempt) {
    setFailure(null);
    try {
      await openAccount(next).unwrap();
      setNickname('');
    } catch (error) {
      // The optimistic row is already rolled back. What is kept is the idempotency key.
      setFailure({ attempt: next, error });
    }
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = nickname.trim();
    void attemptOpenAccount({
      nickname: trimmed === '' ? null : trimmed,
      clientRef: nanoid(),
      idempotencyKey: nanoid(),
    });
  }

  function retry() {
    if (!failure) return;
    // A new row on screen, so a new render key - but the same idempotency key, so the
    // server treats it as the request it has already seen.
    void attemptOpenAccount({ ...failure.attempt, clientRef: nanoid() });
  }

  return { nickname, setNickname, submit, retry, failure, opening };
}

import { configureStore } from '@reduxjs/toolkit';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { Theme } from '@radix-ui/themes';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/api';
import authReducer, { signedIn } from '../auth/authSlice';
import Accounts from './Accounts';

/**
 * The optimistic upsert, which is the only genuinely tricky logic in this app.
 *
 * <p>Two things are asserted that a casual test would miss. That the row appears
 * *before* the server answers — a test which only waits for the final state passes
 * against no optimistic update at all. And that the row is the **same DOM node** before
 * and after reconciliation, which is what proves the React key never changed. If the key
 * were the account id, the node would be replaced when the id arrived, and the user
 * would see a flicker.
 */
function makeStore() {
  return configureStore({
    reducer: { [api.reducerPath]: api.reducer, auth: authReducer },
    middleware: (getDefault) => getDefault().concat(api.middleware),
  });
}

/**
 * A fetch whose account-creation response we resolve by hand.
 *
 * <p>Note it reads the method and headers off the {@link Request}, not off an init
 * object. RTK Query builds a Request and calls {@code fetch(request)} with one
 * argument, so a mock that inspects {@code init.method} sees undefined, treats every
 * call as a GET and answers the creation with the account list. The symptom is a test
 * that fails claiming the optimistic row is not pending - which is true, because it has
 * already been reconciled with an empty array.
 */
function controllableFetch() {
  let releaseCreate!: (value: { status: number; body: unknown }) => void;
  const createResponse = new Promise<{ status: number; body: unknown }>(
    (resolve) => { releaseCreate = resolve; },
  );

  const asResponse = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : null;
    const method = (init?.method ?? request?.method ?? 'GET').toUpperCase();
    if (method === 'GET') {
      return asResponse(200, []);
    }
    const outcome = await createResponse;
    return asResponse(outcome.status, outcome.body);
  });

  return { fetchMock, releaseCreate };
}

/** The Idempotency-Key of a recorded call, however fetch was invoked. */
function idempotencyKeyOf(call: unknown[]): string | null {
  const [input, init] = call as [RequestInfo | URL, RequestInit | undefined];
  if (input instanceof Request) {
    return input.headers.get('Idempotency-Key');
  }
  return new Headers(init?.headers).get('Idempotency-Key');
}

/** Whether a recorded fetch call was a create. */
function isCreate(call: unknown[]): boolean {
  const [input, init] = call as [RequestInfo | URL, RequestInit | undefined];
  const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
  return method.toUpperCase() === 'POST';
}

describe('opening an account', () => {
  let store: ReturnType<typeof makeStore>;

  beforeEach(() => {
    store = makeStore();
    store.dispatch(signedIn({ accessToken: 'test-token', email: 'ada@example.test' }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows the account before the server confirms it, then reconciles the same row', async () => {
    const { fetchMock, releaseCreate } = controllableFetch();
    vi.stubGlobal('fetch', fetchMock);

    render(<Provider store={store}><Theme><Accounts /></Theme></Provider>);
    await screen.findByText(/no accounts yet/i);

    await userEvent.type(screen.getByRole('textbox'), 'Holiday fund');
    await userEvent.click(screen.getByRole('button', { name: /open account/i }));

    // 1. On screen while the request is still in flight. The server has not replied,
    //    so there is no account number to show and the row says so.
    const pendingRow = await screen.findByText('Holiday fund');
    const row = pendingRow.closest('li')!;
    // Scoped to the row, so this cannot accidentally match a badge elsewhere.
    expect(within(row).getByText('Opening…')).toBeInTheDocument();
    // No account number yet. The API allocates it, so until the server answers there
    // is nothing honest to put here.
    expect(within(row).getByText('—')).toBeInTheDocument();

    // 2. Now let the server answer.
    releaseCreate({
      status: 201,
      body: {
        id: 'a2f1c6de-0000-4000-8000-000000000001',
        accountNumber: '99-0001-0000123-030',
        customerName: 'Ada Lovelace',
        nickname: 'Holiday fund',
        displayName: 'Holiday fund',
        openedAt: '2026-09-14T10:00:00Z',
      },
    });

    await waitFor(() => {
      expect(screen.getByText('99-0001-0000123-030')).toBeInTheDocument();
    });

    // 3. The same element, not a replacement. This is the assertion that matters: if
    //    the row were keyed by the account id, React would have unmounted this node and
    //    mounted a new one when the id arrived.
    expect(screen.getByText('Holiday fund').closest('li')).toBe(row);
    expect(within(row).queryByText('Opening…')).not.toBeInTheDocument();
  });

  it('removes the row again when the server refuses', async () => {
    const { fetchMock, releaseCreate } = controllableFetch();
    vi.stubGlobal('fetch', fetchMock);

    render(<Provider store={store}><Theme><Accounts /></Theme></Provider>);
    await screen.findByText(/no accounts yet/i);

    await userEvent.type(screen.getByRole('textbox'), 'my badword account');
    await userEvent.click(screen.getByRole('button', { name: /open account/i }));
    expect(await screen.findByText('my badword account')).toBeInTheDocument();

    releaseCreate({
      status: 422,
      body: {
        title: 'Nickname not acceptable',
        detail: 'That nickname cannot be used. Please choose another.',
        status: 422,
        correlationId: 'f6606eea3aea785c284ca07adc4c15ff',
      },
    });

    // Rolled back. Leaving a rejected row on screen would tell a customer they hold an
    // account they do not, which against a cap of five is a real problem.
    await waitFor(() => {
      expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Nickname not acceptable');
    expect(screen.getByRole('alert')).toHaveTextContent('f6606eea3aea785c284ca07adc4c15ff');
  });

  it('still shows the account when the optimistic row is gone before the response lands',
    async () => {
      const { fetchMock, releaseCreate } = controllableFetch();
      vi.stubGlobal('fetch', fetchMock);

      render(<Provider store={store}><Theme><Accounts /></Theme></Provider>);
      await screen.findByText(/no accounts yet/i);

      await userEvent.type(screen.getByRole('textbox'), 'Holiday fund');
      await userEvent.click(screen.getByRole('button', { name: /open account/i }));
      await screen.findByText('Holiday fund');

      // A reconnect refetch rebuilds the list and the optimistic row is gone. Before the
      // upsert, reconciliation was a find-and-merge guarded by `if (row)`, so this was a
      // silent no-op - and because the mutation does not invalidate, nothing ever
      // refetched. A committed account simply never appeared.
      store.dispatch(api.util.updateQueryData('listAccounts', undefined, () => []));

      releaseCreate({
        status: 201,
        body: {
          id: 'a2f1c6de-0000-4000-8000-000000000009',
          accountNumber: '99-0001-0000321-030',
          customerName: 'Ada Lovelace',
          nickname: 'Holiday fund',
          displayName: 'Holiday fund',
          openedAt: '2026-09-14T10:00:00Z',
        },
      });

      await waitFor(() => {
        expect(screen.getByText('99-0001-0000321-030')).toBeInTheDocument();
      });
      expect(screen.getAllByRole('listitem')).toHaveLength(1);
    });

  it('rolls back the row it added, not whatever now sits at that index', async () => {
    const { fetchMock, releaseCreate } = controllableFetch();
    vi.stubGlobal('fetch', fetchMock);

    render(<Provider store={store}><Theme><Accounts /></Theme></Provider>);
    await screen.findByText(/no accounts yet/i);

    await userEvent.type(screen.getByRole('textbox'), 'Holiday fund');
    await userEvent.click(screen.getByRole('button', { name: /open account/i }));
    await screen.findByText('Holiday fund');

    // A refetch lands while the create is in flight and replaces the list. The optimistic
    // row was at index 0; a real account is there now.
    store.dispatch(api.util.updateQueryData('listAccounts', undefined, (draft) => {
      draft.length = 0;
      draft.push({
        clientRef: 'server-1', id: 'server-1', accountNumber: '99-0001-0000111-030',
        customerName: 'Ada Lovelace', nickname: 'House deposit', displayName: 'House deposit',
        openedAt: '2026-09-14T09:00:00Z', pending: false,
      });
    }));

    releaseCreate({ status: 503, body: { title: 'Temporarily unavailable', status: 503 } });

    // patch.undo() replays "remove index 0" and would delete the real account. Removing
    // by clientRef finds nothing to remove, which is correct: the optimistic row is
    // already gone.
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });
    expect(screen.getByText('House deposit')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  });

  it('reuses the idempotency key when retrying, so a retry cannot open a second account',
    async () => {
      const { fetchMock, releaseCreate } = controllableFetch();
      vi.stubGlobal('fetch', fetchMock);

      render(<Provider store={store}><Theme><Accounts /></Theme></Provider>);
      await screen.findByText(/no accounts yet/i);

      await userEvent.type(screen.getByRole('textbox'), 'Holiday fund');
      await userEvent.click(screen.getByRole('button', { name: /open account/i }));

      releaseCreate({
        status: 503,
        body: { title: 'Temporarily unavailable', status: 503, retryable: true },
      });

      const retry = await screen.findByRole('button', { name: /try again/i });
      await userEvent.click(retry);

      await waitFor(() => {
        const creates = fetchMock.mock.calls.filter(isCreate);
        expect(creates.length).toBe(2);
        // The same key both times. Without this, "try again" after a timeout is
        // indistinguishable from "open another account", and the customer silently
        // loses one of their five slots to a network blip.
        expect(idempotencyKeyOf(creates[0])).toBeTruthy();
        expect(idempotencyKeyOf(creates[0])).toBe(idempotencyKeyOf(creates[1]));
      });
    });
});

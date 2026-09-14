import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';
import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { signedOut } from '../auth/authSlice';
import type { RootState } from '../store';
import type { Account, AccountRow, Problem } from './types';

/**
 * The browser does not mint a correlation id, deliberately.
 *
 * <p>The trace belongs to the gateway. It sees requests this application never will —
 * ones rejected at the edge for auth, rate limiting or a bad route — so an id minted
 * here would cover only the subset that got through, and could not be joined to the
 * rest. A browser is also an untrusted client: a header it sets is attacker-controlled
 * input on its way into a log file, which is why the server validates and replaces it
 * rather than trusting it.
 *
 * <p>In this stack nginx is the gateway and sets it from {@code $request_id}. The id
 * still reaches the user, on the problem document, so it can be quoted to support.
 *
 * <p>What a browser would legitimately generate is a separate client request id for its
 * own telemetry, carried alongside the trace rather than overwriting it, so front-end
 * error reports can be joined to server-side traces. Not built - there is no RUM here
 * to join to.
 */
/**
 * Relative in the browser, because nginx proxies /api on the same origin.
 *
 * <p>Overridable because outside a browser there is no origin to resolve a relative
 * path against — `new Request('/api/accounts')` simply fails — so tests point it at an
 * absolute one. The indirection is not test scaffolding leaking into production code;
 * a deployment that served the SPA from a CDN and the API from another host would set
 * this too.
 */
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api';

const baseQuery = fetchBaseQuery({
  baseUrl: API_BASE_URL,
  prepareHeaders: (headers, { getState }) => {
    const token = (getState() as RootState).auth.accessToken;
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    return headers;
  },
});

/**
 * A 401 means the token expired mid-flight.
 *
 * <p>In a system with refresh tokens this is where the token would be refreshed and the
 * request replayed — safe to replay precisely because a create carries an
 * `Idempotency-Key`, so a replayed POST returns the original account rather than opening
 * a second one. Refresh is deliberately not built (see DECISIONS.md); what is built is
 * the part that makes it safe, and the seam it would slot into.
 */
const baseQueryWithAuthHandling: BaseQueryFn<
  string | FetchArgs,
  unknown,
  FetchBaseQueryError
> = async (args, api, extraOptions) => {
  const result = await baseQuery(args, api, extraOptions);
  if (result.error?.status === 401) {
    api.dispatch(signedOut());
  }
  return result;
};

export const api = createApi({
  reducerPath: 'api',
  baseQuery: baseQueryWithAuthHandling,
  tagTypes: ['Account'],

  // Refetch when the connection comes back: the list may have moved on while the
  // browser was offline, and that is a real reason to re-ask.
  refetchOnReconnect: true,

  // Not on window focus. A customer glancing at another tab is not a reason to
  // re-query their accounts, and on a screen showing money, a list that quietly
  // rebuilds itself every time you look away is unsettling rather than helpful.
  refetchOnFocus: false,
  endpoints: (builder) => ({
    signIn: builder.mutation<{ access_token: string; expires_in: number }, {
      email: string;
      password: string;
    }>({
      query: (credentials) => ({ url: '/auth/token', method: 'POST', body: credentials }),
    }),

    listAccounts: builder.query<AccountRow[], void>({
      query: () => '/accounts',
      // Server rows are keyed by their id; optimistic rows get a generated ref. The key
      // is stable across the create and its reconciliation, which is the transition that
      // would otherwise remount a row on screen. A later refetch rebuilds the list and
      // re-keys from the id, which is fine because every row is replaced at once.
      transformResponse: (accounts: Account[]): AccountRow[] =>
        accounts.map((account) => ({ ...account, clientRef: account.id })),
      providesTags: ['Account'],
    }),

    openAccount: builder.mutation<Account, {
      nickname: string | null;
      clientRef: string;
      idempotencyKey: string;
    }>({
      query: ({ nickname, idempotencyKey }) => ({
        url: '/accounts',
        method: 'POST',
        // Stable across retries of the same logical request. A retried create would
        // otherwise become a second account - and with a cap of five, the customer
        // would lose a slot to a network blip.
        headers: { 'Idempotency-Key': idempotencyKey },
        body: nickname ? { nickname } : {},
      }),

      /*
       * Deliberately no invalidatesTags.
       *
       * Invalidating would refetch the list, rebuild every row from the server, and
       * replace the optimistic row's clientRef with its id - changing the React key of
       * a row that is already on screen and remounting it. The optimistic update below
       * already puts the row in exactly the state a refetch would produce, so the
       * refetch buys nothing and costs a visible flicker.
       */

      async onQueryStarted({ clientRef, nickname }, { dispatch, queryFulfilled }) {
        // 1. Optimistic: show the row immediately, marked as not yet confirmed.
        dispatch(
          api.util.updateQueryData('listAccounts', undefined, (draft) => {
            draft.push({ clientRef, nickname, pending: true });
          }),
        );

        try {
          const { data } = await queryFulfilled;

          // 2. Reconcile. An upsert, not a merge-if-present: the optimistic row may be
          //    gone by now - a reconnect refetch rebuilds the list, and a create
          //    submitted while the first list GET is still in flight has no row to find.
          //    Because this mutation deliberately does not invalidate, a no-op here
          //    means a committed account that never appears. Matching on the server id
          //    as well as the ref matters too: a refetch may already have brought the
          //    row back, and pushing again would duplicate it.
          dispatch(
            api.util.updateQueryData('listAccounts', undefined, (draft) => {
              const row = draft.find(
                (candidate) => candidate.clientRef === clientRef || candidate.id === data.id,
              );
              if (row) {
                // The row's own ref, not the one passed in: a row a refetch brought back
                // is keyed by its id, and re-keying it here would remount it.
                Object.assign(row, data, { clientRef: row.clientRef, pending: false });
              } else {
                draft.push({ ...data, clientRef, pending: false });
              }
            }),
          );
        } catch {
          // 3. Roll back by identity, not with patch.undo(). undo() replays Immer's
          //    inverse patch, which is "remove index N" - and if the list changed in the
          //    meantime, say a reconnect refetch landed, that deletes whatever now sits
          //    at N. Removing the row we actually added cannot take someone else's with
          //    it.
          //
          //    Never leave a row the server rejected on screen: a customer who believes
          //    they hold six accounts is worse off than one who saw an error.
          dispatch(
            api.util.updateQueryData('listAccounts', undefined, (draft) => {
              const index = draft.findIndex((candidate) => candidate.clientRef === clientRef);
              if (index >= 0) {
                draft.splice(index, 1);
              }
            }),
          );
        }
      },
    }),
  }),
});

export const { useSignInMutation, useListAccountsQuery, useOpenAccountMutation } = api;

/** Turns an RTK Query error into the problem document the API actually sent. */
export function asProblem(error: unknown): Problem {
  const candidate = error as { data?: Problem; status?: number | string };
  if (candidate?.data && typeof candidate.data === 'object') {
    return candidate.data;
  }
  // A transport failure, not an application one - there is no problem document because
  // the request never reached anything that could write one.
  return {
    title: 'Could not reach the service',
    detail: 'The request did not complete. Please check your connection and try again.',
    retryable: true,
  };
}

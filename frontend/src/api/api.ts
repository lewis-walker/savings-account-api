import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';
import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { signedOut } from '../auth/authSlice';
import type { RootState } from '../store';
import type { Account, AccountRow, Problem } from './types';

/**
 * Relative, because nginx serves the app and proxies /api on the same origin.
 * Overridable because outside a browser there is no origin for a relative path to
 * resolve against.
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
 * Where a refresh-and-replay would attach. Replay is safe because a create carries an
 * Idempotency-Key; refresh itself is out of scope, see DECISIONS.md.
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

  refetchOnReconnect: true,

  // Not on focus: we make refresh a deliberate decision.
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
      // Every row carries a clientRef: an optimistic one has no id to be keyed by, and
      // the key must survive the id arriving.
      transformResponse: (accounts: Account[]): AccountRow[] =>
        accounts.map((account) => ({ ...account, clientRef: account.id, pending: false })),
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
        headers: { 'Idempotency-Key': idempotencyKey },
        body: nickname ? { nickname } : {},
      }),

      // No invalidatesTags: a refetch would re-key the optimistic row from its id and
      // remount it, to rebuild what the reconcile below already produces.

      async onQueryStarted({ clientRef, nickname }, { dispatch, queryFulfilled }) {
        dispatch(
          api.util.updateQueryData('listAccounts', undefined, (draft) => {
            draft.push({ clientRef, nickname, pending: true });
          }),
        );

        try {
          const { data } = await queryFulfilled;

          // An upsert: the optimistic row may be gone, and since nothing invalidates, a
          // no-op here is a committed account that never appears.
          dispatch(
            api.util.updateQueryData('listAccounts', undefined, (draft) => {
              const at = draft.findIndex(
                (candidate) =>
                  candidate.clientRef === clientRef ||
                  (!candidate.pending && candidate.id === data.id),
              );
              // Replaced, not merged: this is a pending row becoming an opened one, which
              // is a different member of the union rather than the same one with more
              // fields. The ref carried over is the row's own - re-keying remounts it.
              if (at >= 0) {
                draft[at] = { ...data, clientRef: draft[at].clientRef, pending: false };
              } else {
                draft.push({ ...data, clientRef, pending: false });
              }
            }),
          );
        } catch {
          // By identity, not patchResult.undo(), which replays Immer's inverse patch -
          // "remove index N" - and takes whatever now sits at N if the list has moved.
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
  // No problem document: the request never reached anything that could write one.
  return {
    title: 'Could not reach the service',
    detail: 'The request did not complete. Please check your connection and try again.',
    retryable: true,
  };
}

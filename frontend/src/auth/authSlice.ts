import { createSlice, type PayloadAction } from '@reduxjs/toolkit';

type AuthState = {
  accessToken: string | null;
  email: string | null;
}

/**
 * The access token lives here, in memory, and nowhere else.
 *
 * <p>Anything in `localStorage` is readable by any script running on the page
 * Memory is cleared when the tab closes and is not reachable from an injected script in the same way.
 *
 * <p>The cost is that a page refresh signs you out, because the token is
 * gone. In production there would bea refresh token in an `httpOnly`, `SameSite` cookie —
 * which scripts can't read — used to mint a new access token silently on load.
 * That is described in DECISIONS.md rather than built, and the 401 handling in the API
 * layer is where it would attach.
 */
const initialState: AuthState = { accessToken: null, email: null };

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    signedIn: (state, action: PayloadAction<{ accessToken: string; email: string }>) => {
      state.accessToken = action.payload.accessToken;
      state.email = action.payload.email;
    },
    signedOut: (state) => {
      state.accessToken = null;
      state.email = null;
    },
  },
});

export const { signedIn, signedOut } = authSlice.actions;
export default authSlice.reducer;

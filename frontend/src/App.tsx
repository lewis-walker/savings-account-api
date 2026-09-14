import { useDispatch, useSelector } from 'react-redux';
import { api } from './api/api';
import { signedOut } from './auth/authSlice';
import type { RootState } from './store';
import SignIn from './components/SignIn';
import Accounts from './components/Accounts';

export default function App() {
  const signedIn = useSelector((state: RootState) => state.auth.accessToken !== null);

  return (
    <div className="app">
      <header className="masthead">
        <div className="brand">
          <span className="mark" aria-hidden="true" />
          <span>Savings</span>
        </div>
        {signedIn && <SignedInAs />}
      </header>
      <main>{signedIn ? <Accounts /> : <SignIn />}</main>
    </div>
  );
}

function SignedInAs() {
  const email = useSelector((state: RootState) => state.auth.email);
  const dispatch = useDispatch();

  return (
    <span className="who">
      {email}
      <button
        type="button"
        className="linkish"
        onClick={() => {
          // Both, and in this order. Clearing the token without clearing the cache
          // leaves the previous customer's accounts sitting in memory for the next
          // person to sign in on this device.
          dispatch(api.util.resetApiState());
          dispatch(signedOut());
        }}
      >
        Sign out
      </button>
    </span>
  );
}

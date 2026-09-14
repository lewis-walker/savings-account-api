import { useDispatch, useSelector } from 'react-redux';
import { api } from './api/api';
import { signedOut } from './auth/authSlice';
import type { RootState } from './store';
import styles from './App.module.css';
import SignIn from './components/SignIn';
import Accounts from './components/Accounts';

export default function App() {
  const signedIn = useSelector((state: RootState) => state.auth.accessToken !== null);

  return (
    <div className={styles.app}>
      <header className={styles.masthead}>
        <div className={styles.brand}>
          <span className={styles.mark} aria-hidden="true" />
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
    <span className={styles.who}>
      {email}
      <button
        type="button"
        className={styles.signOut}
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

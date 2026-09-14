import { useSelector } from 'react-redux';
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
  return <span className="who">{email}</span>;
}

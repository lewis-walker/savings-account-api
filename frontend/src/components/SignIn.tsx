import { useState, type FormEvent } from 'react';
import { useDispatch } from 'react-redux';
import { api, asProblem, useSignInMutation } from '../api/api';
import { signedIn } from '../auth/authSlice';
import Problem from './Problem';

export default function SignIn() {
  const [email, setEmail] = useState('ada@example.test');
  const [password, setPassword] = useState('demo-password');
  const [signIn, { isLoading, error }] = useSignInMutation();
  const dispatch = useDispatch();

  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      const result = await signIn({ email, password }).unwrap();
      // Clear the cache before the identity changes. Without this, signing in as a
      // second customer serves the first customer's accounts from cache - the token is
      // new, but RTK Query has no reason to refetch a query whose arguments have not
      // changed. Keyed on sign-in rather than sign-out because sign-out is not the only
      // way an identity changes.
      dispatch(api.util.resetApiState());
      dispatch(signedIn({ accessToken: result.access_token, email }));
    } catch {
      // Rendered below from the problem document; nothing useful to do here.
    }
  }

  return (
    <div className="card narrow">
      <h1>Sign in</h1>
      <form onSubmit={submit}>
        <label>
          Email
          <input
            type="email"
            value={email}
            autoComplete="username"
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {error && <Problem problem={asProblem(error)} />}
        <button type="submit" disabled={isLoading}>
          {isLoading ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p className="hint">
        Demo customers, all with password <code>demo-password</code>:
        <br />
        <code>ada@example.test</code> · <code>grace@example.test</code>
        <br />
        <code>alan@example.test</code> — due diligence incomplete, so account opening is
        refused. That path is reachable on purpose.
      </p>
    </div>
  );
}

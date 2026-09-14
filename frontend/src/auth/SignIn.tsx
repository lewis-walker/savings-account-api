import { Box, Button, Card, Code, Flex, Heading, Text, TextField } from '@radix-ui/themes';
import { useState, type SubmitEvent } from 'react';
import { useDispatch } from 'react-redux';
import { api, asProblem, useSignInMutation } from '../api/api';
import Problem from '../ui/Problem';
import { signedIn } from './authSlice';

export default function SignIn() {
  const [email, setEmail] = useState('ada@example.test');
  const [password, setPassword] = useState('demo-password');
  const [signIn, { isLoading, error }] = useSignInMutation();
  const dispatch = useDispatch();

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const result = await signIn({ email, password }).unwrap();
      // Clear the cache before the identity changes. Without this, signing in as a
      // second customer serves the first customer's accounts from cache.
      dispatch(api.util.resetApiState());
      dispatch(signedIn({ accessToken: result.access_token, email }));
    } catch {
      // Rendered below from the problem document; nothing useful to do here.
    }
  }

  return (
    <Box maxWidth="420px" mx="auto">
      <Card size="3">
        <Heading size="4" mb="4">Sign in</Heading>
        <form onSubmit={submit}>
          <Flex direction="column" gap="3">
            <label>
              <Text as="div" size="2" weight="medium" mb="1">Email</Text>
              <TextField.Root
                type="email"
                value={email}
                autoComplete="username"
                onChange={(event) => setEmail(event.target.value)}
                required
              />
            </label>
            <label>
              <Text as="div" size="2" weight="medium" mb="1">Password</Text>
              <TextField.Root
                type="password"
                value={password}
                autoComplete="current-password"
                onChange={(event) => setPassword(event.target.value)}
                required
              />
            </label>
            {error && <Problem problem={asProblem(error)} />}
            <Button type="submit" loading={isLoading} style={{ alignSelf: 'flex-start' }}>
              Sign in
            </Button>
          </Flex>
        </form>
        <Text as="p" size="1" color="gray" mt="4">
          Demo customers, all with password <Code size="1">demo-password</Code>:{' '}
          <Code size="1">ada@example.test</Code> · <Code size="1">grace@example.test</Code> ·{' '}
          <Code size="1">alan@example.test</Code> — due diligence incomplete, so account
          opening is refused. That path is reachable on purpose.
        </Text>
      </Card>
    </Box>
  );
}

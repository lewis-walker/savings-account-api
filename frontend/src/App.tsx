import { Box, Button, Container, Flex, Text, Theme } from '@radix-ui/themes';
import { useDispatch, useSelector } from 'react-redux';
import { api } from './api/api';
import Accounts from './accounts/Accounts';
import SignIn from './auth/SignIn';
import { signedOut } from './auth/authSlice';
import type { RootState } from './store';
import { useAppearance } from './ui/useAppearance';

export default function App() {
  const signedIn = useSelector((state: RootState) => state.auth.accessToken !== null);
  const { appearance, toggle } = useAppearance();

  return (
    // Green reads as money without being a brand nobody has seen.
    <Theme appearance={appearance} accentColor="green" grayColor="slate" radius="medium">
      {/* A tinted page, so the white panels read as panels. */}
      <Box minHeight="100vh" style={{ background: 'var(--gray-2)' }}>
        <Box px="5" py="3" style={{ background: 'var(--color-panel-solid)', borderBottom: '1px solid var(--gray-a5)' }}>
          <Flex align="center" gap="3">
            <Box width="22px" height="22px" style={{ background: 'var(--accent-9)', borderRadius: 'var(--radius-2)' }} />
            <Text weight="bold">Savings</Text>
            <Flex align="center" gap="3" ml="auto">
              {signedIn && <SignedInAs />}
              <Button variant="ghost" size="2" onClick={toggle} aria-label="Switch appearance">
                {appearance === 'dark' ? 'Light' : 'Dark'}
              </Button>
            </Flex>
          </Flex>
        </Box>
        <Container size="2" px="5" py="6">
          {signedIn ? <Accounts /> : <SignIn />}
        </Container>
      </Box>
    </Theme>
  );
}

function SignedInAs() {
  const email = useSelector((state: RootState) => state.auth.email);
  const dispatch = useDispatch();

  return (
    <Flex align="center" gap="3">
      <Text size="2" color="gray">{email}</Text>
      <Button
        variant="ghost"
        size="2"
        onClick={() => {
          // Both, and in this order. Clearing the token without clearing the cache
          // leaves the previous customer's accounts in memory for whoever signs in next.
          dispatch(api.util.resetApiState());
          dispatch(signedOut());
        }}
      >
        Sign out
      </Button>
    </Flex>
  );
}

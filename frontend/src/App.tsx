import { MoonIcon, SunIcon } from '@radix-ui/react-icons';
import { Box, Button, Flex, IconButton, Text, Theme, Tooltip } from '@radix-ui/themes';
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
      <Flex direction="column" minHeight="100vh">
        <Box px="5" py="3" style={{ background: 'var(--color-panel-solid)', borderBottom: '1px solid var(--gray-a5)' }}>
          <Flex align="center" gap="3">
            <Box width="22px" height="22px" style={{ background: 'var(--accent-9)', borderRadius: 'var(--radius-2)' }} />
            <Text weight="bold">Savings</Text>
            <Flex align="center" gap="3" ml="auto">
              {signedIn && <SignedInAs />}
              <Tooltip content={appearance === 'dark' ? 'Switch to light' : 'Switch to dark'}>
                <IconButton
                  variant="ghost"
                  size="2"
                  onClick={toggle}
                  // No text, so the control needs naming for anyone not looking at it.
                  aria-label={appearance === 'dark' ? 'Switch to light appearance' : 'Switch to dark appearance'}
                >
                  {appearance === 'dark' ? <SunIcon /> : <MoonIcon />}
                </IconButton>
              </Tooltip>
            </Flex>
          </Flex>
        </Box>
        {/* Grows to fill what the masthead leaves, so a child can pin itself to the
            foot of the page with mt="auto". */}
        <Flex direction="column" flexGrow="1" width="100%" maxWidth="704px" mx="auto" px="5" py="6">
          {signedIn ? <Accounts /> : <SignIn />}
        </Flex>
      </Flex>
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

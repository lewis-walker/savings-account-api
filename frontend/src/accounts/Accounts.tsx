import { Badge, Box, Button, Flex, Heading, Reset, Text, TextField } from '@radix-ui/themes';
import { asProblem, useListAccountsQuery } from '../api/api';
import Problem from '../ui/Problem';
import AccountRow from './AccountRow';
import { useOpenAccount } from './useOpenAccount';

const MAXIMUM_ACCOUNTS = 5;

export default function Accounts() {
  const { data: accounts = [], isLoading, error: listError } = useListAccountsQuery();
  const { nickname, setNickname, submit, retry, failure, opening } = useOpenAccount();

  const confirmed = accounts.filter((account) => !account.pending).length;
  const full = confirmed >= MAXIMUM_ACCOUNTS;

  return (
    <Flex direction="column" gap="7" flexGrow="1">
      <Box>
        <Flex align="baseline" gap="3" mb="3">
          <Heading size="4">Your savings accounts</Heading>
          {/* "1 of 5" alone reads as paging through five accounts. It is a limit. */}
          <Badge color={full ? 'red' : 'gray'} ml="auto">
            {confirmed} of {MAXIMUM_ACCOUNTS} allowed
          </Badge>
        </Flex>

        {isLoading && <Text color="gray">Loading…</Text>}
        {listError && <Problem problem={asProblem(listError)} />}
        {!isLoading && accounts.length === 0 && (
          <Text color="gray">No accounts yet. Open your first below.</Text>
        )}

        {/* A real list, so it is announced as one with its length. Reset takes the
            browser's bullets and padding off it. */}
        <Flex asChild direction="column" gap="2">
          <Reset>
            <ul>
              {accounts.map((account) => (
                // Keyed by clientRef, never by id: a pending row has no id yet, and the
                // key must not change when one arrives.
                <AccountRow key={account.clientRef} account={account} />
              ))}
            </ul>
          </Reset>
        </Flex>
      </Box>

      {/* Hidden at the limit rather than explained: the badge above already says
          five of five, and a form that cannot be submitted is furniture. mt="auto"
          puts it at the foot of the page rather than under the last row. */}
      {!full && (
        <Box mt="auto">
          <Heading size="3" mb="3">Open another account</Heading>
          <form onSubmit={submit}>
            <Flex direction="column" gap="3">
              <label>
                <Text as="div" size="2" weight="medium" mb="1">
                  Nickname <Text color="gray" weight="regular">optional, 5–30 characters</Text>
                </Text>
                <TextField.Root
                  value={nickname}
                  onChange={(event) => setNickname(event.target.value)}
                  placeholder="Holiday fund"
                  maxLength={60}
                />
              </label>
              <Button type="submit" loading={opening} style={{ alignSelf: 'flex-start' }}>
                Open account
              </Button>
            </Flex>
          </form>

          {failure && (
            <Box mt="4">
              <Problem problem={asProblem(failure.error)} />
              {asProblem(failure.error).retryable && (
                <Button variant="soft" mt="3" onClick={retry}>Try again</Button>
              )}
            </Box>
          )}
        </Box>
      )}
    </Flex>
  );
}

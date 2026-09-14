import { Badge, Code, Flex, Text } from '@radix-ui/themes';
import type { AccountRow as Row } from '../api/types';

const OPENED_ON = new Intl.DateTimeFormat('en-NZ', {
  day: 'numeric', month: 'short', year: 'numeric',
});

export default function AccountRow({ account }: { account: Row }) {
  return (
    // A rule above every row, so the first one doubles as the list's header line and
    // nothing needs a last-child rule.
    <li style={{ borderTop: '1px solid var(--gray-a6)' }}>
      <Flex align="center" gap="3" py="3">
        <Flex direction="column" gap="1" minWidth="0">
          {/* The server names an unnamed account, so every client names it the same.
              A pending row has no sequence yet, so it shows what was typed. */}
          <Text weight="medium">
            {account.pending ? account.nickname ?? 'Savings account' : account.displayName}
          </Text>
          {/* The API allocates the number, so a pending row has none. A placeholder
              would be inventing a banking identifier. */}
          <Code size="1" color="gray" variant="ghost">
            {account.pending ? '—' : account.accountNumber}
          </Code>
        </Flex>
        {account.pending ? (
          <Badge color="amber" ml="auto">Opening…</Badge>
        ) : (
          <Text size="2" color="gray" ml="auto" wrap="nowrap">
            {OPENED_ON.format(new Date(account.openedAt))}
          </Text>
        )}
      </Flex>
    </li>
  );
}

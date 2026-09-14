import { Badge, Card, Code, Flex, Text } from '@radix-ui/themes';
import type { AccountRow as Row } from '../api/types';

const OPENED_ON = new Intl.DateTimeFormat('en-NZ', {
  day: 'numeric', month: 'short', year: 'numeric',
});

export default function AccountRow({ account }: { account: Row }) {
  return (
    <li>
      <Card variant={account.pending ? 'classic' : 'surface'}>
        <Flex align="center" gap="3">
          <Flex direction="column" gap="1" minWidth="0">
            <Text weight="medium">{account.nickname ?? 'Savings account'}</Text>
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
      </Card>
    </li>
  );
}

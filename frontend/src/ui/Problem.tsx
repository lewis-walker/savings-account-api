import { Callout, Code, Flex, Text } from '@radix-ui/themes';
import type { Problem as ProblemDetail } from '../api/types';

/**
 * Renders an RFC 9457 problem document.
 *
 * <p>One component for every failure, because the API returns one shape for every
 * failure: a validation error, a refused nickname, a full customer and a database outage
 * all arrive here and render without a special case.
 *
 * <p>The correlation id is shown deliberately. It is on this response and on every log
 * line the request produced, so a customer reading it out is the fastest route from
 * "it did not work" to the exact failure.
 */
export default function Problem({ problem }: { problem: ProblemDetail }) {
  return (
    <Callout.Root color="red" role="alert">
      <Callout.Text>
        <Flex direction="column" gap="1">
          <Text weight="bold">{problem.title ?? 'Something went wrong'}</Text>
          {problem.detail && <Text>{problem.detail}</Text>}
          {problem.errors?.map((error) => (
            <Text key={error.field} size="2">
              <Code>{error.field}</Code> {error.message}
            </Text>
          ))}
          {problem.correlationId && (
            <Text size="1" color="gray">
              Reference <Code size="1">{problem.correlationId}</Code>
            </Text>
          )}
        </Flex>
      </Callout.Text>
    </Callout.Root>
  );
}

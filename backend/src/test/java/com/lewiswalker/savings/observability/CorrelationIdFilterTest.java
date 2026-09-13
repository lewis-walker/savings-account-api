package com.lewiswalker.savings.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("a well-formed inbound id is kept, so a trace spans services")
    void keepsAcceptableInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "5f7c1b90-3f2a-4c11-9d33-6b2e0a1c77de");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .isEqualTo("5f7c1b90-3f2a-4c11-9d33-6b2e0a1c77de");
    }

    @ParameterizedTest
    @DisplayName("anything else is replaced, not sanitised — CWE-117")
    @ValueSource(strings = {
            "abc\r\nINFO  Transfer approved by supervisor",   // forged log entry
            "abc\ndef",
            "id with spaces",
            "../../etc/passwd",
            "<script>alert(1)</script>",
            "0123456789012345678901234567890123456789012345678901234567890123456789",  // too long
            "",
    })
    void replacesUnacceptableInboundId(String supplied) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, supplied);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String issued = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(issued).isNotEqualTo(supplied);
        assertThat(issued).matches("[A-Za-z0-9-]{1,64}");
        assertThat(issued).doesNotContain("\r", "\n");
    }

    @Test
    @DisplayName("an id is minted when the caller supplies none")
    void mintsWhenAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("[A-Za-z0-9-]{1,64}");
    }

    @Test
    @DisplayName("the id is in the MDC during the request and gone afterwards")
    void mdcIsPopulatedThenCleared() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "trace-abc123");
        String[] seenDuringChain = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                seenDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertThat(seenDuringChain[0]).isEqualTo("trace-abc123");
        // Containers pool threads. A leaked value does not mean a missing correlation
        // id on the next request, it means a wrong one.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("the MDC is cleared even when the request blows up")
    void mdcIsClearedOnFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "trace-abc123");

        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("handler exploded");
            });
        } catch (Exception expected) {
            // the point is what happens in the finally block
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}

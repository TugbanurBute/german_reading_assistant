package com.germanreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String requestIdDuring(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));
        return seen.get();
    }

    @Test
    void createsAnIdWhenNoneIsSentAndReturnsIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String id = requestIdDuring(new MockHttpServletRequest(), response);

        assertThat(id).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
    }

    @Test
    void keepsTheIdSentByTheProxy() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "9f2c1d0e8a7b6c5d4e3f2a1b0c9d8e7f");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(requestIdDuring(request, response)).isEqualTo("9f2c1d0e8a7b6c5d4e3f2a1b0c9d8e7f");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("9f2c1d0e8a7b6c5d4e3f2a1b0c9d8e7f");
    }

    @Test
    void replacesAnIdThatIsTooLongOrContainsOddCharacters() throws Exception {
        for (String bad : new String[] {"x".repeat(65), "abc\ndef", "<script>", "a b"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER, bad);

            assertThat(requestIdDuring(request, new MockHttpServletResponse())).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void clearsTheLogContextAfterTheRequest() throws Exception {
        requestIdDuring(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}

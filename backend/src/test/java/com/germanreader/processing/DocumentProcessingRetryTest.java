package com.germanreader.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;

class DocumentProcessingRetryTest {

    private final DocumentProcessor processor = mock(DocumentProcessor.class);
    private final StatelessRetryOperationsInterceptor retry = DocumentProcessingConfig.retry(processor, 1, 1);
    private final Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());

    private MethodInvocation listenerCall(AtomicInteger calls, int failures) {
        return new MethodInvocation() {
            @Override
            public Object proceed() {
                if (calls.incrementAndGet() <= failures) {
                    throw new IllegalStateException("transient failure " + calls.get());
                }
                return null;
            }

            @Override
            public Object[] getArguments() {
                return new Object[] {null, message};
            }

            @Override
            public Method getMethod() {
                return null;
            }

            @Override
            public Object getThis() {
                return null;
            }

            @Override
            public AccessibleObject getStaticPart() {
                return null;
            }
        };
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds() throws Throwable {
        AtomicInteger calls = new AtomicInteger();

        retry.invoke(listenerCall(calls, 2));

        assertThat(calls).hasValue(3);
        verify(processor, never()).markFailed(message, null);
    }

    @Test
    void afterTheLastAttemptTheDocumentIsMarkedFailedAndTheMessageRejectedWithoutRequeue() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.invoke(listenerCall(calls, Integer.MAX_VALUE)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(hasCause(e, AmqpRejectAndDontRequeueException.class)).isTrue());

        assertThat(calls).hasValue(DocumentProcessingConfig.MAX_ATTEMPTS);
        verify(processor).markFailed(org.mockito.ArgumentMatchers.eq(message), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectedMessagesAreDeadLetteredByTheQueue() {
        Queue queue = new DocumentProcessingConfig().documentProcessingQueue();

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", DocumentProcessingConfig.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", DocumentProcessingConfig.DEAD_LETTER_QUEUE);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}

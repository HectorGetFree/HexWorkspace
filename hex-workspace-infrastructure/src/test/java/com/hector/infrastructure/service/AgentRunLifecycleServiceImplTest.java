package com.hector.infrastructure.service;

import com.hector.domain.agent.adapter.port.IBrowserEventPublisher;
import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.infrastructure.dao.IAgentRunDao;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lifecycle 事件只有通过 WS 状态机 CAS 后才允许投递到浏览器。 */
class AgentRunLifecycleServiceImplTest {

    @Test
    void doesNotPublishStaleLifecycleEventWhoseCasWasRejected() {
        IAgentRunDao dao = mock(IAgentRunDao.class);
        IBrowserEventPublisher publisher = mock(IBrowserEventPublisher.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                    .accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(dao.insertLifecycleEvent(anyString(), anyString(), anyLong(), anyString())).thenReturn(1);
        when(dao.updateLifecycle(anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any())).thenReturn(0);

        AgentRunLifecycleServiceImpl service = new AgentRunLifecycleServiceImpl();
        ReflectionTestUtils.setField(service, "dao", dao);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(service, "browserEventPublisher", publisher);

        service.project(new LifecycleEventVO(
                "event-stale", 1L, "run-1", "conversation-1", "AgentRunSucceeded",
                OffsetDateTime.now(), "command-1", "{}"));

        verify(publisher, never()).publishLifecycle(any());
        verify(publisher, never()).publishEnd(anyString());
    }
}

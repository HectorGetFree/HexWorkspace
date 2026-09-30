package com.hector.domain.agent;

import com.hector.domain.agent.adapter.port.IRuntimeCommandPort;
import com.hector.domain.agent.adapter.port.RuntimeCommandUnknownException;
import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.model.entity.WorkspaceConversationEntity;
import com.hector.domain.agent.model.valobj.AgentRunStatusVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandRequestVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandResultVO;
import com.hector.domain.agent.repository.IAgentRunRepository;
import com.hector.domain.agent.repository.IWorkspaceConversationRepository;
import com.hector.domain.agent.service.impl.AgentRunServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AgentRun 与 Runtime Command 编排测试。 */
class AgentRunServiceImplTest {

    @Test
    void acceptedStartKeepsAgentRunPendingAndUsesStableCommandId() {
        IWorkspaceConversationRepository conversationRepository = mock(IWorkspaceConversationRepository.class);
        IAgentRunRepository runRepository = mock(IAgentRunRepository.class);
        IRuntimeCommandPort runtimeCommandPort = mock(IRuntimeCommandPort.class);
        AgentRunServiceImpl service = service(conversationRepository, runRepository, runtimeCommandPort);
        WorkspaceConversationEntity conversation = conversation();
        when(conversationRepository.findByIdForUpdate("C1")).thenReturn(Optional.of(conversation));
        when(runRepository.findNonTerminalByConversationId("C1")).thenReturn(Optional.empty());
        AgentRunEntity[] created = new AgentRunEntity[1];
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            created[0] = invocation.getArgument(0);
            return created[0];
        });
        when(runRepository.findByRunId(any())).thenAnswer(invocation -> Optional.ofNullable(created[0]));
        when(runtimeCommandPort.send(any())).thenAnswer(invocation -> {
            RuntimeCommandRequestVO request = invocation.getArgument(0);
            return new RuntimeCommandResultVO(request.commandId(), request.runId(),
                    RuntimeCommandResultVO.Status.ACCEPTED, null, null);
        });

        AgentRunEntity result = service.startAndDispatch("C1", 7L, "执行任务", "{}", "[]");

        assertEquals(AgentRunStatusVO.PENDING, result.getStatus());
        ArgumentCaptor<RuntimeCommandRequestVO> request = ArgumentCaptor.forClass(RuntimeCommandRequestVO.class);
        verify(runtimeCommandPort).send(request.capture());
        assertEquals("start_" + result.getRunId(), request.getValue().commandId());
        assertTrue(request.getValue().payloadJson().contains("\"permissionMode\":\"default\""));
    }

    @Test
    void runtimeTimeoutKeepsCreatedPendingRunAndDoesNotMarkFailure() {
        IWorkspaceConversationRepository conversationRepository = mock(IWorkspaceConversationRepository.class);
        IAgentRunRepository runRepository = mock(IAgentRunRepository.class);
        IRuntimeCommandPort runtimeCommandPort = mock(IRuntimeCommandPort.class);
        AgentRunServiceImpl service = service(conversationRepository, runRepository, runtimeCommandPort);
        when(conversationRepository.findByIdForUpdate("C1")).thenReturn(Optional.of(conversation()));
        when(runRepository.findNonTerminalByConversationId("C1")).thenReturn(Optional.empty());
        AgentRunEntity[] created = new AgentRunEntity[1];
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            created[0] = invocation.getArgument(0);
            return created[0];
        });
        when(runRepository.findByRunId(any())).thenAnswer(invocation -> Optional.ofNullable(created[0]));
        when(runtimeCommandPort.send(any())).thenThrow(new RuntimeCommandUnknownException("timeout"));

        assertThrows(RuntimeCommandUnknownException.class,
                () -> service.startAndDispatch("C1", 7L, "执行任务", "{}", "[]"));

        verify(runRepository, never()).markRejected(any(), any(), any());
    }

    @Test
    void retryAfterTimeoutReusesPendingRunAndStableCommandId() {
        IWorkspaceConversationRepository conversationRepository = mock(IWorkspaceConversationRepository.class);
        IAgentRunRepository runRepository = mock(IAgentRunRepository.class);
        IRuntimeCommandPort runtimeCommandPort = mock(IRuntimeCommandPort.class);
        AgentRunServiceImpl service = service(conversationRepository, runRepository, runtimeCommandPort);
        when(conversationRepository.findByIdForUpdate("C1")).thenReturn(Optional.of(conversation()));
        AgentRunEntity[] created = new AgentRunEntity[1];
        when(runRepository.findNonTerminalByConversationId("C1"))
                .thenAnswer(invocation -> Optional.ofNullable(created[0]));
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            created[0] = invocation.getArgument(0);
            return created[0];
        });
        when(runRepository.findByRunId(any())).thenAnswer(invocation -> Optional.ofNullable(created[0]));
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(runtimeCommandPort.send(any())).thenAnswer(invocation -> {
            RuntimeCommandRequestVO request = invocation.getArgument(0);
            if (firstCall.getAndSet(false)) {
                throw new RuntimeCommandUnknownException("timeout");
            }
            return new RuntimeCommandResultVO(request.commandId(), request.runId(),
                    RuntimeCommandResultVO.Status.ACCEPTED, null, null);
        });

        assertThrows(RuntimeCommandUnknownException.class,
                () -> service.startAndDispatch("C1", 7L, "执行任务", "{}", "[]"));
        AgentRunEntity retryResult = service.startAndDispatch("C1", 7L, "执行任务", "{}", "[]");

        assertEquals(created[0].getRunId(), retryResult.getRunId());
        ArgumentCaptor<RuntimeCommandRequestVO> requests = ArgumentCaptor.forClass(RuntimeCommandRequestVO.class);
        verify(runtimeCommandPort, org.mockito.Mockito.times(2)).send(requests.capture());
        assertEquals(requests.getAllValues().get(0).commandId(), requests.getAllValues().get(1).commandId());
    }

    private AgentRunServiceImpl service(IWorkspaceConversationRepository conversationRepository,
                                        IAgentRunRepository runRepository,
                                        IRuntimeCommandPort runtimeCommandPort) {
        AgentRunServiceImpl service = new AgentRunServiceImpl();
        inject(service, "conversationRepository", conversationRepository);
        inject(service, "runRepository", runRepository);
        inject(service, "runtimeCommandPort", runtimeCommandPort);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        inject(service, "transactionTemplate", new TransactionTemplate(transactionManager));
        return service;
    }

    private WorkspaceConversationEntity conversation() {
        return WorkspaceConversationEntity.builder().conversationId("C1").workspaceId(10L)
                .userId(7L).title("测试").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private void inject(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}

package com.hector.infrastructure.service;

import com.hector.domain.agent.model.valobj.PermissionRequestVO;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.infrastructure.dao.IAgentRunDao;
import com.hector.infrastructure.dao.IAgentRunPermissionRequestDao;
import com.hector.infrastructure.dao.po.AgentRun;
import com.hector.infrastructure.dao.po.AgentRunPermissionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Permission 收据的幂等与决定状态机测试。 */
class AgentRunPermissionServiceImplTest {
    private final AtomicReference<AgentRunPermissionRequest> receipt = new AtomicReference<>();
    private AgentRunPermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        IAgentRunDao agentRunDao = mock(IAgentRunDao.class);
        IAgentRunPermissionRequestDao permissionDao = mock(IAgentRunPermissionRequestDao.class);
        when(agentRunDao.queryByRunId("run-1")).thenReturn(AgentRun.builder().runId("run-1").build());
        when(permissionDao.queryByRequestId("permission-1")).thenAnswer(invocation -> receipt.get());
        when(permissionDao.insertIgnore(any())).thenAnswer(invocation -> {
            AgentRunPermissionRequest candidate = invocation.getArgument(0);
            return receipt.compareAndSet(null, candidate) ? 1 : 0;
        });
        when(permissionDao.resolvePending(anyString(), anyString(), anyInt(), anyString(), any()))
                .thenAnswer(invocation -> {
                    AgentRunPermissionRequest current = receipt.get();
                    if (current == null || current.getStatus() != 0) return 0;
                    receipt.set(AgentRunPermissionRequest.builder()
                            .id(current.getId()).requestId(current.getRequestId()).runId(current.getRunId())
                            .toolName(current.getToolName()).description(current.getDescription())
                            .status(invocation.getArgument(2)).responseId(invocation.getArgument(3))
                            .createdAt(current.getCreatedAt()).resolvedAt(invocation.getArgument(4)).build());
                    return 1;
                });
        service = new AgentRunPermissionServiceImpl();
        ReflectionTestUtils.setField(service, "agentRunDao", agentRunDao);
        ReflectionTestUtils.setField(service, "permissionDao", permissionDao);
    }

    @Test
    void persistsOnlyOneReceiptForDuplicateCallback() {
        PermissionRequestVO request = request("run-1", "read_file", "读取文件");

        assertTrue(service.receive(request));
        assertFalse(service.receive(request));
        assertEquals("run-1", receipt.get().getRunId());
        assertEquals(0, receipt.get().getStatus());
    }

    @Test
    void rejectsCallbackWhoseRequestIdHasDifferentIdentity() {
        assertTrue(service.receive(request("run-1", "read_file", "读取文件")));

        assertThrows(BusinessException.class,
                () -> service.receive(request("run-2", "write_file", "写入文件")));
    }

    @Test
    void persistsAllowAndReusesOriginalResponseIdForRetry() {
        service.receive(request("run-1", "read_file", "读取文件"));

        var first = service.decide("run-1", "permission-1", "allow", "response-first");
        var retry = service.decide("run-1", "permission-1", "allow", "response-second");

        assertEquals("allow", first.decision());
        assertEquals("response-first", first.responseId());
        assertEquals("response-first", retry.responseId());
        assertEquals(1, receipt.get().getStatus());
    }

    @Test
    void persistsDenyButRejectsConflictingDecisionAndRunMismatch() {
        service.receive(request("run-1", "read_file", "读取文件"));

        assertEquals("deny", service.decide("run-1", "permission-1", "deny", "response-first").decision());
        assertThrows(BusinessException.class,
                () -> service.decide("run-1", "permission-1", "allow", "response-second"));
        assertThrows(BusinessException.class,
                () -> service.decide("run-2", "permission-1", "deny", "response-first"));
    }

    private PermissionRequestVO request(String runId, String toolName, String description) {
        return new PermissionRequestVO(runId, "permission-1", toolName, description, OffsetDateTime.now());
    }
}

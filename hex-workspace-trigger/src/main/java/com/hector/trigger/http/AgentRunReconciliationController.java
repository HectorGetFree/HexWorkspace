package com.hector.trigger.http;

import com.hector.domain.agent.service.IAgentRunReconciliationService;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 受用户认证保护的最小对账触发入口；不直接改变业务状态。 */
@RestController
public class AgentRunReconciliationController {
    @Resource
    private IAgentRunReconciliationService service;

    @PostMapping("/api/agent-runs/{runId}/reconcile")
    public ResponseEntity<Void> reconcile(
            @PathVariable String runId,
            @RequestParam(required = false) String commandId) {
        service.reconcile(runId, commandId, SecurityUserUtil.getCurrentUserId());
        return ResponseEntity.accepted().build();
    }
}

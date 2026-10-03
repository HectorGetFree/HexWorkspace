package com.hector.infrastructure.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.domain.agent.model.valobj.PermissionRequestVO;
import com.hector.domain.agent.adapter.port.IBrowserEventPublisher;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** authoritative fact 到浏览器的临时实时 fan-out；不承担持久化或状态权威职责。 */
@Component
public class BrowserEventHub implements IBrowserEventPublisher {
    private static final String END = "__BROWSER_EVENT_END__";
    private final Map<String, java.util.concurrent.CopyOnWriteArrayList<BlockingQueue<String>>> subscribers = new ConcurrentHashMap<>();

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public Subscription subscribe(String runId) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        subscribers.computeIfAbsent(runId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(queue);
        return new Subscription(runId, queue);
    }

    @Override
    public void publishLifecycle(LifecycleEventVO event) {
        try {
            JSONObject payload = JSON.parseObject(event.payloadJson() == null ? "{}" : event.payloadJson());
            JSONObject safePayload = new JSONObject();
            if (payload.containsKey("failureCode")) safePayload.put("failureCode", payload.getString("failureCode"));
            if (payload.containsKey("failureMessage")) safePayload.put("failureMessage", payload.getString("failureMessage"));
            safePayload.put("eventId", event.eventId());
            safePayload.put("sequence", event.sequence());
            safePayload.put("occurredAt", event.occurredAt());
            safePayload.put("status", statusOf(event.type()));
            String envelope = objectMapper.writeValueAsString(Map.of(
                    "eventId", event.eventId(),
                    "integrationRunId", event.workspaceRunId(), "streamId", "ws-lifecycle",
                    "sequence", event.sequence(), "type", "lifecycle", "createdAt", event.occurredAt(),
                    "payload", safePayload));
            publish(event.workspaceRunId(), "id: " + event.sequence() + "\nevent: lifecycle\ndata: " + envelope + "\n\n");
        } catch (Exception ignored) {
            // 浏览器实时投递失败不能影响 authoritative lifecycle transaction。
        }
    }

    @Override
    public void publishRuntime(String runId, String event) { publish(runId, event); }
    @Override
    public void publishPermission(PermissionRequestVO event) {
        try {
            String envelope = objectMapper.writeValueAsString(Map.of(
                    "eventId", "permission:" + event.workspaceRunId() + ":" + event.requestId(),
                    "integrationRunId", event.workspaceRunId(), "streamId", "ws-permission",
                    "sequence", 0, "type", "permission_requested", "createdAt", event.createdAt(),
                    "payload", Map.of("requestId", event.requestId(), "toolName", event.toolName(), "description", event.description())));
            publish(event.workspaceRunId(), "event: permission_requested\ndata: " + envelope + "\n\n");
        } catch (Exception ignored) { }
    }
    @Override
    public void publishEnd(String runId) { publish(runId, END); }

    private void publish(String runId, String event) {
        for (BlockingQueue<String> queue : subscribers.getOrDefault(runId, new java.util.concurrent.CopyOnWriteArrayList<>())) queue.offer(event);
    }

    private String statusOf(String type) {
        return switch (type) {
            case "AgentRunStarted", "AgentRunResumed", "AgentRunContinued" -> "RUNNING";
            case "AgentRunPaused" -> "PAUSED";
            case "AgentRunIncomplete" -> "INCOMPLETE";
            case "AgentRunSucceeded" -> "SUCCEEDED";
            case "AgentRunFailed" -> "FAILED";
            case "AgentRunCancelled" -> "CANCELLED";
            default -> type;
        };
    }

    public final class Subscription implements IBrowserEventPublisher.Subscription {
        private final String runId;
        private final BlockingQueue<String> queue;
        private Subscription(String runId, BlockingQueue<String> queue) { this.runId = runId; this.queue = queue; }
        public String take() throws InterruptedException { return queue.take(); }
        @Override public String poll(long timeout, TimeUnit unit) throws InterruptedException { return queue.poll(timeout, unit); }
        public boolean isEnd(String value) { return END.equals(value); }
        public void close() { var values = subscribers.get(runId); if (values != null) { values.remove(queue); if (values.isEmpty()) subscribers.remove(runId, values); } }
    }
}

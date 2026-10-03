package com.hector.domain.agent.adapter.port;

import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.domain.agent.model.valobj.PermissionRequestVO;

import java.util.concurrent.TimeUnit;

/** authoritative facts 到浏览器的临时实时投递端口。 */
public interface IBrowserEventPublisher {
    Subscription subscribe(String runId);
    void publishLifecycle(LifecycleEventVO event);
    void publishPermission(PermissionRequestVO event);
    void publishRuntime(String runId, String event);
    void publishEnd(String runId);

    interface Subscription extends AutoCloseable {
        String take() throws InterruptedException;
        default String poll(long timeout, TimeUnit unit) throws InterruptedException {
            return take();
        }
        boolean isEnd(String value);
        @Override void close();
    }
}

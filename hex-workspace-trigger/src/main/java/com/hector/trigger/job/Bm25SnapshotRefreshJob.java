package com.hector.trigger.job;

import com.hector.domain.knowledge.adapter.port.IRetrievalSparseStore;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时触发快照刷新，实际构建与原子发布由基础设施实现。 */
@Component
public class Bm25SnapshotRefreshJob {
    @Resource private IRetrievalSparseStore sparseStore;
    @Scheduled(fixedDelayString = "${hexworkspace.knowledge.hybrid.refresh-ms:60000}")
    public void refresh() { sparseStore.refreshKnownWorkspaces(); }
}

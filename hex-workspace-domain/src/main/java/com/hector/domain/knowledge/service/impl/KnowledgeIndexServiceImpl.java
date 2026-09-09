package com.hector.domain.knowledge.service.impl;

import com.hector.domain.document.event.DocumentParseRequestedEvent;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.service.IDocumentContentService;
import com.hector.domain.knowledge.adapter.port.IDocumentSegmenter;
import com.hector.domain.knowledge.adapter.port.IEmbeddingModel;
import com.hector.domain.knowledge.adapter.port.IVectorStore;
import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeSegmentVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeVectorPointVO;
import com.hector.domain.knowledge.service.IKnowledgeIndexService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识索引领域服务。
 * 与解析共用同一条 MQ 消费链路，避免“解析成功但第二条索引消息尚未投递”的额外中间态。
 */
@Service
public class KnowledgeIndexServiceImpl implements IKnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexServiceImpl.class);

    @Resource
    private IDocumentContentService documentContentService;

    @Resource
    private IDocumentRepository documentRepository;
    @Resource
    private IDocumentSegmenter documentSegmenter;
    @Resource
    private IEmbeddingModel embeddingModel;
    @Resource
    private IVectorStore vectorStore;

    @Override
    public void indexParsedDocument(DocumentParseRequestedEvent event, Long extractedContentId) {
        log.info("知识索引领域服务-建立索引开始 workspaceId:{} documentId:{} versionNumber:{}", event.getWorkspaceId(), event.getDocumentId(), event.getVersionNumber());
        // 1. 解析产物是唯一文本输入；重新索引时也可从 MinIO 重放，不依赖向量库保留正文。
        DocumentEntity documentEntity = documentRepository.findById(event.getDocumentId())
                .orElseThrow(() -> new IllegalStateException("待索引文档不存在"));
        if (!event.getWorkspaceId().equals(documentEntity.getWorkspaceId())) {
            throw new IllegalStateException("待索引文档Workspace不匹配");
        }
        String content = new String(
                documentContentService.readContent(event.getWorkspaceId(), extractedContentId),
                StandardCharsets.UTF_8);
        List<KnowledgeSegmentVO> segments = documentSegmenter.segment(content);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("解析文本为空，无法建立知识索引");
        }

        // 2. 以批量模型调用生成向量，再替换当前版本在向量库中的全部点位。
        List<String> contents = segments.stream().map(KnowledgeSegmentVO::content).collect(Collectors.toList());
        List<List<Float>> vectors = embeddingModel.embed(contents);
        if (vectors.size() != segments.size()) {
            throw new IllegalStateException("向量模型返回数量与分段数量不一致");
        }
        List<KnowledgeVectorPointVO> points = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            KnowledgeSegmentVO segment = segments.get(index);
            points.add(new KnowledgeVectorPointVO(
                    event.getDocumentId() + "-" + event.getVersionNumber() + "-" + segment.chunkNo(),
                    documentEntity.getDocumentCode(), segment.chunkNo(), segment.heading(), segment.content(), vectors.get(index)
            ));
        }
        vectorStore.replaceDocumentVersion(event.getWorkspaceId(), event.getDocumentId(), event.getVersionNumber(), points);
        log.info("知识索引领域服务-建立索引完成 workspaceId:{} documentId:{} versionNumber:{} segmentCount:{} strategy:{} model:{}",
                event.getWorkspaceId(), event.getDocumentId(), event.getVersionNumber(), segments.size(), documentSegmenter.strategyVersion(), embeddingModel.modelName());
    }

    @Override
    public List<KnowledgeSearchHitVO> search(Long workspaceId, String question) {
        log.info("知识索引领域服务-检索开始 workspaceId:{} questionLength:{}", workspaceId, question == null ? 0 : question.length());
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        // 1. 先从向量库扩大召回，再限制单个文档版本最多贡献两个片段，避免一个长文档垄断上下文。
        List<KnowledgeSearchHitVO> candidates = vectorStore.search(workspaceId, embeddingModel.embed(List.of(question.trim())).get(0), 8);
        List<KnowledgeSearchHitVO> hits = candidates.stream()
                .filter(new java.util.function.Predicate<>() {
                    private final Map<String, Integer> accepted = new java.util.HashMap<>();
                    @Override public boolean test(KnowledgeSearchHitVO hit) {
                        String key = hit.documentId() + "-" + hit.versionNumber();
                        int count = accepted.getOrDefault(key, 0);
                        if (count >= 2) return false;
                        accepted.put(key, count + 1);
                        return true;
                    }
                })
                .limit(6).collect(Collectors.toList());
        log.info("知识索引领域服务-检索完成 workspaceId:{} hitCount:{}", workspaceId, hits.size());
        return hits;
    }
}

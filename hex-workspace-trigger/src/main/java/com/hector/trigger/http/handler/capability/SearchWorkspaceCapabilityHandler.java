package com.hector.trigger.http.handler.capability;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.agent.adapter.port.ICapabilityHandler;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.knowledge.model.valobj.RetrievalContextVO;
import com.hector.domain.knowledge.model.valobj.RetrievalHitVO;
import com.hector.domain.knowledge.model.valobj.RetrievalOptionsVO;
import com.hector.domain.knowledge.model.valobj.RetrievalResultVO;
import com.hector.domain.knowledge.service.IKnowledgeRetrievalService;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将模型检索参数适配到既有可信 Retrieval Service 的生产 Capability。 */
@Component
public class SearchWorkspaceCapabilityHandler implements ICapabilityHandler {
    private static final Logger log = LoggerFactory.getLogger(SearchWorkspaceCapabilityHandler.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int MAX_TOP_K = 20;

    private final IKnowledgeRetrievalService retrievalService;

    public SearchWorkspaceCapabilityHandler(IKnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public String capabilityName() {
        return "search_workspace";
    }

    @Override
    public CapabilityResultVO invoke(CapabilityContextVO context, String argumentsJson) {
        try {
            JSONObject arguments = JSON.parseObject(argumentsJson == null ? "{}" : argumentsJson);
            String query = arguments == null ? null : arguments.getString("query");
            if (query == null || query.isBlank() || query.trim().length() > MAX_QUERY_LENGTH) {
                return CapabilityResultVO.failed(context.runId(), "", "INVALID_REQUEST",
                        "query 不能为空且长度不能超过 " + MAX_QUERY_LENGTH + " 个字符");
            }
            Integer topK = arguments.getInteger("topK");
            int resultLimit = topK == null ? DEFAULT_TOP_K : topK;
            if (resultLimit < 1 || resultLimit > MAX_TOP_K) {
                return CapabilityResultVO.failed(context.runId(), "", "INVALID_REQUEST",
                        "topK 必须在 1 到 " + MAX_TOP_K + " 之间");
            }

            RetrievalResultVO result = retrievalService.retrieve(
                    new RetrievalContextVO(context.initiatorUserId(), context.workspaceId()),
                    query.trim(), new RetrievalOptionsVO(null, null, resultLimit));
            return CapabilityResultVO.success(context.runId(), "", toData(result));
        } catch (IllegalArgumentException exception) {
            return CapabilityResultVO.failed(context.runId(), "", "INVALID_REQUEST", exception.getMessage());
        } catch (IllegalStateException exception) {
            log.error("Workspace 检索状态异常 runId:{} userId:{} workspaceId:{} error:{}",
                    context.runId(), context.initiatorUserId(), context.workspaceId(), exception.getMessage(), exception);
            if (exception.getMessage() != null && exception.getMessage().contains("无权")) {
                return CapabilityResultVO.denied(context.runId(), "", "RETRIEVAL_NOT_ALLOWED", "当前无权检索此 Workspace");
            }
            return CapabilityResultVO.failed(context.runId(), "", "RETRIEVAL_FAILED", "Workspace 检索失败");
        } catch (RuntimeException exception) {
            log.error("Workspace 检索系统异常 runId:{} userId:{} workspaceId:{}",
                    context.runId(), context.initiatorUserId(), context.workspaceId(), exception);
            return CapabilityResultVO.failed(context.runId(), "", "RETRIEVAL_FAILED", "Workspace 检索失败");
        }
    }

    private String toData(RetrievalResultVO result) {
        List<Map<String, Object>> items = result == null || result.hits() == null
                ? List.of() : result.hits().stream().map(this::toItem).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        return JSON.toJSONString(data);
    }

    private Map<String, Object> toItem(RetrievalHitVO hit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("documentId", hit.documentId());
        // 当前 Retrieval 对外提供的是文档内稳定版本号；不暴露索引内部身份。
        item.put("versionId", hit.versionNo());
        item.put("documentTitle", hit.title());
        item.put("titlePath", hit.headingPath() == null ? List.of() : hit.headingPath());
        item.put("page", hit.pageStart());
        item.put("content", hit.content());
        return item;
    }
}

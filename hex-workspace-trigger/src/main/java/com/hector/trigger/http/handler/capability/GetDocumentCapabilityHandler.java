package com.hector.trigger.http.handler.capability;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.agent.adapter.port.ICapabilityHandler;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.document.model.valobj.DocumentReadResultVO;
import com.hector.domain.document.service.IDocumentService;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 将模型的精确文档版本读取请求适配到权威文档领域服务。 */
@Component
public class GetDocumentCapabilityHandler implements ICapabilityHandler {
    private static final int DEFAULT_START_LINE = 1;
    private static final int DEFAULT_LINE_COUNT = 200;
    private static final int MAX_LINE_COUNT = 500;

    private final IDocumentService documentService;

    public GetDocumentCapabilityHandler(IDocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public String capabilityName() {
        return "get_document";
    }

    @Override
    public CapabilityResultVO invoke(CapabilityContextVO context, String argumentsJson) {
        try {
            JSONObject arguments = JSON.parseObject(argumentsJson == null ? "{}" : argumentsJson);
            Long documentId = arguments == null ? null : arguments.getLong("documentId");
            Long versionId = arguments == null ? null : arguments.getLong("versionId");
            int startLine = valueOrDefault(arguments, "startLine", DEFAULT_START_LINE);
            int lineCount = valueOrDefault(arguments, "lineCount", DEFAULT_LINE_COUNT);
            if (documentId == null || versionId == null || startLine < 1 || lineCount < 1
                    || lineCount > MAX_LINE_COUNT) {
                return CapabilityResultVO.failed(context.runId(), "", "INVALID_REQUEST", "documentId、versionId 和读取范围无效");
            }
            DocumentReadResultVO result = documentService.readVersionContent(
                    context.workspaceId(), context.initiatorUserId(), documentId, versionId, startLine, lineCount);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", result.documentId());
            data.put("versionId", result.versionId());
            data.put("documentTitle", result.documentTitle());
            data.put("startLine", result.startLine());
            data.put("endLine", result.endLine());
            data.put("hasMore", result.hasMore());
            data.put("content", result.content());
            return CapabilityResultVO.success(context.runId(), "", JSON.toJSONString(data));
        } catch (IllegalArgumentException exception) {
            return CapabilityResultVO.failed(context.runId(), "", "INVALID_REQUEST", exception.getMessage());
        } catch (BusinessException exception) {
            // 保留文档领域的稳定错误码，避免把权威状态拒绝误报成外部依赖故障。
            return CapabilityResultVO.failed(context.runId(), "", exception.getCode(), exception.getInfo());
        } catch (RuntimeException exception) {
            return CapabilityResultVO.failed(context.runId(), "", "DOCUMENT_READ_FAILED", "文档读取失败");
        }
    }

    private int valueOrDefault(JSONObject arguments, String key, int defaultValue) {
        Integer value = arguments == null ? null : arguments.getInteger(key);
        return value == null ? defaultValue : value;
    }
}

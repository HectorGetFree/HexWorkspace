package com.hector.trigger.http.handler.capability;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.hector.domain.agent.adapter.port.ICapabilityHandler;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.workspace.model.valobj.WorkspaceStructureResultVO;
import com.hector.domain.workspace.service.IWorkspaceStructureService;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将模型的一级目录浏览请求适配到权威 Workspace 结构读取服务。 */
@Component
public class ListFolderCapabilityHandler implements ICapabilityHandler {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final IWorkspaceStructureService structureService;

    public ListFolderCapabilityHandler(IWorkspaceStructureService structureService) {
        this.structureService = structureService;
    }

    @Override
    public String capabilityName() {
        return "list_folder";
    }

    @Override
    public CapabilityResultVO invoke(CapabilityContextVO context, String argumentsJson) {
        try {
            JSONObject arguments = JSON.parseObject(argumentsJson == null ? "{}" : argumentsJson);
            Long folderId = arguments == null ? null : arguments.getLong("folderId");
            Integer requestedLimit = arguments == null ? null : arguments.getInteger("limit");
            int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
            if (limit < 1 || limit > MAX_LIMIT) {
                return CapabilityResultVO.failed(context.runId(), "", "INVALID_REQUEST",
                        "limit 必须在 1 到 " + MAX_LIMIT + " 之间");
            }
            WorkspaceStructureResultVO result = structureService.listChildren(
                    context.initiatorUserId(), context.workspaceId(), folderId, limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("folderId", folderId);
            data.put("items", result.items());
            data.put("truncated", result.truncated());
            return CapabilityResultVO.success(context.runId(), "",
                    JSON.toJSONString(data, SerializerFeature.WriteMapNullValue));
        } catch (IllegalArgumentException exception) {
            return CapabilityResultVO.failed(context.runId(), "", "INVALID_REQUEST", exception.getMessage());
        } catch (BusinessException exception) {
            return CapabilityResultVO.failed(context.runId(), "", exception.getCode(), exception.getInfo());
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("无权")) {
                return CapabilityResultVO.denied(context.runId(), "", "FOLDER_ACCESS_DENIED", "当前无权访问此 Workspace");
            }
            return CapabilityResultVO.failed(context.runId(), "", "FOLDER_LIST_FAILED", "目录读取失败");
        } catch (RuntimeException exception) {
            return CapabilityResultVO.failed(context.runId(), "", "FOLDER_LIST_FAILED", "目录读取失败");
        }
    }
}

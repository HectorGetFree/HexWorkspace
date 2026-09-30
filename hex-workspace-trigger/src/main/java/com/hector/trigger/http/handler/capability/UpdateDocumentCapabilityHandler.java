package com.hector.trigger.http.handler.capability;

import com.hector.domain.agent.adapter.port.ICapabilityHandler;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.agent.service.ICapabilityMutationService;
import org.springframework.stereotype.Component;

/** 将 update_document 请求适配到文档版本 mutation 服务。 */
@Component
public class UpdateDocumentCapabilityHandler implements ICapabilityHandler {
    private final ICapabilityMutationService mutationService;

    public UpdateDocumentCapabilityHandler(ICapabilityMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @Override
    public String capabilityName() {
        return "update_document";
    }

    @Override
    public CapabilityResultVO invoke(CapabilityContextVO context, String argumentsJson) {
        return mutationService.updateDocument(context, argumentsJson);
    }
}

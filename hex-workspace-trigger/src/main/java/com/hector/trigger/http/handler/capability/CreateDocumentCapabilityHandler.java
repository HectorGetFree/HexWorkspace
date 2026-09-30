package com.hector.trigger.http.handler.capability;

import com.hector.domain.agent.adapter.port.ICapabilityHandler;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.agent.service.ICapabilityMutationService;
import org.springframework.stereotype.Component;

/** 将 create_document 请求适配到文档创建 mutation 服务。 */
@Component
public class CreateDocumentCapabilityHandler implements ICapabilityHandler {
    private final ICapabilityMutationService mutationService;

    public CreateDocumentCapabilityHandler(ICapabilityMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @Override
    public String capabilityName() {
        return "create_document";
    }

    @Override
    public CapabilityResultVO invoke(CapabilityContextVO context, String argumentsJson) {
        return mutationService.createDocument(context, argumentsJson);
    }
}

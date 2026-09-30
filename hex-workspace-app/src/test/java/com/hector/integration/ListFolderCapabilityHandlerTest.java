package com.hector.integration;

import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.workspace.model.valobj.WorkspaceStructureItemVO;
import com.hector.domain.workspace.model.valobj.WorkspaceStructureResultVO;
import com.hector.domain.workspace.service.IWorkspaceStructureService;
import com.hector.trigger.http.handler.capability.ListFolderCapabilityHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListFolderCapabilityHandlerTest {

    @Test
    void listsRootWithCurrentDocumentVersionIdentity() {
        IWorkspaceStructureService structureService = (userId, workspaceId, folderId, limit) ->
                new WorkspaceStructureResultVO(List.of(
                        new WorkspaceStructureItemVO("folder", 10L, "docs", null),
                        new WorkspaceStructureItemVO("document", 11L, "README.md", 3L)), false);

        CapabilityResultVO result = new ListFolderCapabilityHandler(structureService).invoke(
                new CapabilityContextVO("run-1", "conversation-1", 7L, 9L, "[\"list_folder\"]"), "{}");

        assertEquals(CapabilityResultVO.Status.SUCCEEDED, result.status());
        assertTrue(result.dataJson().contains("\"versionId\":3"));
        assertTrue(result.dataJson().contains("\"folderId\":null"));
        assertTrue(result.dataJson().contains("\"truncated\":false"));
    }

    @Test
    void rejectsInvalidLimit() {
        IWorkspaceStructureService structureService = (userId, workspaceId, folderId, limit) ->
                new WorkspaceStructureResultVO(List.of(), false);

        CapabilityResultVO result = new ListFolderCapabilityHandler(structureService).invoke(
                new CapabilityContextVO("run-1", "conversation-1", 7L, 9L, "[\"list_folder\"]"),
                "{\"limit\":201}");

        assertEquals(CapabilityResultVO.Status.FAILED, result.status());
        assertEquals("INVALID_REQUEST", result.errorCode());
    }
}

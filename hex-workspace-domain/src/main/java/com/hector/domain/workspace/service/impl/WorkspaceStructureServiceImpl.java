package com.hector.domain.workspace.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.service.IFolderService;
import com.hector.domain.workspace.model.valobj.WorkspaceStructureItemVO;
import com.hector.domain.workspace.model.valobj.WorkspaceStructureResultVO;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.domain.workspace.service.IWorkspaceStructureService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Workspace 结构读取服务；集中承载目录可见性和当前版本身份的读取语义。 */
@Service
public class WorkspaceStructureServiceImpl implements IWorkspaceStructureService {

    private final IFolderService folderService;
    private final IDocumentService documentService;
    private final IWorkspaceRepository workspaceRepository;
    private final IDocumentVersionRepository documentVersionRepository;

    public WorkspaceStructureServiceImpl(IFolderService folderService, IDocumentService documentService,
                                         IWorkspaceRepository workspaceRepository,
                                         IDocumentVersionRepository documentVersionRepository) {
        this.folderService = folderService;
        this.documentService = documentService;
        this.workspaceRepository = workspaceRepository;
        this.documentVersionRepository = documentVersionRepository;
    }

    @Override
    public WorkspaceStructureResultVO listChildren(Long userId, Long workspaceId, Long folderId, int limit) {
        // 1. 目录身份来自调用方可信上下文；未提供时只能通过领域服务解析当前根目录。
        workspaceRepository.findById(workspaceId)
                .filter(workspace -> workspace.isActive() && userId != null
                        && userId.equals(workspace.getOwnerUserId()))
                .orElseThrow(() -> new IllegalStateException("当前用户无权访问此 Workspace"));
        FolderEntity parent = folderId == null
                ? folderService.ensureRootFolder(workspaceId)
                : folderService.getActiveFolder(workspaceId, folderId);

        // 2. Folder/Document 服务分别执行自身的状态与祖先目录校验，避免 Capability 层复制权限规则。
        List<WorkspaceStructureItemVO> items = new ArrayList<>();
        folderService.listChildren(workspaceId, parent.getId()).stream()
                .filter(FolderEntity::isActive)
                .map(folder -> new WorkspaceStructureItemVO("folder", folder.getId(), folder.getName(), null))
                .forEach(items::add);
        documentService.listVisibleDocumentsByParentId(workspaceId, parent.getId()).stream()
                .filter(document -> document.isActive() && document.getCurrentVersionNo() != null
                        && document.getCurrentVersionNo() > 0)
                .map(document -> {
                    // versionId 必须是不可变版本主键，才能直接用于 get_document 和版本冲突检查。
                    Long versionId = documentVersionRepository.findByDocumentIdAndVersionNo(
                                    document.getId(), document.getCurrentVersionNo())
                            .orElseThrow(() -> new IllegalStateException("文档当前版本记录不存在"))
                            .getId();
                    return new WorkspaceStructureItemVO(
                            "document", document.getId(), document.getFileName(), versionId);
                })
                .forEach(items::add);

        // 3. 目录优先、名称稳定排序、ID 兜底，确保截断边界在稳定顺序之后计算。
        List<WorkspaceStructureItemVO> orderedItems = items.stream()
                .sorted(Comparator.comparingInt((WorkspaceStructureItemVO item) ->
                                "folder".equals(item.type()) ? 0 : 1)
                        .thenComparing(WorkspaceStructureItemVO::name, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(WorkspaceStructureItemVO::id))
                .toList();
        List<WorkspaceStructureItemVO> probeItems = orderedItems.subList(
                0, Math.min(orderedItems.size(), limit + 1));
        boolean truncated = probeItems.size() > limit;
        List<WorkspaceStructureItemVO> boundedItems = probeItems.subList(0, Math.min(probeItems.size(), limit));
        return new WorkspaceStructureResultVO(boundedItems, truncated);
    }
}

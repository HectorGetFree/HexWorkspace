package com.hector.domain.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.hector.domain.agent.model.entity.CapabilityMutationRecordEntity;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityMutationStatusVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.agent.repository.ICapabilityMutationRecordRepository;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentMutationResultVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.document.service.IDocumentUploadTaskService;
import com.hector.domain.folder.service.IFolderService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HexFormat;

/** 基于 durable mutation record 执行 update_document。 */
@Service
public class CapabilityMutationServiceImpl implements com.hector.domain.agent.service.ICapabilityMutationService {
    private static final String CAPABILITY = "update_document";
    private static final int CLAIM_LEASE_MINUTES = 10;

    @Resource
    private ICapabilityMutationRecordRepository recordRepository;
    @Resource
    private IDocumentRepository documentRepository;
    @Resource
    private IDocumentVersionRepository versionRepository;
    @Resource
    private IDocumentService documentService;
    @Resource
    private IDocumentUploadTaskRepository uploadTaskRepository;
    @Resource
    private IFolderService folderService;
    @Resource
    private IDocumentUploadTaskService uploadTaskService;

    @Override
    public CapabilityResultVO createDocument(CapabilityContextVO context, String argumentsJson) {
        if (context.callId() == null || context.callId().isBlank()) {
            return failed(context, "INVALID_REQUEST", "callId 不能为空");
        }
        JSONObject arguments;
        try {
            arguments = JSON.parseObject(argumentsJson == null ? "{}" : argumentsJson);
        } catch (RuntimeException exception) {
            return failed(context, "INVALID_REQUEST", "请求参数无效");
        }
        Long parentFolderId = arguments == null ? null : arguments.getLong("parentFolderId");
        String title = arguments == null ? null : arguments.getString("title");
        String content = arguments == null ? null : arguments.getString("content");
        if (parentFolderId == null || parentFolderId <= 0 || title == null || title.isBlank() || content == null) {
            return failed(context, "INVALID_REQUEST", "parentFolderId、title 和 content 不能为空");
        }
        // 1. 先校验当前 Folder 权限，避免无权请求留下 mutation record。
        try {
            folderService.getActiveFolder(context.workspaceId(), parentFolderId);
        } catch (BusinessException exception) {
            return denied(context, "WRITE_DENIED", "当前用户无权在此文件夹创建文档");
        }
        String fingerprint = fingerprint(arguments);
        CapabilityMutationRecordEntity existing = recordRepository
                .findByIdentity(context.runId(), context.callId()).orElse(null);
        if (existing != null) {
            return resolveCreateExisting(context, existing, fingerprint, parentFolderId, title, content);
        }
        String executionToken = UUID.randomUUID().toString();
        CapabilityMutationRecordEntity record = CapabilityMutationRecordEntity.builder()
                .runId(context.runId()).callId(context.callId()).capability("create_document")
                .requestFingerprint(fingerprint).status(CapabilityMutationStatusVO.PROCESSING)
                .executionToken(executionToken).leaseUntil(LocalDateTime.now().plusMinutes(CLAIM_LEASE_MINUTES))
                .targetVersionNo(1L).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        if (!recordRepository.saveIfAbsent(record)) {
            return createDocument(context, argumentsJson);
        }
        try {
            String idempotencyKey = createIdempotencyKey(context);
            DocumentEntity document = documentService.createMarkdownDocument(context.workspaceId(),
                    context.initiatorUserId(), parentFolderId, title, content, idempotencyKey);
            return executeCreateAndResolve(context, fingerprint, executionToken, document);
        } catch (BusinessException exception) {
            recordRepository.markFailed(context.runId(), context.callId(), fingerprint,
                    executionToken, exception.getCode(), exception.getInfo());
            return failed(context, exception.getCode(), exception.getInfo());
        } catch (RuntimeException exception) {
            recordRepository.markPending(context.runId(), context.callId(), fingerprint,
                    executionToken, "DOCUMENT_CREATE_PENDING", "文档创建状态待确认");
            return failed(context, "DOCUMENT_CREATE_PENDING", "文档创建状态待确认");
        }
    }

    private CapabilityResultVO resolveCreateExisting(CapabilityContextVO context,
                                                      CapabilityMutationRecordEntity record, String fingerprint,
                                                      Long parentFolderId, String title, String content) {
        if (!fingerprint.equals(record.getRequestFingerprint())) {
            return failed(context, "IDEMPOTENCY_CONFLICT", "相同 runId 和 callId 对应了不同请求");
        }
        if (record.getStatus() == CapabilityMutationStatusVO.SUCCEEDED) {
            return createSuccess(context, record.getDocumentId(), record.getVersionId());
        }
        DocumentEntity document = documentRepository.findByUploadIdempotencyKey(
                context.workspaceId(), context.initiatorUserId(), createIdempotencyKey(context)).orElse(null);
        if (document != null && document.isActive() && Long.valueOf(1L).equals(document.getCurrentVersionNo())) {
            Long versionId = versionRepository.findByDocumentIdAndVersionNo(document.getId(), 1L)
                    .map(DocumentVersionEntity::getId).orElse(null);
            if (versionId == null) {
                return failed(context, "DOCUMENT_CREATE_PENDING", "文档 V1 尚未正式生效");
            }
            recordRepository.markSucceeded(context.runId(), context.callId(), fingerprint,
                    record.getExecutionToken(), document.getId(), null, versionId);
            return createSuccess(context, document.getId(), versionId);
        }
        LocalDateTime now = LocalDateTime.now();
        if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now)) {
            return failed(context, "DOCUMENT_CREATE_PENDING", "文档创建已经开始，等待 V1 正式生效");
        }
        String executionToken = UUID.randomUUID().toString();
        if (!recordRepository.claimExpired(context.runId(), context.callId(), fingerprint,
                executionToken, now.plusMinutes(CLAIM_LEASE_MINUTES), now)) {
            return failed(context, "DOCUMENT_CREATE_PENDING", "文档创建正在由其他请求恢复");
        }
        try {
            document = documentService.createMarkdownDocument(context.workspaceId(), context.initiatorUserId(),
                    parentFolderId, title, content, createIdempotencyKey(context));
            return executeCreateAndResolve(context, fingerprint, executionToken, document);
        } catch (BusinessException exception) {
            recordRepository.markFailed(context.runId(), context.callId(), fingerprint,
                    executionToken, exception.getCode(), exception.getInfo());
            return failed(context, exception.getCode(), exception.getInfo());
        } catch (RuntimeException exception) {
            recordRepository.markPending(context.runId(), context.callId(), fingerprint,
                    executionToken, "DOCUMENT_CREATE_PENDING", "文档创建状态待确认");
            return failed(context, "DOCUMENT_CREATE_PENDING", "文档创建状态待确认");
        }
    }

    private CapabilityResultVO executeCreateAndResolve(CapabilityContextVO context, String fingerprint,
                                                       String executionToken, DocumentEntity document) {
        if (!document.isActive()) {
            uploadTaskRepository.findByDocumentIdAndVersionNo(document.getId(), 1L)
                    .ifPresent(task -> uploadTaskService.execute(task.getTaskCode()));
            document = documentRepository.findById(document.getId()).orElse(document);
        }
        if (document.isActive() && Long.valueOf(1L).equals(document.getCurrentVersionNo())
                && versionRepository.findByDocumentIdAndVersionNo(document.getId(), 1L).isPresent()) {
            Long versionId = versionRepository.findByDocumentIdAndVersionNo(document.getId(), 1L)
                    .map(DocumentVersionEntity::getId).orElseThrow();
            recordRepository.markSucceeded(context.runId(), context.callId(), fingerprint,
                    executionToken, document.getId(), null, versionId);
            return createSuccess(context, document.getId(), versionId);
        }
        return pendingCreate(context, "DOCUMENT_CREATE_PENDING", "文档创建状态待确认", document.getId());
    }

    private CapabilityResultVO pendingCreate(CapabilityContextVO context, String code, String message, Long documentId) {
        return failed(context, code, message + (documentId == null ? "" : "，documentId=" + documentId));
    }

    private CapabilityResultVO createSuccess(CapabilityContextVO context, Long documentId, Long versionId) {
        JSONObject data = new JSONObject();
        data.put("documentId", documentId);
        data.put("versionId", versionId);
        return CapabilityResultVO.success(context.runId(), context.callId(), JSON.toJSONString(data));
    }

    private String createIdempotencyKey(CapabilityContextVO context) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((context.runId() + ":" + context.callId()).getBytes(StandardCharsets.UTF_8));
            return "agent:" + HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成创建幂等键", exception);
        }
    }

    @Override
    public CapabilityResultVO updateDocument(CapabilityContextVO context, String argumentsJson) {
        if (context.callId() == null || context.callId().isBlank()) {
            return failed(context, "INVALID_REQUEST", "callId 不能为空");
        }
        JSONObject arguments;
        try {
            arguments = JSON.parseObject(argumentsJson == null ? "{}" : argumentsJson);
        } catch (RuntimeException exception) {
            return failed(context, "INVALID_REQUEST", "请求参数无效");
        }
        Long documentId = arguments == null ? null : arguments.getLong("documentId");
        Long expectedVersionId = arguments == null ? null : arguments.getLong("expectedVersionId");
        String content = arguments == null ? null : arguments.getString("content");
        if (documentId == null || expectedVersionId == null || content == null) {
            return failed(context, "INVALID_REQUEST", "documentId、expectedVersionId 和 content 不能为空");
        }
        String fingerprint = fingerprint(arguments);
        CapabilityMutationRecordEntity existing = recordRepository
                .findByIdentity(context.runId(), context.callId()).orElse(null);
        if (existing != null) {
            return resolveExisting(context, existing, fingerprint, documentId, expectedVersionId, content);
        }
        DocumentEntity document = documentRepository.findById(documentId)
                .orElse(null);
        DocumentVersionEntity expected = versionRepository.findById(expectedVersionId).orElse(null);
        if (document == null || expected == null || !documentId.equals(expected.getDocumentId())) {
            return failed(context, "DOCUMENT_NOT_FOUND", "文档或版本不存在");
        }
        if (!document.isActive()
                || !context.workspaceId().equals(document.getWorkspaceId())
                || !context.initiatorUserId().equals(document.getOwnerUserId())) {
            return denied(context, "WRITE_DENIED", "当前用户无权修改此文档");
        }
        if (!expected.getVersionNo().equals(document.getCurrentVersionNo())) {
            return failed(context, "VERSION_CONFLICT", "文档版本已发生变化");
        }
        long targetVersionNo = document.getCurrentVersionNo() + 1;
        String executionToken = UUID.randomUUID().toString();
        CapabilityMutationRecordEntity record = CapabilityMutationRecordEntity.builder()
                .runId(context.runId()).callId(context.callId()).capability(CAPABILITY)
                .requestFingerprint(fingerprint).status(CapabilityMutationStatusVO.PROCESSING)
                .executionToken(executionToken).leaseUntil(LocalDateTime.now().plusMinutes(CLAIM_LEASE_MINUTES))
                .documentId(documentId).previousVersionId(expectedVersionId).targetVersionNo(targetVersionNo)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        if (!recordRepository.saveIfAbsent(record)) {
            return updateDocument(context, argumentsJson);
        }
        try {
            DocumentMutationResultVO result = documentService.updateMarkdownDocument(
                    context.workspaceId(), context.initiatorUserId(), documentId, expectedVersionId, content);
            recordRepository.markSucceeded(context.runId(), context.callId(), fingerprint,
                    executionToken, result.documentId(), result.previousVersionId(), result.versionId());
            return success(context, result);
        } catch (BusinessException exception) {
            recordRepository.markFailed(context.runId(), context.callId(), fingerprint,
                    executionToken, exception.getCode(), exception.getInfo());
            return failed(context, exception.getCode(), exception.getInfo());
        } catch (RuntimeException exception) {
            recordRepository.markPending(context.runId(), context.callId(), fingerprint,
                    executionToken, "DOCUMENT_UPDATE_PENDING", "文档更新状态待确认");
            return failed(context, "DOCUMENT_UPDATE_PENDING", "文档更新状态待确认");
        }
    }

    private CapabilityResultVO resolveExisting(CapabilityContextVO context, CapabilityMutationRecordEntity record,
                                               String fingerprint, Long documentId, Long expectedVersionId,
                                               String content) {
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            return failed(context, "IDEMPOTENCY_CONFLICT", "相同 runId 和 callId 对应了不同请求");
        }
        if (record.getStatus() == CapabilityMutationStatusVO.SUCCEEDED) {
            return success(context, new DocumentMutationResultVO(record.getDocumentId(),
                    record.getPreviousVersionId(), record.getVersionId()));
        }
        if (record.getTargetVersionNo() != null) {
            DocumentEntity document = documentRepository.findById(documentId).orElse(null);
            DocumentVersionEntity target = versionRepository
                    .findByDocumentIdAndVersionNo(documentId, record.getTargetVersionNo()).orElse(null);
            if (document != null && document.isActive()
                    && record.getTargetVersionNo().equals(document.getCurrentVersionNo()) && target != null) {
                recordRepository.markSucceeded(context.runId(), context.callId(), fingerprint,
                        record.getExecutionToken(), documentId, expectedVersionId, target.getId());
                return success(context, new DocumentMutationResultVO(
                        documentId, expectedVersionId, target.getId()));
            }
            if (uploadTaskRepository.findByDocumentIdAndVersionNo(documentId, record.getTargetVersionNo()).isPresent()
                    || (document != null && !document.isActive())) {
                return failed(context, "DOCUMENT_UPDATE_PENDING", "文档更新已经开始，等待既有任务完成");
            }
        }
        DocumentEntity document = documentRepository.findById(documentId).orElse(null);
        if (document != null && document.isActive()
                && expectedVersionId.equals(versionRepository.findByDocumentIdAndVersionNo(
                documentId, document.getCurrentVersionNo()).map(DocumentVersionEntity::getId).orElse(null))
                && (record.getLeaseUntil() == null || !record.getLeaseUntil().isAfter(LocalDateTime.now()))) {
            String executionToken = UUID.randomUUID().toString();
            LocalDateTime leaseUntil = LocalDateTime.now().plusMinutes(CLAIM_LEASE_MINUTES);
            if (recordRepository.claimExpired(context.runId(), context.callId(), fingerprint,
                    executionToken, leaseUntil, LocalDateTime.now())) {
                try {
                    DocumentMutationResultVO result = documentService.updateMarkdownDocument(
                            context.workspaceId(), context.initiatorUserId(), documentId, expectedVersionId, content);
                    recordRepository.markSucceeded(context.runId(), context.callId(), fingerprint,
                            executionToken, result.documentId(), result.previousVersionId(), result.versionId());
                    return success(context, result);
                } catch (BusinessException exception) {
                    recordRepository.markFailed(context.runId(), context.callId(), fingerprint,
                            executionToken, exception.getCode(), exception.getInfo());
                    return failed(context, exception.getCode(), exception.getInfo());
                } catch (RuntimeException exception) {
                    recordRepository.markPending(context.runId(), context.callId(), fingerprint,
                            executionToken, "DOCUMENT_UPDATE_PENDING", "文档更新状态待确认");
                    return failed(context, "DOCUMENT_UPDATE_PENDING", "文档更新状态待确认");
                }
            }
        }
        return failed(context, record.getErrorCode() == null ? "DOCUMENT_UPDATE_PENDING" : record.getErrorCode(),
                record.getErrorMessage() == null ? "文档更新状态待确认" : record.getErrorMessage());
    }

    private String fingerprint(JSONObject arguments) {
        String canonical = JSON.toJSONString(arguments, SerializerFeature.SortField);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算请求摘要", exception);
        }
    }

    private CapabilityResultVO success(CapabilityContextVO context, DocumentMutationResultVO result) {
        return CapabilityResultVO.success(context.runId(), context.callId(), JSON.toJSONString(result));
    }

    private CapabilityResultVO failed(CapabilityContextVO context, String code, String message) {
        return CapabilityResultVO.failed(context.runId(), context.callId(), code, message);
    }

    private CapabilityResultVO denied(CapabilityContextVO context, String code, String message) {
        return CapabilityResultVO.denied(context.runId(), context.callId(), code, message);
    }
}

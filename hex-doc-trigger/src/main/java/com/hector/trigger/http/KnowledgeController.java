package com.hector.trigger.http;

import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;
import com.hector.domain.knowledge.service.IKnowledgeIndexService;
import com.hector.domain.membership.service.IMembershipService;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import com.hector.trigger.api.dto.KnowledgeSearchResponseDTO;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

/** 租户级知识检索接口，MVP 只返回向量 Top-K 命中，不在此阶段加入问答模型编排。 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/knowledge")
public class KnowledgeController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    @Resource private IMembershipService membershipService;
    @Resource private IKnowledgeIndexService knowledgeIndexService;

    /**
     * 根据问题查询当前租户的知识片段。
     * curl 示例：curl -G "http://127.0.0.1:8098/api/v1/tenants/1/knowledge/search" -H "Authorization: Bearer 替换为实际登录令牌" --data-urlencode "question=文档讲了什么"
     */
    @GetMapping("/search")
    public Response<KnowledgeSearchResponseDTO> search(@PathVariable Long tenantId, @RequestParam String question) {
        try {
            Long userId = SecurityUserUtil.getCurrentUserId();
            log.info("知识检索开始 tenantId:{} userId:{} questionLength:{}", tenantId, userId, question == null ? 0 : question.length());
            membershipService.assertActiveMember(userId, tenantId);
            KnowledgeSearchResponseDTO response = KnowledgeSearchResponseDTO.builder().hits(knowledgeIndexService.search(tenantId, question).stream()
                    .map(this::toHit).collect(Collectors.toList())).build();
            log.info("知识检索完成 tenantId:{} userId:{} hitCount:{}", tenantId, userId, response.getHits().size());
            return success(response);
        } catch (BusinessException | IllegalArgumentException exception) {
            log.error("知识检索业务异常 tenantId:{} error:{}", tenantId, exception.getMessage(), exception);
            return failure(ResponseCode.SYSTEM_ERROR.getCode(), exception.getMessage());
        } catch (Exception exception) {
            log.error("知识检索系统异常 tenantId:{}", tenantId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private KnowledgeSearchResponseDTO.Hit toHit(KnowledgeSearchHitVO hit) {
        return KnowledgeSearchResponseDTO.Hit.builder().documentId(hit.documentId()).versionNumber(hit.versionNumber())
                .chunkNo(hit.chunkNo()).heading(hit.heading()).content(hit.content()).score(hit.score()).build();
    }
}

package com.hector.domain.knowledge.service.impl;

import com.hector.domain.knowledge.adapter.port.IChatModel;
import com.hector.domain.knowledge.model.valobj.KnowledgeAnswerVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeSearchHitVO;
import com.hector.domain.knowledge.service.IKnowledgeAnswerService;
import com.hector.domain.knowledge.service.IKnowledgeIndexService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库问答领域服务。
 * 先由后端确定可用来源，再把受限上下文交给聊天模型，避免模型越过租户检索边界自行编造引用。
 */
@Service
public class KnowledgeAnswerServiceImpl implements IKnowledgeAnswerService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAnswerServiceImpl.class);
    private static final int MAX_CONTEXT_CHARACTERS = 6000;
    private static final String NO_ANSWER = "当前知识库未提供足够信息，无法回答该问题。";
    private static final String SYSTEM_PROMPT = "你是企业知识库助手。只能依据用户提供的知识库上下文回答问题。"
            + "若上下文不足，请明确说明无法回答；不得猜测、补充外部知识或编造引用。回答应简洁、准确。";

    @Resource
    private IKnowledgeIndexService knowledgeIndexService;

    @Resource
    private IChatModel chatModel;

    @Override
    public KnowledgeAnswerVO answer(Long tenantId, String question) {
        log.info("知识问答领域服务-生成回答开始 tenantId:{} questionLength:{}", tenantId, question == null ? 0 : question.length());
        List<KnowledgeSearchHitVO> sources = knowledgeIndexService.search(tenantId, question);
        if (sources.isEmpty()) {
            log.info("知识问答领域服务-无检索来源，直接返回拒答 tenantId:{}", tenantId);
            return new KnowledgeAnswerVO(NO_ANSWER, List.of());
        }

        // 1. 只拼入后端实际召回的片段，并限制上下文体积，避免单次模型调用不可控地膨胀。
        String userPrompt = buildUserPrompt(question, sources);
        String answer = chatModel.complete(SYSTEM_PROMPT, userPrompt);
        log.info("知识问答领域服务-生成回答完成 tenantId:{} sourceCount:{} model:{}", tenantId, sources.size(), chatModel.modelName());
        return new KnowledgeAnswerVO(answer, sources);
    }

    private String buildUserPrompt(String question, List<KnowledgeSearchHitVO> sources) {
        StringBuilder context = new StringBuilder();
        for (KnowledgeSearchHitVO source : sources) {
            String section = "[来源 documentCode=" + source.documentCode() + ", version=" + source.versionNumber()
                    + ", chunk=" + source.chunkNo() + "]\n标题：" + (source.heading() == null ? "未命名章节" : source.heading())
                    + "\n正文：" + source.content() + "\n\n";
            if (context.length() + section.length() > MAX_CONTEXT_CHARACTERS) {
                int remaining = MAX_CONTEXT_CHARACTERS - context.length();
                if (remaining > 0) {
                    context.append(section, 0, Math.min(remaining, section.length()));
                }
                break;
            }
            context.append(section);
        }
        return "知识库上下文：\n" + context + "用户问题：\n" + question;
    }
}

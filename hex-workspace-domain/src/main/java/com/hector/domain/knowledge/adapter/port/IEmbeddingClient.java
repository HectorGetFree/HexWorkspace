package com.hector.domain.knowledge.adapter.port;

import com.hector.domain.knowledge.model.valobj.EmbeddingVectorVO;

import java.util.List;

/** 面向索引构建的 Embedding 客户端，显式保留供应商返回序号。 */
public interface IEmbeddingClient {

    /**
     * 批量生成向量，并保留供应商返回的输入序号。
     * 调用方必须依据序号校验结果完整性，不能假定返回列表天然有序。
     *
     * @param inputs 经过稳定拼装的文本输入
     * @return 每条输入对应的序号与向量
     */
    List<EmbeddingVectorVO> embedIndexed(List<String> inputs);
}

package com.hector.infrastructure.repository;

import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.repository.IServingVectorSpaceRepository;
import com.hector.infrastructure.dao.IServingVectorSpaceDao;
import com.hector.infrastructure.dao.po.EmbeddingConfig;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 数据库单例 Serving Vector Space 指针仓储实现。 */
@Repository
public class ServingVectorSpaceRepository implements IServingVectorSpaceRepository {

    @Resource
    private IServingVectorSpaceDao servingVectorSpaceDao;

    @Override
    public Optional<EmbeddingConfigEntity> findServingConfig() {
        return Optional.ofNullable(servingVectorSpaceDao.queryServingConfig())
                .map(this::toEmbeddingConfigEntity);
    }

    @Override
    public boolean initializeIfAbsent(Long embeddingConfigId) {
        return servingVectorSpaceDao.insertIgnore(embeddingConfigId) == 1;
    }

    private EmbeddingConfigEntity toEmbeddingConfigEntity(EmbeddingConfig record) {
        return EmbeddingConfigEntity.builder()
                .id(record.getId())
                .configHash(record.getConfigHash())
                .provider(record.getProvider())
                .model(record.getModel())
                .dimension(record.getDimension())
                .distance(record.getDistance())
                .collectionName(record.getCollectionName())
                .createdAt(record.getCreatedAt())
                .build();
    }
}

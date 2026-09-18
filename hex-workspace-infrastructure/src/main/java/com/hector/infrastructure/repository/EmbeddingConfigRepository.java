package com.hector.infrastructure.repository;

import com.hector.domain.knowledge.model.entity.EmbeddingConfigEntity;
import com.hector.domain.knowledge.repository.IEmbeddingConfigRepository;
import com.hector.infrastructure.dao.IEmbeddingConfigDao;
import com.hector.infrastructure.dao.po.EmbeddingConfig;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Embedding 配置快照仓储实现。 */
@Repository
public class EmbeddingConfigRepository implements IEmbeddingConfigRepository {

    @Resource
    private IEmbeddingConfigDao embeddingConfigDao;

    @Override
    public EmbeddingConfigEntity saveIfAbsent(EmbeddingConfigEntity config) {
        embeddingConfigDao.insertIgnore(toEmbeddingConfigRecord(config));
        return findByConfigHash(config.getConfigHash()).orElseThrow();
    }

    @Override
    public Optional<EmbeddingConfigEntity> findById(Long id) {
        return Optional.ofNullable(embeddingConfigDao.queryById(id)).map(this::toEmbeddingConfigEntity);
    }

    @Override
    public Optional<EmbeddingConfigEntity> findByConfigHash(String configHash) {
        return Optional.ofNullable(embeddingConfigDao.queryByConfigHash(configHash)).map(this::toEmbeddingConfigEntity);
    }

    private EmbeddingConfig toEmbeddingConfigRecord(EmbeddingConfigEntity entity) {
        return EmbeddingConfig.builder().id(entity.getId()).configHash(entity.getConfigHash())
                .provider(entity.getProvider()).model(entity.getModel()).dimension(entity.getDimension())
                .distance(entity.getDistance()).collectionName(entity.getCollectionName()).createdAt(entity.getCreatedAt()).build();
    }

    private EmbeddingConfigEntity toEmbeddingConfigEntity(EmbeddingConfig po) {
        return EmbeddingConfigEntity.builder().id(po.getId()).configHash(po.getConfigHash())
                .provider(po.getProvider()).model(po.getModel()).dimension(po.getDimension())
                .distance(po.getDistance()).collectionName(po.getCollectionName()).createdAt(po.getCreatedAt()).build();
    }
}

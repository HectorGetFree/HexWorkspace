package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.KnowledgeIndexGeneration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface IKnowledgeIndexGenerationDao {
    int insert(KnowledgeIndexGeneration generation);

    int attachChunkArtifactIfOwned(@Param("generationId") String generationId, @Param("workerToken") String workerToken,
                                   @Param("chunkArtifactKey") String chunkArtifactKey,
                                   @Param("chunkArtifactChecksum") String chunkArtifactChecksum,
                                   @Param("chunkCount") int chunkCount,
                                   @Param("now") LocalDateTime now);
}

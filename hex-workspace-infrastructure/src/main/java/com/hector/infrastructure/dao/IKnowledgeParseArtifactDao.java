package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.KnowledgeParseArtifact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IKnowledgeParseArtifactDao {
    KnowledgeParseArtifact queryByIdentity(@Param("documentId") Long documentId, @Param("versionNo") Long versionNo,
                                           @Param("sourceChecksum") String sourceChecksum, @Param("parserVersion") String parserVersion,
                                           @Param("schemaVersion") String schemaVersion);

    int insertIgnore(KnowledgeParseArtifact artifact);

    List<KnowledgeParseArtifact> queryReadyByVersion(@Param("documentId") Long documentId,
                                                      @Param("versionNo") Long versionNo,
                                                      @Param("sourceChecksum") String sourceChecksum);
}

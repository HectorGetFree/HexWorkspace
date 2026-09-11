package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.DocumentUploadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 文档上传任务 DAO。 */
@Mapper
public interface IDocumentUploadTaskDao {
    int insert(DocumentUploadTask task);
    DocumentUploadTask queryByTaskCode(@Param("taskCode") String taskCode);
    int claimForRunning(@Param("taskCode") String taskCode);
    int update(DocumentUploadTask task);
    List<DocumentUploadTask> queryRetryDue(@Param("now") LocalDateTime now, @Param("limit") int limit);
    int claimRetryForDispatch(@Param("taskCode") String taskCode, @Param("now") LocalDateTime now);
}

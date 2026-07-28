package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.File;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文件 DAO。
 */
@Mapper
public interface IFileDao {

    /**
     * 新增文件。
     */
    int insert(File file);

    /**
     * 更新文件。
     */
    int update(File file);

    /**
     * 按主键查询文件。
     */
    File queryFileById(@Param("id") Long id);

    /**
     * 按文件编码查询文件。
     */
    File queryFileByFileCode(@Param("fileCode") String fileCode);

    /**
     * 按创建者用户ID查询文件列表。
     */
    List<File> queryFilesByCreatorUserId(@Param("creatorUserId") Long creatorUserId);
}

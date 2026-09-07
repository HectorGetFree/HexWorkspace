package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档 DAO。
 */
@Mapper
public interface IDocumentDao {

    int insert(Document document);

    int update(Document document);

    Document queryById(@Param("id") Long id);

    Document queryByDocumentCode(@Param("documentCode") String documentCode);

    List<Document> queryByTenantId(@Param("tenantId") Long tenantId);
}

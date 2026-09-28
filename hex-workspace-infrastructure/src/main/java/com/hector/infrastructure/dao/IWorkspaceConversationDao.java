package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.WorkspaceConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Workspace Conversation DAO。 */
@Mapper
public interface IWorkspaceConversationDao {
    int insert(WorkspaceConversation conversation);
    WorkspaceConversation queryByConversationId(@Param("conversationId") String conversationId);
    WorkspaceConversation queryByConversationIdForUpdate(@Param("conversationId") String conversationId);
}

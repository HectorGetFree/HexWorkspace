package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.WorkspaceConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/** Workspace Conversation DAO。 */
@Mapper
public interface IWorkspaceConversationDao {
    int insert(WorkspaceConversation conversation);
    WorkspaceConversation queryByConversationId(@Param("conversationId") String conversationId);
    WorkspaceConversation queryByConversationIdForUpdate(@Param("conversationId") String conversationId);
    List<WorkspaceConversation> queryByUserId(@Param("userId") Long userId, @Param("limit") int limit);
    int touch(@Param("conversationId") String conversationId, @Param("updatedAt") LocalDateTime updatedAt);
}

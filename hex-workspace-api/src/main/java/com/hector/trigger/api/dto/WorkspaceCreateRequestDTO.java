package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 创建资料 Workspace 请求。拥有者始终由服务端从登录态确定。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceCreateRequestDTO implements Serializable {

    private String workspaceName;
    private String description;
}

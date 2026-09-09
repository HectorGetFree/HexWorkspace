package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** Workspace 目录内容响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectoryResponseDTO implements Serializable {

    /** 当前打开的文件夹。 */
    private FolderResponseDTO currentFolder;

    /** 当前文件夹下的直属子文件夹。 */
    private List<FolderResponseDTO> folders;

    /** 当前文件夹下的直属文档。 */
    private List<DocumentResponseDTO> documents;
}

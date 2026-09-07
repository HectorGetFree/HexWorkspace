package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 文档列表响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListResponseDTO implements Serializable {

    private List<DocumentResponseDTO> documents;
}

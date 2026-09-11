package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建文档响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentCreateResponseDTO implements Serializable {

    private String documentCode;
}

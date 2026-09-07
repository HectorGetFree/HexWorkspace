package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 成员关系列表响应对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipListResponseDTO implements Serializable {

    /** 成员关系列表。 */
    private List<MembershipResponseDTO> memberships;
}

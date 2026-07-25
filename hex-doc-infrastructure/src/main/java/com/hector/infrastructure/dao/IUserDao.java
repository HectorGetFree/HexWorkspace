package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户 DAO。
 */
@Mapper
public interface IUserDao {

    /**
     * 新增用户。
     */
    int insert(UserPO userPO);

    /**
     * 按用户名查询用户。
     */
    UserPO queryUserByUsername(@Param("username") String username);

    /**
     * 按主键查询用户。
     */
    UserPO queryUserById(@Param("id") Long id);
}

package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.User;
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
    int insert(User user);

    /**
     * 按用户名查询用户。
     */
    User queryUserByUsername(@Param("username") String username);

    /**
     * 按主键查询用户。
     */
    User queryUserById(@Param("id") Long id);
}

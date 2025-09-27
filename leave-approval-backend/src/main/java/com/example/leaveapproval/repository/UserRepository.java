// 文件路径: com/example/leaveapproval/repository/UserRepository.java
// (请用以下全部代码替换原有文件内容)

package com.example.leaveapproval.repository;

import com.example.leaveapproval.model.Role;
import com.example.leaveapproval.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for the {@link User} entity.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 根据用户名查找用户 (用于登录和唯一性检查)。
     * @param username 用户名
     * @return 包含用户的Optional，如果不存在则为空
     */
    Optional<User> findByUsername(String username);

    /**
     * 根据邮箱查找用户 (用于唯一性检查)。
     * @param email 邮箱地址
     * @return 包含用户的Optional，如果不存在则为空
     */
    Optional<User> findByEmail(String email);

    /**
     * 检查指定的用户名是否已存在。
     * @param username 用户名
     * @return 如果存在返回true，否则返回false
     */
    Boolean existsByUsername(String username);

    /**
     * 检查指定的邮箱是否已存在。
     * @param email 邮箱地址
     * @return 如果存在返回true，否则返回false
     */
    Boolean existsByEmail(String email);

    /**
     * 查找系统中拥有特定角色的所有用户列表。
     * @param role 角色枚举值 (例如 Role.ROLE_TEAM_LEAD)
     * @return 拥有该角色的用户列表；如果不存在，返回空列表
     */
    List<User> findByRolesContaining(Role role);

    /**
     * [为逻辑删除新增] 根据 enabled 状态分页查找用户。
     * 用于获取所有活动用户列表或所有非活动用户列表。
     *
     * @param enabled 用户是否启用 (true for active, false for inactive)
     * @param pageable 分页参数
     * @return 分页的用户数据
     */
    Page<User> findByEnabled(boolean enabled, Pageable pageable);
}
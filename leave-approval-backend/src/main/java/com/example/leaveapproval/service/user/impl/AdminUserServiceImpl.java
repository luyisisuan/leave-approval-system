// 文件路径: com/example/leaveapproval/service/user/impl/AdminUserServiceImpl.java
// (请用以下全部代码替换原有文件内容)

package com.example.leaveapproval.service.user.impl;

import com.example.leaveapproval.dto.AdminUserCreateRequest;
import com.example.leaveapproval.dto.UserDto;
import com.example.leaveapproval.dto.UserUpdateRequest;
import com.example.leaveapproval.exception.ResourceNotFoundException;
import com.example.leaveapproval.model.Role;
import com.example.leaveapproval.model.User;
import com.example.leaveapproval.repository.UserRepository;
import com.example.leaveapproval.service.user.AdminUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class AdminUserServiceImpl implements AdminUserService {

    private static final Logger logger = LoggerFactory.getLogger(AdminUserServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AdminUserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * [已修改] 获取用户列表，默认只显示活动用户 (enabled = true)。
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserDto> getAllUsers(Pageable pageable) {
        logger.info("管理员操作：获取所有活动状态的用户，分页参数：{}", pageable);
        // 修改 userRepository.findAll 为 findByEnabled(true, ...)，只查找活动用户
        return userRepository.findByEnabled(true, pageable).map(UserDto::fromEntity);
    }

    /**
     * [保持不变] 管理员应能通过ID获取任何用户，包括非活动用户。
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<UserDto> getUserById(Long id) {
        logger.info("管理员操作：根据ID获取用户，用户ID：{}", id);
        return userRepository.findById(id).map(UserDto::fromEntity);
    }

    /**
     * [保持不变] 创建用户逻辑。
     */
    @Override
    public UserDto createUser(AdminUserCreateRequest createRequest) {
        logger.info("管理员操作：尝试创建新用户，用户名：{}", createRequest.getUsername());

        if (userRepository.existsByUsername(createRequest.getUsername())) {
            String errorMessage = "错误：用户名 '" + createRequest.getUsername() + "' 已被占用！";
            logger.warn(errorMessage);
            throw new DataIntegrityViolationException(errorMessage);
        }
        if (userRepository.existsByEmail(createRequest.getEmail())) {
            String errorMessage = "错误：邮箱 '" + createRequest.getEmail() + "' 已被使用！";
            logger.warn(errorMessage);
            throw new DataIntegrityViolationException(errorMessage);
        }
        if (createRequest.getRoles() == null || createRequest.getRoles().isEmpty()) {
            String errorMessage = "错误：管理员创建用户时必须分配角色。";
            logger.warn(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }

        User user = new User();
        user.setUsername(createRequest.getUsername());
        user.setPassword(passwordEncoder.encode(createRequest.getPassword()));
        user.setFullName(createRequest.getFullName());
        user.setEmail(createRequest.getEmail());

        if (StringUtils.hasText(createRequest.getDepartment())) {
            user.setDepartment(createRequest.getDepartment());
        }

        if (createRequest.getManagerId() != null) {
            User manager = userRepository.findById(createRequest.getManagerId())
                    .orElseThrow(() -> {
                        String errorMessage = "指定的上级用户 (ID: " + createRequest.getManagerId() + ") 不存在。";
                        logger.warn(errorMessage);
                        return new ResourceNotFoundException("Manager", "id", createRequest.getManagerId());
                    });
            user.setManager(manager);
        }

        user.setRoles(createRequest.getRoles());
        user.setEnabled(true); // 新用户默认为启用状态

        User savedUser = userRepository.save(user);
        logger.info("管理员操作：用户创建成功，用户ID：{}", savedUser.getId());
        return UserDto.fromEntity(savedUser);
    }

    /**
     * [保持不变] 更新用户逻辑，管理员可以更新任何用户，包括重新启用(enabled: true)已禁用的用户。
     */
    @Override
    public Optional<UserDto> updateUser(Long id, UserUpdateRequest updateRequest) {
        logger.info("管理员操作：尝试更新用户，用户ID：{}", id);

        return userRepository.findById(id).flatMap(user -> {
            boolean isModified = false;

            if (StringUtils.hasText(updateRequest.getFullName()) && !updateRequest.getFullName().equals(user.getFullName())) {
                user.setFullName(updateRequest.getFullName());
                isModified = true;
            }

            if (StringUtils.hasText(updateRequest.getEmail()) && !updateRequest.getEmail().equals(user.getEmail())) {
                Optional<User> existingUserWithEmail = userRepository.findByEmail(updateRequest.getEmail());
                if (existingUserWithEmail.isPresent() && !existingUserWithEmail.get().getId().equals(user.getId())) {
                    String errorMessage = "错误：邮箱 '" + updateRequest.getEmail() + "' 已被其他用户使用！";
                    logger.warn(errorMessage);
                    throw new DataIntegrityViolationException(errorMessage);
                }
                user.setEmail(updateRequest.getEmail());
                isModified = true;
            }

            if (updateRequest.getDepartment() != null && !updateRequest.getDepartment().equals(user.getDepartment())) {
                user.setDepartment(updateRequest.getDepartment());
                isModified = true;
            }

            if (updateRequest.getManagerId() != null) {
                if (user.getManager() == null || !updateRequest.getManagerId().equals(user.getManager().getId())) {
                    if (updateRequest.getManagerId().equals(user.getId())) {
                        String errorMsg = "错误：用户不能将自己设置为其直属上级。用户ID: " + id;
                        logger.warn(errorMsg);
                        throw new IllegalArgumentException(errorMsg);
                    }
                    User manager = userRepository.findById(updateRequest.getManagerId())
                            .orElseThrow(() -> {
                                String errorMessage = "指定的上级用户 (ID: " + updateRequest.getManagerId() + ") 不存在。";
                                logger.warn(errorMessage);
                                return new ResourceNotFoundException("Manager", "id", updateRequest.getManagerId());
                            });
                    user.setManager(manager);
                    isModified = true;
                }
            } else if (updateRequest.getManagerId() == null && user.getManager() != null) {
                user.setManager(null);
                isModified = true;
            }

            if (updateRequest.getRoles() != null && !updateRequest.getRoles().isEmpty()) {
                if (!user.getRoles().equals(updateRequest.getRoles())) {
                    user.setRoles(updateRequest.getRoles());
                    isModified = true;
                }
            } else if (updateRequest.getRoles() != null && updateRequest.getRoles().isEmpty()){
                String errorMessage = "错误：更新用户时角色列表不能为空。如需禁用用户，请使用 'enabled' 标志。";
                logger.warn(errorMessage);
                throw new IllegalArgumentException(errorMessage);
            }

            if (updateRequest.getEnabled() != null && updateRequest.getEnabled() != user.isEnabled()) {
                user.setEnabled(updateRequest.getEnabled());
                isModified = true;
            }

            if (isModified) {
                User updatedUser = userRepository.save(user);
                logger.info("管理员操作：用户更新成功，用户ID：{}", updatedUser.getId());
                return Optional.of(UserDto.fromEntity(updatedUser));
            } else {
                logger.info("管理员操作：未检测到用户信息的实际更改，用户ID：{}。返回现有数据。", id);
                return Optional.of(UserDto.fromEntity(user));
            }
        });
    }

    /**
     * [重要修改] 实现逻辑删除 (Soft Delete)。
     * 不再从数据库物理删除用户，而是将其 enabled 状态更新为 false。
     */
    @Override
    public void deleteUser(Long id) {
        logger.info("管理员操作：尝试禁用（逻辑删除）用户，用户ID：{}", id);

        // 1. 查找用户，如果不存在则抛出异常
        User user = userRepository.findById(id)
                .orElseThrow(() -> {
                    String errorMessage = "错误：尝试禁用的用户 (ID: " + id + ") 不存在。";
                    logger.warn(errorMessage);
                    return new ResourceNotFoundException("User", "id", id);
                });

        // 2. 将用户的 enabled 状态设置为 false
        user.setEnabled(false);

        // 3. 保存更新后的用户状态
        userRepository.save(user);

        logger.info("管理员操作：用户禁用（逻辑删除）成功，用户ID：{}", id);
    }

    /**
     * [已修改] 获取经理列表，确保只返回活动状态的经理 (enabled = true)。
     */
    @Override
    @Transactional(readOnly = true)
    public List<UserDto> getPotentialManagers() {
        logger.info("获取所有活动状态的潜在经理用户列表。");

        Set<Role> managerRoles = Set.of(
                Role.ROLE_TEAM_LEAD,
                Role.ROLE_DEPT_MANAGER,
                Role.ROLE_HR,
                Role.ROLE_ADMIN
        );

        Set<User> potentialManagersSet = new HashSet<>();

        for (Role role : managerRoles) {
            potentialManagersSet.addAll(userRepository.findByRolesContaining(role));
        }

        return potentialManagersSet.stream()
                // 新增过滤器：只包含 enabled 状态为 true 的用户
                .filter(User::isEnabled)
                .map(UserDto::fromEntity)
                .sorted(Comparator.comparing(UserDto::getFullName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }
}
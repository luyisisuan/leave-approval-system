package com.example.leaveapproval.service.leave.impl;

import com.example.leaveapproval.dto.ApprovalActionDto;
import com.example.leaveapproval.dto.ApprovalHistoryViewDto;
import com.example.leaveapproval.dto.LeaveRequestCreateDto;
import com.example.leaveapproval.dto.LeaveRequestViewDto;
import com.example.leaveapproval.exception.ResourceNotFoundException;
import com.example.leaveapproval.model.*; // User, LeaveRequest, ApprovalHistory, Role, LeaveStatus, LeaveType
import com.example.leaveapproval.model.state.LeaveState; // 明确导入 LeaveState 接口
import com.example.leaveapproval.repository.ApprovalHistoryRepository;
import com.example.leaveapproval.repository.LeaveRequestRepository;
import com.example.leaveapproval.repository.UserRepository;
import com.example.leaveapproval.service.approval.chain.Approver;
import com.example.leaveapproval.service.leave.LeaveRequestManagementService;
import com.example.leaveapproval.service.leave.LeaveRequestProcessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
public class LeaveRequestManagementServiceImpl implements LeaveRequestManagementService {

    private static final Logger logger = LoggerFactory.getLogger(LeaveRequestManagementServiceImpl.class);

    private final LeaveRequestRepository leaveRequestRepository;
    private final UserRepository userRepository;
    private final ApprovalHistoryRepository approvalHistoryRepository;
    private final LeaveRequestProcessService leaveRequestProcessService;
    private final ApplicationContext applicationContext;

    @Autowired
    public LeaveRequestManagementServiceImpl(
            LeaveRequestRepository leaveRequestRepository,
            UserRepository userRepository,
            ApprovalHistoryRepository approvalHistoryRepository,
            @Qualifier("genericLeaveProcessService") LeaveRequestProcessService leaveRequestProcessService,
            ApplicationContext applicationContext) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.userRepository = userRepository;
        this.approvalHistoryRepository = approvalHistoryRepository;
        this.leaveRequestProcessService = leaveRequestProcessService;
        this.applicationContext = applicationContext;
    }

    private User getCurrentAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof User)) {
            String principalName = authentication != null ? authentication.getPrincipal().toString() : "null";
            logger.warn("无法获取当前认证用户，认证信息主体为: {}", principalName);
            throw new IllegalStateException("用户未登录或认证信息无效。请重新登录。");
        }
        return (User) authentication.getPrincipal();
    }

    @Override
    public LeaveRequestViewDto submitLeaveRequest(LeaveRequestCreateDto createDto) {
        logger.info("接收到新的请假申请提交请求。");
        LeaveRequestViewDto createdLeaveRequest = leaveRequestProcessService.submitLeaveRequest(createDto);
        logger.info("请假申请 (ID: {}) 已成功提交并启动审批流程，当前状态: {}",
                createdLeaveRequest.getId(), createdLeaveRequest.getStatus());
        return createdLeaveRequest;
    }

    /**
     * [重要] 此方法已被完全重构，以支持Admin的通用审批权限。
     * Admin可以代表当前指定的审批人执行操作，绕过权限检查。
     */
    @Override
    public LeaveRequestViewDto processApprovalAction(Long leaveRequestId, ApprovalActionDto actionDto, Long approverUserId) {
        logger.info("用户ID {} 正在尝试处理请假申请ID {}，决定：{}",
                approverUserId, leaveRequestId, actionDto.getDecision());

        LeaveRequest leaveRequest = leaveRequestRepository.findById(leaveRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("LeaveRequest", "id", leaveRequestId));

        User actionTakingUser = userRepository.findById(approverUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User (Approver)", "id", approverUserId));

        // 集中进行Admin角色检查
        boolean isAdminAction = actionTakingUser.getRoles().contains(Role.ROLE_ADMIN);

        // 获取当前流程指定的审批人
        User designatedApprover = leaveRequest.getCurrentApprover();

        // 重构后的权限检查逻辑：
        // 规则：如果操作者不是Admin，那么他必须是当前指定的审批人。
        if (!isAdminAction && (designatedApprover == null || !designatedApprover.getId().equals(actionTakingUser.getId()))) {
            String designatedApproverUsername = designatedApprover != null ? designatedApprover.getUsername() : "未指定";
            String errorMsg = String.format("权限不足：用户 '%s' 不是请假申请 %d 的当前指定审批人 ('%s')。",
                    actionTakingUser.getUsername(), designatedApproverUsername, leaveRequestId);
            logger.warn(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        // 处理审批链中断的特殊情况：申请在待审批，但没有指定审批人。只有Admin能处理。
        if (designatedApprover == null && leaveRequest.getStatusEnum() == LeaveStatus.PENDING_APPROVAL) {
            if (isAdminAction) {
                logger.warn("请假申请 {} 处于待审批状态但无指定审批人。Admin '{}' 将接管处理。",
                        leaveRequestId, actionTakingUser.getUsername());
                // 在这种特殊情况下，我们将Admin视为“名义上的”指定审批人，以便找到正确的审批节点
                designatedApprover = actionTakingUser;
            } else {
                String errorMsg = String.format("系统错误：请假申请 %d 处于待审批状态但没有指定审批人，非Admin无法处理。", leaveRequestId);
                logger.error(errorMsg);
                throw new IllegalStateException(errorMsg);
            }
        } else if (designatedApprover == null) {
            // 其他状态下如果没有指定审批人，说明流程已结束或异常
            String errorMsg = String.format("系统错误：请假申请 %d 状态为 %s 且无指定审批人，无法处理。",
                    leaveRequestId, leaveRequest.getStatusEnum());
            logger.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        // 状态转换逻辑保持不变
        LeaveState currentLeaveState = leaveRequest.getCurrentState();
        try {
            if (actionDto.getDecision() == ApprovalHistory.Decision.APPROVED) {
                currentLeaveState.approve(leaveRequest, actionTakingUser, actionDto.getDecision(), actionDto.getComments());
            } else if (actionDto.getDecision() == ApprovalHistory.Decision.REJECTED) {
                currentLeaveState.reject(leaveRequest, actionTakingUser, actionDto.getDecision(), actionDto.getComments());
            } else {
                throw new IllegalArgumentException("无效的审批操作决定类型: " + actionDto.getDecision());
            }
        } catch (IllegalStateException e) {
            logger.warn("为请假申请 {} 执行状态转换失败: {}", leaveRequestId, e.getMessage());
            throw e;
        }

        // 始终基于“指定审批人”来查找审批节点，以确保执行正确的业务逻辑
        Approver approverNode = getApproverNodeForUser(designatedApprover);

        if (approverNode == null) {
            // 如果仍然找不到节点，说明系统配置有误
            String designatedApproverInfo = String.format("%s (ID: %d, 角色: %s)",
                    designatedApprover.getUsername(), designatedApprover.getId(), designatedApprover.getRoles());
            String errorMsg = String.format(
                    "系统错误：无法为当前应审批用户 %s 找到对应的审批处理者配置。请假ID: %d",
                    designatedApproverInfo, leaveRequestId);
            logger.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        try {
            // 关键：让 Admin (actionTakingUser) 去执行“指定审批人”节点的业务逻辑。
            approverNode.handleApprovalAction(leaveRequest, actionTakingUser, actionDto.getDecision(), actionDto.getComments());
            logger.info("请假申请 ID {} 已由用户 '{}' 代表审批节点 '{}' 成功处理。",
                    leaveRequestId, actionTakingUser.getUsername(), approverNode.getClass().getSimpleName());
        } catch (IllegalStateException | IllegalArgumentException e) {
            logger.error("在责任链环节处理请假申请 {} 失败: {}", leaveRequestId, e.getMessage(), e);
            throw e;
        }

        LeaveRequest updatedRequest = leaveRequestRepository.findById(leaveRequestId)
                .orElseThrow(() -> new InternalError("严重错误：处理审批后无法重新获取请假申请 " + leaveRequestId));

        return populateLeaveRequestViewDto(updatedRequest);
    }

    private Approver getApproverNodeForUser(User user) {
        if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
            logger.warn("尝试为没有角色或为null的用户获取Approver节点：{}", user != null ? user.getUsername() : "null用户");
            return null;
        }
        if (user.getRoles().contains(Role.ROLE_HR)) {
            return applicationContext.getBean("hrApprover", Approver.class);
        } else if (user.getRoles().contains(Role.ROLE_DEPT_MANAGER)) {
            return applicationContext.getBean("deptManagerApprover", Approver.class);
        } else if (user.getRoles().contains(Role.ROLE_TEAM_LEAD)) {
            return applicationContext.getBean("teamLeadApprover", Approver.class);
        }
        // 如果用户只有Admin角色（比如修复中断的流程时），可以默认给他一个高权限节点（如HR）作为入口
        if (user.getRoles().contains(Role.ROLE_ADMIN)) {
            logger.info("为Admin角色 {} 指定HR审批节点作为入口以处理流程。", user.getUsername());
            return applicationContext.getBean("hrApprover", Approver.class);
        }
        logger.warn("用户 {} (ID: {}) 具有角色 {}，但没有匹配的特定审批处理者节点配置用于启动审批链。如果操作者是Admin，其权限仍将在审批链内部处理。",
                user.getUsername(), user.getId(), user.getRoles());
        return null;
    }

    @Override
    public LeaveRequestViewDto cancelLeaveRequest(Long leaveRequestId, Long applicantId) {
        logger.info("用户ID {} 尝试取消请假申请ID {}", applicantId, leaveRequestId);
        LeaveRequest leaveRequest = leaveRequestRepository.findById(leaveRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("LeaveRequest", "id", leaveRequestId));
        User actionTaker = userRepository.findById(applicantId)
                .orElseThrow(() -> new ResourceNotFoundException("User (Action Taker)", "id", applicantId));

        if (!leaveRequest.getApplicant().getId().equals(actionTaker.getId())) {
            String errorMsg = String.format("权限不足：用户 %s 不是请假申请 %d 的申请人。",
                    actionTaker.getUsername(), leaveRequestId);
            logger.warn(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        try {
            leaveRequest.cancel(actionTaker);
            LeaveRequest cancelledRequest = leaveRequestRepository.save(leaveRequest);
            logger.info("请假申请 ID: {} 已被申请人 {} 成功取消，新状态: {}",
                    cancelledRequest.getId(), actionTaker.getUsername(), cancelledRequest.getStatusEnum());
            return populateLeaveRequestViewDto(cancelledRequest);
        } catch (IllegalStateException e) {
            logger.warn("取消操作失败 (ID: {}，操作人: {}): {}", leaveRequestId, actionTaker.getUsername(), e.getMessage(), e);
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LeaveRequestViewDto> getLeaveRequestDetailsById(Long leaveRequestId) {
        logger.debug("查询请假申请详情，ID: {}", leaveRequestId);
        return leaveRequestRepository.findById(leaveRequestId)
                .map(this::populateLeaveRequestViewDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequestViewDto> getMyLeaveRequests(Long applicantId, Pageable pageable) {
        logger.debug("用户ID {} 查询我的请假申请，分页：{}", applicantId, pageable);
        return leaveRequestRepository.findByApplicantId(applicantId, pageable)
                .map(this::populateLeaveRequestViewDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequestViewDto> getPendingApprovalRequestsForUser(Long approverId, LeaveStatus status, Pageable pageable) {
        LeaveStatus queryStatus = (status == null) ? LeaveStatus.PENDING_APPROVAL : status;
        logger.debug("审批人ID {} 查询状态为 {} 的请假申请列表，分页：{}", approverId, queryStatus, pageable);
        return leaveRequestRepository.findByCurrentApproverIdAndStatusEnum(approverId, queryStatus, pageable)
                .map(this::populateLeaveRequestViewDto);
    }

    // 新增方法：统一的待审批列表获取入口
    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequestViewDto> getPendingApprovals(User currentUser, Pageable pageable) {
        // 判断当前用户是否拥有Admin角色
        boolean isAdmin = currentUser.getRoles().contains(Role.ROLE_ADMIN);

        if (isAdmin) {
            logger.info("Admin用户 {} 正在获取所有待审批的申请列表。", currentUser.getUsername());
            // 如果是Admin，调用已有的方法获取所有待审批申请
            return adminGetAllPendingRequests(LeaveStatus.PENDING_APPROVAL, pageable);
        } else {
            logger.info("普通用户 {} 正在获取分配给TA的待审批申请列表。", currentUser.getUsername());
            // 如果是普通用户，执行原逻辑，只查找分配给自己的申请
            return getPendingApprovalRequestsForUser(currentUser.getId(), LeaveStatus.PENDING_APPROVAL, pageable);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequestViewDto> adminGetAllPendingRequests(LeaveStatus status, Pageable pageable) {
        LeaveStatus queryStatus = (status == null) ? LeaveStatus.PENDING_APPROVAL : status;
        logger.info("Admin 操作：获取所有状态为 {} 的请假申请，分页：{}", queryStatus, pageable);
        return leaveRequestRepository.findByStatusEnum(queryStatus, pageable)
                .map(this::populateLeaveRequestViewDto);
    }

    private LeaveRequestViewDto populateLeaveRequestViewDto(LeaveRequest leaveRequest) {
        if (leaveRequest == null) return null;
        LeaveRequestViewDto dto = LeaveRequestViewDto.fromEntity(leaveRequest);
        if (dto != null) {
            List<ApprovalHistory> histories = approvalHistoryRepository.findByLeaveRequestOrderByApprovedAtAsc(leaveRequest);
            dto.setApprovalHistory(
                    histories.stream()
                            .map(ApprovalHistoryViewDto::fromEntity)
                            .collect(Collectors.toList())
            );
            logger.trace("为请假申请ID {} 填充了 {} 条审批历史记录。", leaveRequest.getId(), histories.size());
        }
        return dto;
    }
}
<script setup>
import { ref, onMounted, computed, reactive, watch } from 'vue';
import adminUserService from '../services/adminUserService';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Edit, Delete, Refresh, User as UserIcon, Key, Tickets as NameIcon, Message as EmailIcon, OfficeBuilding, CollectionTag, CircleCheck, CircleClose } from '@element-plus/icons-vue';

// --- 状态管理 ---
const users = ref([]);
const paginationData = ref({
  content: [], totalPages: 0, totalElements: 0, number: 0, size: 10,
});
const isLoadingTable = ref(false);
const pageError = ref(null);

const availableRoles = ref([
  { value: 'ROLE_EMPLOYEE', text: '员工' },
  { value: 'ROLE_TEAM_LEAD', text: '团队领导' },
  { value: 'ROLE_DEPT_MANAGER', text: '部门经理' },
  { value: 'ROLE_HR', text: 'HR' },
  { value: 'ROLE_ADMIN', text: '管理员' },
]);

const potentialManagers = ref([]);
const isLoadingPotentialManagers = ref(false);
const fetchPotentialManagersError = ref(null);

// --- 数据获取 ---
const fetchPotentialManagersForModal = async () => {
  isLoadingPotentialManagers.value = true;
  fetchPotentialManagersError.value = null;
  try {
    const data = await adminUserService.getPotentialManagers();
    potentialManagers.value = data.map(user => ({
      id: user.id,
      displayText: `${user.fullName || user.username} (${Array.isArray(user.roles) ? user.roles.map(r => r.replace('ROLE_', '')).join(', ') : 'N/A'})`
    }));
  } catch (err) {
    fetchPotentialManagersError.value = err.response?.data?.message || err.message || "无法加载可选经理列表。";
    potentialManagers.value = [];
  } finally {
    isLoadingPotentialManagers.value = false;
  }
};

const fetchUsers = async (page = 0, size = 10) => {
  isLoadingTable.value = true;
  pageError.value = null;
  try {
    const responseData = await adminUserService.getAllUsers({ page, size, sort: 'id,asc' });
    users.value = responseData.content || [];
    paginationData.value = {
      content: responseData.content || [],
      totalPages: responseData.totalPages || 0,
      totalElements: responseData.totalElements || 0,
      number: responseData.number || 0,
      size: responseData.size || size,
    };
  } catch (err) {
    pageError.value = '加载用户列表失败。';
    users.value = [];
  } finally {
    isLoadingTable.value = false;
  }
};

onMounted(() => {
  fetchUsers();
  fetchPotentialManagersForModal();
});

// --- 创建/编辑用户对话框相关 ---
const isUserDialogVisible = ref(false);
const dialogMode = ref('create');
const editingUserId = ref(null);
const userFormRef = ref(null);

const initialUserForm = {
  username: '', email: '', password: '', confirmPassword: '',
  fullName: '', department: '', managerId: null,
  roles: ['ROLE_EMPLOYEE'], enabled: true,
};
const userForm = reactive({ ...initialUserForm });

const passwordStrength = computed(() => {
  const pass = userForm.password;
  let score = 0;
  if (!pass || pass.length < 6) return { score: 0, text: '太短', color: '#F56C6C' };
  if (pass.length >= 8) score++;
  if (/\d/.test(pass)) score++;
  if (/[a-z]/.test(pass) && /[A-Z]/.test(pass)) score++;
  if (/[^a-zA-Z0-9]/.test(pass)) score++;

  if (score <= 2) return { score, text: '弱', color: '#F56C6C' };
  if (score === 3) return { score, text: '中等', color: '#E6A23C' };
  return { score, text: '强', color: '#67C23A' };
});

watch(() => userForm.roles, (newRoles) => {
  const managementRoles = ['ROLE_TEAM_LEAD', 'ROLE_DEPT_MANAGER', 'ROLE_HR', 'ROLE_ADMIN'];
  const hasManagementRole = newRoles.some(role => managementRoles.includes(role));

  if (hasManagementRole && !newRoles.includes('ROLE_EMPLOYEE')) {
    userForm.roles.push('ROLE_EMPLOYEE');
  }
});

const userFormRules = reactive({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '长度在 3 到 20 个字符', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' },
  ],
  password: [
    { validator: (rule, value, callback) => {
        if (dialogMode.value === 'create' && !value) callback(new Error('创建用户时密码不能为空'));
        else if (dialogMode.value === 'create' && (value.length < 6 || value.length > 40)) callback(new Error('密码长度需为 6 到 40 个字符'));
        else callback();
      }, trigger: 'blur'
    },
  ],
  confirmPassword: [
    { validator: (rule, value, callback) => {
        if (dialogMode.value === 'create' && !value) callback(new Error('请再次输入密码'));
        else if (dialogMode.value === 'create' && value !== userForm.password) callback(new Error('两次输入密码不一致'));
        else callback();
      }, trigger: 'blur'
    },
  ],
  fullName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  roles: [{ type: 'array', required: true, message: '请至少选择一个角色', trigger: 'change' }],
});

const userFormServerError = ref(null);
const isSubmittingUserForm = ref(false);

const openCreateUserDialog = () => {
  dialogMode.value = 'create';
  editingUserId.value = null;
  Object.assign(userForm, initialUserForm);
  if (userFormRef.value) {
    userFormRef.value.clearValidate();
  }
  userFormServerError.value = null;
  if (potentialManagers.value.length === 0 && !isLoadingPotentialManagers.value) {
    fetchPotentialManagersForModal();
  }
  isUserDialogVisible.value = true;
};

const openEditUserDialog = async (user) => {
  dialogMode.value = 'edit';
  editingUserId.value = user.id;
  if (potentialManagers.value.length === 0 && !isLoadingPotentialManagers.value) {
    fetchPotentialManagersForModal();
  }
  isSubmittingUserForm.value = true;
  userFormServerError.value = null;
  try {
    const fullUserData = await adminUserService.getUserById(user.id);
    Object.assign(userForm, {
      username: fullUserData.username, email: fullUserData.email, password: '', confirmPassword: '',
      fullName: fullUserData.fullName, department: fullUserData.department || '', managerId: fullUserData.managerId || null,
      roles: fullUserData.roles ? fullUserData.roles.map(role => (typeof role === 'string' ? role : role.name || role.value)) : [],
      enabled: fullUserData.enabled,
    });
    isUserDialogVisible.value = true;
  } catch (err) {
    ElMessage.error('获取用户详情失败: ' + (err.response?.data?.message || err.message));
  } finally {
    isSubmittingUserForm.value = false;
  }
};

const handleUserDialogClose = () => {
  if (userFormRef.value) {
    userFormRef.value.resetFields();
  }
  Object.assign(userForm, initialUserForm);
  editingUserId.value = null;
};

const handleUserFormSubmit = async () => {
  if (!userFormRef.value) return;
  await userFormRef.value.validate(async (valid) => {
    if (valid) {
      isSubmittingUserForm.value = true;
      userFormServerError.value = null;
      try {
        if (dialogMode.value === 'edit') {
          const updateData = {
            fullName: userForm.fullName, email: userForm.email, department: userForm.department,
            managerId: userForm.managerId ? Number(userForm.managerId) : null,
            roles: userForm.roles, enabled: userForm.enabled,
          };
          await adminUserService.updateUser(editingUserId.value, updateData);
          ElMessage.success(`用户 '${userForm.fullName}' 的信息已更新！`);
        } else {
          const createData = {
            username: userForm.username, email: userForm.email, password: userForm.password,
            fullName: userForm.fullName, department: userForm.department,
            managerId: userForm.managerId ? Number(userForm.managerId) : null, roles: userForm.roles,
          };
          if (userForm.enabled !== undefined) createData.enabled = userForm.enabled;
          await adminUserService.createUser(createData);
          ElMessage.success(`用户 '${userForm.fullName}' 已成功创建！`);
        }
        isUserDialogVisible.value = false;
        fetchUsers(paginationData.value.number, paginationData.value.size);
      } catch (err) {
        userFormServerError.value = err.response?.data?.message || err.message || '操作失败。';
        ElMessage.error(userFormServerError.value);
      } finally {
        isSubmittingUserForm.value = false;
      }
    } else {
      ElMessage.error('表单校验未通过，请检查输入。');
      return false;
    }
  });
};

const handleDeleteUser = async (userId, username) => {
  try {
    await ElMessageBox.confirm(
        `您确定要禁用用户 "${username}" (ID: ${userId}) 吗？<br/>这是一种安全的操作（逻辑删除），用户的历史记录将被保留，账户可以随时被重新启用。`,
        '确认禁用账户',
        {
          type: 'warning',
          confirmButtonText: '确定禁用',
          cancelButtonText: '取消',
          draggable: true,
          dangerouslyUseHTMLString: true
        }
    );
    isLoadingTable.value = true;
    await adminUserService.deleteUser(userId);
    ElMessage.success(`用户 "${username}" 已成功禁用！`);
    if (users.value.length === 1 && paginationData.value.number > 0) {
      fetchUsers(paginationData.value.number - 1, paginationData.value.size);
    } else {
      fetchUsers(paginationData.value.number, paginationData.value.size);
    }
  } catch (actionOrError) {
    if (actionOrError !== 'cancel' && actionOrError !== 'close') {
      ElMessage.error('操作失败: ' + (actionOrError.response?.data?.message || actionOrError.message));
    } else {
      ElMessage.info('操作已取消。');
    }
  } finally {
    isLoadingTable.value = false;
  }
};

const formatRolesForDisplay = (roles) => {
  if (!Array.isArray(roles) || roles.length === 0) return '未分配';
  return roles.map(role => {
    const roleObj = availableRoles.value.find(r => r.value === (typeof role === 'string' ? role : role.name || role.value));
    return roleObj ? roleObj.text : (typeof role === 'string' ? role.replace('ROLE_', '') : '未知');
  }).join(' | ');
};

const handlePageChange = (newPage) => fetchUsers(newPage - 1, paginationData.value.size);
const handleSizeChange = (newSize) => fetchUsers(0, newSize);
</script>

<template>
  <div class="admin-users-page-el">
    <el-card shadow="never" class="page-header-card-el">
      <div class="page-header-content-el">
        <h1><el-icon style="vertical-align: middle; margin-right: 8px;"><UserIcon /></el-icon>用户账户管理</h1>
        <el-button type="primary" :icon="Plus" @click="openCreateUserDialog" round>创建新用户</el-button>
      </div>
    </el-card>

    <div v-if="pageError" style="margin-top: 20px;">
      <el-alert :title="pageError" type="error" show-icon :closable="false" />
    </div>

    <el-table :data="users" v-loading="isLoadingTable" style="width: 100%; margin-top: 20px;" stripe border class="users-table-el" empty-text="当前没有用户数据">
      <el-table-column prop="id" label="ID" width="70" align="center" sortable />
      <el-table-column prop="username" label="用户名" min-width="120" sortable show-overflow-tooltip />
      <el-table-column prop="fullName" label="姓名" min-width="120" sortable show-overflow-tooltip />
      <el-table-column prop="email" label="邮箱" min-width="180" sortable show-overflow-tooltip />
      <el-table-column prop="department" label="部门" min-width="100" show-overflow-tooltip />
      <el-table-column label="直属经理" min-width="120" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.managerUsername || '-' }}</template>
      </el-table-column>
      <el-table-column label="角色" min-width="150" show-overflow-tooltip>
        <template #default="scope">
          <el-tooltip effect="dark" :content="formatRolesForDisplay(scope.row.roles)" placement="top">
            <span class="roles-display">{{ formatRolesForDisplay(scope.row.roles) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.enabled ? 'success' : 'danger'" effect="dark" round size="small">
            {{ scope.row.enabled ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center" fixed="right">
        <template #default="scope">
          <el-button-group>
            <el-button type="primary" link :icon="Edit" size="small" @click="openEditUserDialog(scope.row)">编辑</el-button>
            <el-button type="danger" link :icon="Delete" size="small" @click="handleDeleteUser(scope.row.id, scope.row.username)">禁用</el-button>
          </el-button-group>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="系统中没有用户数据。"><el-button type="primary" @click="openCreateUserDialog" :icon="Plus">立即创建第一个用户</el-button></el-empty>
      </template>
    </el-table>

    <div class="pagination-container-el" v-if="paginationData.totalPages > 0">
      <el-pagination background layout="total, sizes, prev, pager, next, jumper" :total="paginationData.totalElements"
                     :page-sizes="[10, 20, 50, 100]" :current-page="paginationData.number + 1" :page-size="paginationData.size"
                     @size-change="handleSizeChange" @current-change="handlePageChange" />
    </div>

    <el-dialog v-model="isUserDialogVisible" :title="dialogMode === 'create' ? '创建新用户账户' : `编辑用户 - ${userForm.username}`"
               width="650px" draggable destroy-on-close @closed="handleUserDialogClose" append-to-body custom-class="user-dialog-el" top="8vh">
      <el-form ref="userFormRef" :model="userForm" :rules="userFormRules" label-position="top" require-asterisk-position="right" status-icon>

        <el-divider content-position="left">账户信息</el-divider>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <!-- 修改：添加 autocomplete="off" -->
              <el-input v-model.trim="userForm.username" :disabled="dialogMode === 'edit'" placeholder="设置登录用户名 (创建后不可修改)" :prefix-icon="UserIcon" clearable autocomplete="off" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="电子邮箱" prop="email">
              <!-- 修改：添加 autocomplete="off" -->
              <el-input v-model.trim="userForm.email" placeholder="请输入邮箱地址" :prefix-icon="EmailIcon" clearable autocomplete="off" />
            </el-form-item>
          </el-col>
        </el-row>
        <div v-if="dialogMode === 'create'">
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="设置密码" prop="password">
                <!-- 修改：添加 autocomplete="new-password" -->
                <el-input type="password" v-model="userForm.password" placeholder="输入登录密码" :prefix-icon="Key" show-password clearable autocomplete="new-password" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="确认密码" prop="confirmPassword">
                <!-- 修改：添加 autocomplete="new-password" -->
                <el-input type="password" v-model="userForm.confirmPassword" placeholder="再次输入密码" :prefix-icon="Key" show-password clearable autocomplete="new-password" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row v-if="userForm.password">
            <el-col :span="24">
              <div class="password-strength-meter">
                <div class="strength-label" :style="{ color: passwordStrength.color }">密码强度: {{ passwordStrength.text }}</div>
                <el-progress :percentage="(passwordStrength.score / 4) * 100" :stroke-width="6" :color="passwordStrength.color" :show-text="false" />
              </div>
            </el-col>
          </el-row>
        </div>

        <el-divider content-position="left">个人信息</el-divider>
        <el-form-item label="用户姓名" prop="fullName">
          <el-input v-model.trim="userForm.fullName" placeholder="用户的真实姓名" :prefix-icon="NameIcon" clearable />
        </el-form-item>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="所属部门" prop="department">
              <el-input v-model.trim="userForm.department" placeholder="例如：研发部、市场部 (可选)" :prefix-icon="OfficeBuilding" clearable />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="直属经理 (可选)" prop="managerId">
              <el-select v-model="userForm.managerId" placeholder="选择用户的直属经理" clearable filterable style="width: 100%;" :loading="isLoadingPotentialManagers">
                <el-option label="-- 不指定或移除经理 --" :value="null" />
                <el-option v-for="manager in potentialManagers.filter(m => dialogMode === 'create' || m.id !== editingUserId)" :key="manager.id" :label="manager.displayText" :value="manager.id" />
              </el-select>
              <div v-if="isLoadingPotentialManagers" class="el-form-item__help">加载中...</div>
              <div v-if="fetchPotentialManagersError" class="el-form-item__error">{{ fetchPotentialManagersError }}</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">权限设置</el-divider>
        <el-form-item label="分配角色" prop="roles">
          <el-checkbox-group v-model="userForm.roles" class="roles-checkbox-group-el">
            <el-checkbox v-for="roleOption in availableRoles" :key="roleOption.value" :label="roleOption.value" border>{{ roleOption.text }}</el-checkbox>
          </el-checkbox-group>
          <div class="el-form-item__help">提示：选择管理类角色会自动为您保留“员工”基础角色。</div>
        </el-form-item>
        <el-form-item label="账户状态" prop="enabled">
          <el-switch v-model="userForm.enabled" active-text="启用" inactive-text="禁用" :active-icon="CircleCheck" :inactive-icon="CircleClose" inline-prompt
                     style="--el-switch-on-color: var(--el-color-success); --el-switch-off-color: var(--el-color-danger)" />
        </el-form-item>
        <el-alert v-if="userFormServerError" :title="userFormServerError" type="error" show-icon :closable="false" style="margin-bottom: 20px;" />
      </el-form>
      <template #footer>
        <el-button @click="isUserDialogVisible = false" :disabled="isSubmittingUserForm">取 消</el-button>
        <el-button type="primary" @click="handleUserFormSubmit" :loading="isSubmittingUserForm" :icon="dialogMode === 'create' ? Plus : Edit">
          {{ dialogMode === 'create' ? '确认创建' : '保存更新' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-users-page-el {
  padding: 20px;
  background-color: var(--el-bg-color-page);
}
.page-header-card-el {
  margin-bottom: 25px;
  border-radius: 8px;
}
.page-header-content-el {
  padding: 15px 25px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.page-header-card-el h1 {
  margin: 0; font-size: 1.7rem; color: var(--el-text-color-primary); font-weight: 500; display: flex; align-items: center;
}
.users-table-el {
  border-radius: 8px; overflow: hidden; box-shadow: var(--el-box-shadow-light);
}
.users-table-el .el-table__header-wrapper th {
  background-color: var(--el-fill-color-light) !important; color: var(--el-text-color-regular); font-weight: 500; padding: 12px 10px;
}
.users-table-el .el-table__body td {
  padding: 10px 10px; color: var(--el-text-color-regular);
}
.roles-display {
  display: inline-block; max-width: 180px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; vertical-align: middle;
}
.el-table__fixed-right .el-button-group {
  gap: 8px;
}
.pagination-container-el {
  margin-top: 25px; display: flex; justify-content: flex-end;
}

.user-dialog-el .el-dialog__body {
  padding: 10px 30px 25px;
}
.user-dialog-el .el-form-item {
  margin-bottom: 22px;
}
.user-dialog-el .el-divider {
  margin: 10px 0 25px 0;
}
.user-dialog-el .el-divider__text {
  font-weight: 500;
  color: var(--el-text-color-regular);
}
.roles-checkbox-group-el .el-checkbox {
  margin-right: 20px;
  margin-bottom: 8px;
}
.roles-checkbox-group-el .el-checkbox.is-bordered {
  padding: 8px 15px;
}
.user-dialog-el .el-dialog__footer {
  padding: 18px 30px;
  border-top: 1px solid var(--el-border-color-light);
}
.el-form-item__help, .el-form-item__error {
  font-size: 12px;
  padding-top: 2px;
  line-height: 1;
}
.password-strength-meter {
  margin-top: -10px;
  margin-bottom: 15px;
  padding: 0 5px;
}
.strength-label {
  font-size: 12px;
  margin-bottom: 4px;
  text-align: left;
}
</style>
<script setup>
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { ElMessage } from 'element-plus'; // 用于消息提示
import { User, Lock, Promotion } from '@element-plus/icons-vue'; // 引入图标

const authStore = useAuthStore();
const router = useRouter();

const loginFormRef = ref(null); // 用于表单校验

const loginForm = reactive({
  username: '',
  password: '',
});

const clientErrorMessage = ref(''); // 仍然保留用于非常规的客户端提示

// 表单校验规则 (Element Plus 的方式)
const loginRules = reactive({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, message: '用户名长度至少为3个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少为6个字符', trigger: 'blur' },
  ],
});

const handleLogin = async () => {
  clientErrorMessage.value = '';
  if (authStore.loginStatus.error) {
    authStore.loginStatus.error = null;
  }

  if (!loginFormRef.value) return; // 确保表单实例存在
  await loginFormRef.value.validate(async (valid) => {
    if (valid) {
      const success = await authStore.login({
        username: loginForm.username,
        password: loginForm.password,
      });

      if (success) {
        ElMessage({
          message: '登录成功，即将跳转...',
          type: 'success',
          duration: 1500,
        });
        // 跳转逻辑已在 authStore.login action 中处理或此处处理
        // router.push(authStore.returnUrl || '/dashboard');
      } else {
        // 错误信息由 authStore.loginStatus.error 提供，并在模板中显示
        // Element Plus 的 ElMessage 也可以用来显示错误
        if (authStore.loginStatus.error) {
          ElMessage.error(authStore.loginStatus.error);
        } else {
          ElMessage.error('登录失败，请检查您的凭据或网络。');
        }
      }
    } else {
      console.log('LoginPage (Element Plus): 表单校验失败!');
      ElMessage.error('请检查表单输入项。');
      return false;
    }
  });
};
</script>

<template>
  <div class="login-page-wrapper-el">
    <el-card class="login-card-el" shadow="always">
      <template #header>
        <div class="card-header-el">
          <el-icon :size="32" color="var(--el-color-primary)"><Promotion /></el-icon>
          <span>欢迎登录请假审批系统</span>
        </div>
      </template>
      <el-form
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        label-position="top"
        class="login-form-el"
        @submit.prevent="handleLogin"
        hide-required-asterisk
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            :prefix-icon="User"
            clearable
            size="large"
            name="username"
            autocomplete="off"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            type="password"
            v-model="loginForm.password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            clearable
            size="large"
            @keyup.enter="handleLogin"
            name="password"
            autocomplete="new-password"
          />
        </el-form-item>

        <el-form-item v-if="authStore.loginStatus.error" class="form-item-error">
          <el-alert
            :title="authStore.loginStatus.error"
            type="error"
            show-icon
            :closable="false"
          />
        </el-form-item>
        <el-form-item v-if="clientErrorMessage" class="form-item-error">
           <el-alert
            :title="clientErrorMessage"
            type="warning"
            show-icon
            :closable="false"
          />
        </el-form-item>


        <el-form-item class="login-button-container">
          <el-button
            type="primary"
            @click="handleLogin"
            :loading="authStore.loginStatus.isLoading"
            class="login-submit-btn-el"
            size="large"
            round
          >
            {{ authStore.loginStatus.isLoading ? '正在登录...' : '登 录' }}
          </el-button>
        </el-form-item>
      </el-form>
      <div class="form-footer-links-el">
        <el-text type="info" size="small">还没有账户?</el-text>
        <el-link type="primary" @click="router.push('/register')" style="margin-left: 8px;">立即注册</el-link>
      </div>
    </el-card>
     <footer class="page-footer-credit-el">
      <p>© {{ new Date().getFullYear() }} 请假审批系统. create by dxc 20223535@qq.com.</p>
    </footer>
  </div>
</template>

<style scoped>
.login-page-wrapper-el {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  /* --- 修改背景为白色 --- */
  background-color: #ffffff; /* 或者 var(--el-bg-color-page) 如果 Element Plus 定义了这个变量 */
  /* 如果需要非常浅的灰色背景，可以考虑 #f8f9fa 或 var(--el-fill-color-lighter) */
  padding: 20px;
  overflow: hidden; /* 防止卡片动画溢出 */
}

.login-card-el {
  width: 100%;
  max-width: 400px;
  border-radius: 12px;
  /* 在白色背景下，卡片可能需要一个边框或更明显的阴影来突出 */
  border: 1px solid var(--el-border-color-lighter); /* Element Plus 的浅色边框 */
  box-shadow: var(--el-box-shadow-light); /* Element Plus 的浅阴影 */
  animation: fadeInCard 0.7s ease-out forwards;
  opacity: 0;
}

@keyframes fadeInCard {
  from {
    opacity: 0;
    transform: translateY(20px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.card-header-el {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 10px 0;
  text-align: center;
}
.card-header-el span {
  margin-top: 12px;
  font-size: 1.4rem;
  color: var(--el-text-color-primary); /* 在白色背景下，这个通常是深色，应该没问题 */
  font-weight: 600;
}

.login-form-el {
  margin-top: 20px;
}

.el-input--large .el-input__wrapper {
  padding: 1px 15px;
  border-radius: 8px !important;
}
.el-input__prefix .el-icon {
  font-size: 16px;
}

.el-button--large.is-round {
  padding: 18px 20px;
}

.login-submit-btn-el {
  width: 100%;
  font-weight: 500;
  letter-spacing: 1px;
  transition: all 0.3s ease;
}
.login-submit-btn-el:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(var(--el-color-primary-rgb), 0.3);
}


.form-item-error .el-alert {
  padding: 8px 12px;
  margin-bottom: 10px;
}

.form-footer-links-el {
  margin-top: 25px;
  text-align: center;
}
.form-footer-links-el .el-link {
  font-size: 0.9rem;
  vertical-align: baseline;
}

.page-footer-credit-el {
  position: absolute;
  bottom: 1rem;
  left: 50%;
  transform: translateX(-50%);
  font-size: 0.75rem;
  /* --- 修改页脚文字颜色以适应白色背景 --- */
  color: var(--el-text-color-secondary); /* Element Plus 的次要文字颜色，通常是灰色 */
  /* text-shadow: 0 1px 2px rgba(0,0,0,0.1); */ /* 在白色背景上，文字阴影可能不需要或需要调整 */
}
</style>
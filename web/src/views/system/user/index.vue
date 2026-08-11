<script setup lang="ts">
/**
 * 系统管理 - 用户管理列表页。
 *
 * 功能：查询（username 筛选）、分页、查看详情、新建、编辑、启停、删除、重置密码、已分配角色。
 * 权限控制：按钮级权限由后端返回的 permissions 决定（v-auth 无权限则从 DOM 移除），
 * 列表数据由后端按当前用户权限过滤。
 *
 * 模式参照 ingest/list.vue：sequence guard（requestSeq）防异步覆盖、
 * inFlight + pending 合并保证用户操作不丢失、operatingIds 行级互斥。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import type { FormInstance, FormRules } from "element-plus";
import { ElMessageBox } from "element-plus";
import {
  Plus,
  Edit,
  Delete,
  Refresh,
  View,
  Key
} from "@element-plus/icons-vue";
import {
  listUsers,
  createUser,
  updateUser,
  deleteUser,
  changeUserStatus,
  resetPassword,
  getUserRoles
} from "@/api/system-user";
import { message } from "@/utils/message";
import {
  type SysUser,
  type CreateUserRequest,
  type UpdateUserRequest,
  UserStatus,
  userStatusLabel,
  userStatusTagType
} from "@/api/types";

/* ========================= 列表状态 ========================= */

const loading = ref(false);
const users = ref<SysUser[]>([]);
const total = ref(0);

/** 请求序号：每次发起列表请求时递增，防止旧响应覆盖新响应 */
let requestSeq = 0;

/** 请求互斥锁：确保手动刷新、筛选、分页不会并发 */
let inFlight = false;

/**
 * 待执行的刷新（pending-refresh）机制。
 * 用户触发的刷新绝不丢弃：置位 needsRefresh，等当前请求结束后补发最新参数。
 */
let needsRefresh = false;
const pendingParams = { current: 1, size: 10, username: undefined as string | undefined };

const pagination = reactive({ current: 1, size: 10 });
const filterUsername = ref<string | undefined>(undefined);

/* ========================= 对话框状态 ========================= */

type DialogMode = "create" | "edit";

const formDialogVisible = ref(false);
const dialogMode = ref<DialogMode>("create");
const editingUser = ref<SysUser | null>(null);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const detailDialogVisible = ref(false);
const detailUser = ref<SysUser | null>(null);

const resetDialogVisible = ref(false);
const resetUser = ref<SysUser | null>(null);
const resetFormRef = ref<FormInstance>();
const resetPwdForm = reactive<{ password: string }>({ password: "" });
const resetSubmitting = ref(false);

const rolesDialogVisible = ref(false);
const rolesUser = ref<SysUser | null>(null);
const roles = ref<number[]>([]);
const rolesLoading = ref(false);
/** 角色查询序号：防止 A/B 用户切换时迟到响应覆盖当前用户数据 */
let rolesRequestSeq = 0;
/** 当前角色查询的目标用户 ID，用于校验响应归属 */
let rolesTargetUserId: number | null = null;
/** 组件是否仍挂载，避免卸载后写入状态 */
let rolesMounted = true;
/** 组件是否仍挂载，避免卸载后补发网络请求或写入状态 */
const mounted = ref(false);
onMounted(() => {
  mounted.value = true;
});
onBeforeUnmount(() => {
  rolesMounted = false;
  mounted.value = false;
});

/* ========================= 表单 ========================= */

const form = reactive<CreateUserRequest & UpdateUserRequest>({
  username: "",
  password: "",
  nickname: "",
  email: "",
  phoneNumber: "",
  sex: 0,
  status: UserStatus.ENABLED,
  remark: ""
});

/** 表单校验规则：新建时密码必填，编辑时不渲染密码字段故无校验 */
const rules = computed<FormRules>(() => ({
  username: [
    { required: true, message: "请输入用户名", trigger: "blur" },
    { max: 64, message: "用户名不超过 64 个字符", trigger: "blur" }
  ],
  password:
    dialogMode.value === "create"
      ? [{ required: true, message: "请输入密码", trigger: "blur" }]
      : [],
  nickname: [{ max: 64, message: "昵称不超过 64 个字符", trigger: "blur" }],
  email: [{ type: "email", message: "邮箱格式不正确", trigger: "blur" }],
  remark: [{ max: 512, message: "备注不超过 512 个字符", trigger: "blur" }]
}));

const resetRules: FormRules = {
  password: [
    { required: true, message: "请输入新密码", trigger: "blur" },
    { min: 6, message: "密码不少于 6 个字符", trigger: "blur" }
  ]
};

/* ========================= 操作互斥 ========================= */

/** 操作中的用户 ID 集合（删除/启停/重置密码防重复点击） */
const operatingIds = ref<Set<number>>(new Set());

/* ========================= 列表请求 ========================= */

async function fetchUsers(source: "user") {
  // 用户触发：请求飞行中则记录 pending 与最新参数，等当前请求结束后补发
  if (source === "user" && inFlight) {
    needsRefresh = true;
    pendingParams.current = pagination.current;
    pendingParams.size = pagination.size;
    pendingParams.username = filterUsername.value;
    return;
  }

  const seq = ++requestSeq;
  inFlight = true;
  loading.value = true;
  try {
    const res = await listUsers({
      current: pagination.current,
      size: pagination.size,
      username: filterUsername.value
    });
    // 序号已变化说明已被新的请求覆盖，丢弃旧响应
    if (seq !== requestSeq) return;
    // 飞行期间产生了新的用户意图（pending）：旧响应绝不写入，
    // 直接交由 finally 的 pending 补发，避免旧筛选结果短暂展示
    if (needsRefresh) return;
    users.value = res.records;
    total.value = res.total;
  } catch {
    // 错误提示已由请求层处理
  } finally {
    finishFetch(seq);
  }
}

/**
 * 请求完成的统一调度（fetchUsers 与 fetchUsersInternal 共用）。
 *
 * 只有最终有效请求（seq 仍等于 requestSeq）可以释放 loading 并补发 pending，
 * 从而保证：即便补发请求飞行期间产生了第三次意图（needsRefresh=true），
 * 补发请求完成后仍会再次调用 maybeFirePendingRefresh，把最新意图发出去。
 */
function finishFetch(seq: number) {
  // 过期请求（seq 已变化）不得修改任何状态
  if (seq !== requestSeq) return;
  inFlight = false;
  loading.value = false;
  maybeFirePendingRefresh();
}

/** 补发一次 pending 的用户请求（使用最新保存的参数）。多个 pending 合并为一次。 */
function maybeFirePendingRefresh() {
  if (!needsRefresh || !mounted.value) return;
  needsRefresh = false;
  pagination.current = pendingParams.current;
  pagination.size = pendingParams.size;
  filterUsername.value = pendingParams.username;
  // 以用户身份触发一次请求（此时 inFlight 为 false，不会进入 pending 分支）
  fetchUsersInternal();
}

/** 内部触发：直接发起请求（不经过 pending 合并判断），用于补发。 */
async function fetchUsersInternal() {
  const seq = ++requestSeq;
  inFlight = true;
  loading.value = true;
  try {
    const res = await listUsers({
      current: pagination.current,
      size: pagination.size,
      username: filterUsername.value
    });
    if (seq !== requestSeq) return;
    // 同 fetchUsers：pending 已产生则绝不写入旧数据
    if (needsRefresh) return;
    users.value = res.records;
    total.value = res.total;
  } catch {
    // 错误提示已由请求层处理
  } finally {
    finishFetch(seq);
  }
}

/* ========================= 事件 ========================= */

function handleSearch() {
  pagination.current = 1;
  fetchUsers("user");
}

function handleResetFilter() {
  filterUsername.value = undefined;
  pagination.current = 1;
  fetchUsers("user");
}

function handlePageChange(page: number) {
  pagination.current = page;
  fetchUsers("user");
}

function handleSizeChange(size: number) {
  pagination.size = size;
  pagination.current = 1;
  fetchUsers("user");
}

/* ========================= 新建/编辑 ========================= */

function resetForm() {
  form.username = "";
  form.password = "";
  form.nickname = "";
  form.email = "";
  form.phoneNumber = "";
  form.sex = 0;
  form.status = UserStatus.ENABLED;
  form.remark = "";
}

function openCreateDialog() {
  dialogMode.value = "create";
  editingUser.value = null;
  resetForm();
  formDialogVisible.value = true;
}

function openEditDialog(user: SysUser) {
  dialogMode.value = "edit";
  editingUser.value = user;
  resetForm();
  form.nickname = user.nickname;
  form.email = user.email;
  form.phoneNumber = user.phoneNumber;
  form.sex = user.sex;
  form.remark = user.remark;
  formDialogVisible.value = true;
}

async function handleSubmit() {
  if (!formRef.value || submitting.value) return;
  // 在 await 校验前上锁，避免校验期间双击提交产生重复请求
  submitting.value = true;
  try {
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return; // finally 释放锁

    if (dialogMode.value === "edit" && editingUser.value) {
      const data: UpdateUserRequest = {
        nickname: form.nickname,
        email: form.email,
        phoneNumber: form.phoneNumber,
        sex: form.sex,
        remark: form.remark
      };
      await updateUser(editingUser.value.userId, data);
      message.success("更新成功");
    } else {
      const data: CreateUserRequest = {
        username: form.username,
        password: form.password,
        nickname: form.nickname,
        email: form.email,
        phoneNumber: form.phoneNumber,
        sex: form.sex,
        status: form.status,
        remark: form.remark
      };
      await createUser(data);
      message.success("创建成功");
    }
    formDialogVisible.value = false;
    fetchUsers("user");
  } catch {
    // 错误提示已由请求层处理；失败保留用户输入（不关闭对话框）
  } finally {
    submitting.value = false;
  }
}

/* ========================= 详情 ========================= */

function openDetailDialog(user: SysUser) {
  detailUser.value = user;
  detailDialogVisible.value = true;
}

/* ========================= 启停 ========================= */

async function handleStatusChange(user: SysUser) {
  if (operatingIds.value.has(user.userId)) return;
  const targetStatus = user.status === UserStatus.ENABLED ? UserStatus.DISABLED : UserStatus.ENABLED;
  const actionText = targetStatus === UserStatus.ENABLED ? "启用" : "停用";
  // 在弹出确认框前立即上锁，防止快速双击打开多个确认框导致重复请求
  operatingIds.value.add(user.userId);
  try {
    try {
      await ElMessageBox.confirm(`确定${actionText}该用户吗？`, "确认", { type: "warning" });
    } catch {
      return; // 用户取消确认，由 finally 统一释放锁
    }
    await changeUserStatus(user.userId, targetStatus);
    message.success(`${actionText}成功`);
    fetchUsers("user");
  } catch {
    // 错误提示已由请求层处理
  } finally {
    operatingIds.value.delete(user.userId);
  }
}

/* ========================= 删除 ========================= */

async function handleDelete(user: SysUser) {
  if (operatingIds.value.has(user.userId)) return;
  // 在弹出确认框前立即上锁，防止快速双击打开多个确认框导致重复请求
  operatingIds.value.add(user.userId);
  try {
    try {
      await ElMessageBox.confirm(
        `确定删除用户"${user.username}"吗？删除后不可恢复。`,
        "删除确认",
        { type: "warning" }
      );
    } catch {
      return; // 用户取消确认，由 finally 统一释放锁
    }
    await deleteUser(user.userId);
    message.success("删除成功");
    // 若删除的是当前页最后一条，回退到上一页
    if (users.value.length === 1 && pagination.current > 1) {
      pagination.current -= 1;
    }
    fetchUsers("user");
  } catch {
    // 错误提示已由请求层处理
  } finally {
    operatingIds.value.delete(user.userId);
  }
}

/* ========================= 重置密码 ========================= */

function openResetDialog(user: SysUser) {
  resetUser.value = user;
  resetPwdForm.password = "";
  resetDialogVisible.value = true;
}

async function handleResetPassword() {
  if (!resetFormRef.value || !resetUser.value || resetSubmitting.value) return;
  // 在 await 校验前上锁，避免校验期间双击提交产生重复请求
  resetSubmitting.value = true;
  try {
    const valid = await resetFormRef.value.validate().catch(() => false);
    if (!valid) return; // finally 释放锁

    await resetPassword(resetUser.value.userId, resetPwdForm.password);
    message.success("重置密码成功");
    resetDialogVisible.value = false;
  } catch {
    // 错误提示已由请求层处理
  } finally {
    resetSubmitting.value = false;
  }
}

/* ========================= 已分配角色 ========================= */

async function openRolesDialog(user: SysUser) {
  // 切换用户时立即清空旧角色，避免旧数据在新查询期间残留展示
  roles.value = [];
  rolesUser.value = user;
  rolesDialogVisible.value = true;
  rolesLoading.value = true;
  const seq = ++rolesRequestSeq;
  rolesTargetUserId = user.userId;
  try {
    const res = await getUserRoles(user.userId);
    // 仅当响应属于当前最新查询、目标用户未变、组件仍挂载时才写入
    if (seq !== rolesRequestSeq || user.userId !== rolesTargetUserId || !rolesMounted) return;
    roles.value = res;
  } catch {
    if (seq === rolesRequestSeq && user.userId === rolesTargetUserId && rolesMounted) {
      roles.value = [];
    }
  } finally {
    if (seq === rolesRequestSeq && user.userId === rolesTargetUserId && rolesMounted) {
      rolesLoading.value = false;
    }
  }
}

/* ========================= 生命周期 ========================= */

onMounted(() => {
  fetchUsers("user");
});
</script>

<template>
  <div v-auth="'system:user:list'" class="user-manage">
    <!-- 工具栏 -->
    <div class="toolbar">
      <h2>用户管理</h2>
      <div class="toolbar-actions">
        <el-input
          v-model="filterUsername"
          placeholder="用户名"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleResetFilter">重置</el-button>
        <el-button :icon="Refresh" circle aria-label="刷新用户列表" @click="fetchUsers('user')" />
        <el-button
          v-auth="'system:user:create'"
          type="primary"
          :icon="Plus"
          @click="openCreateDialog"
        >
          新建用户
        </el-button>
      </div>
    </div>

    <!-- 空状态 -->
    <el-empty
      v-if="!loading && users.length === 0"
      description="暂无用户"
    />

    <!-- 用户列表 -->
    <el-table v-loading="loading" :data="users" stripe style="width: 100%">
      <el-table-column prop="userId" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="nickname" label="昵称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag v-if="scope?.row" :type="userStatusTagType(scope.row.status)">
            {{ userStatusLabel(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <el-table-column label="操作" width="360" fixed="right">
        <template #default="scope">
          <template v-if="scope?.row">
            <el-button
              v-auth="'system:user:list'"
              link
              type="primary"
              :icon="View"
              @click="openDetailDialog(scope.row)"
            >
              详情
            </el-button>
            <el-button
              v-auth="'system:user:update'"
              link
              type="primary"
              :icon="Edit"
              @click="openEditDialog(scope.row)"
            >
              编辑
            </el-button>
            <el-button
              v-auth="'system:user:update'"
              link
              :type="scope.row.status === UserStatus.ENABLED ? 'warning' : 'success'"
              :disabled="operatingIds.has(scope.row.userId)"
              @click="handleStatusChange(scope.row)"
            >
              {{ scope.row.status === UserStatus.ENABLED ? "停用" : "启用" }}
            </el-button>
            <el-button
              v-auth="'system:user:reset-password'"
              link
              type="primary"
              :icon="Key"
              :disabled="operatingIds.has(scope.row.userId)"
              @click="openResetDialog(scope.row)"
            >
              重置密码
            </el-button>
            <el-button
              v-auth="'system:user:list'"
              link
              type="primary"
              @click="openRolesDialog(scope.row)"
            >
              已分配角色
            </el-button>
            <el-button
              v-auth="'system:user:delete'"
              link
              type="danger"
              :icon="Delete"
              :disabled="operatingIds.has(scope.row.userId)"
              @click="handleDelete(scope.row)"
            >
              删除
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-if="total > 0"
      class="pagination"
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :current-page="pagination.current"
      :page-size="pagination.size"
      :page-sizes="[10, 20, 50]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />

    <!-- 新建/编辑对话框 -->
    <el-dialog
      v-model="formDialogVisible"
      :title="dialogMode === 'edit' ? '编辑用户' : '新建用户'"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item v-if="dialogMode === 'create'" label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" maxlength="64" />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'create'" label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="请输入昵称" maxlength="64" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="手机号" prop="phoneNumber">
          <el-input v-model="form.phoneNumber" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="性别" prop="sex">
          <el-select v-model="form.sex" style="width: 100%">
            <el-option :value="0" label="未知" />
            <el-option :value="1" label="男" />
            <el-option :value="2" label="女" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dialogMode === 'create'" label="状态" prop="status">
          <el-select v-model="form.status" style="width: 100%">
            <el-option :value="UserStatus.ENABLED" label="启用" />
            <el-option :value="UserStatus.DISABLED" label="停用" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            placeholder="请输入备注"
            maxlength="512"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailDialogVisible" title="用户详情" width="520px" destroy-on-close>
      <el-descriptions v-if="detailUser" :column="1" border>
        <el-descriptions-item label="用户ID">{{ detailUser.userId }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ detailUser.username }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ detailUser.nickname }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ detailUser.email }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ detailUser.phoneNumber }}</el-descriptions-item>
        <el-descriptions-item label="性别">
          {{ detailUser.sex === 1 ? "男" : detailUser.sex === 2 ? "女" : "未知" }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="userStatusTagType(detailUser.status)">
            {{ userStatusLabel(detailUser.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="备注">{{ detailUser.remark }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detailUser.createTime }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ detailUser.updateTime }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码对话框 -->
    <el-dialog
      v-model="resetDialogVisible"
      title="重置密码"
      width="420px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form
        ref="resetFormRef"
        :model="resetPwdForm"
        :rules="resetRules"
        label-width="90px"
      >
        <el-form-item label="新密码" prop="password">
          <el-input
            v-model="resetPwdForm.password"
            type="password"
            placeholder="请输入新密码（不少于 6 个字符）"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetSubmitting" @click="handleResetPassword">确定</el-button>
      </template>
    </el-dialog>

    <!-- 已分配角色对话框 -->
    <el-dialog v-model="rolesDialogVisible" title="已分配角色" width="420px" destroy-on-close>
      <el-skeleton v-if="rolesLoading" :rows="3" animated />
      <el-empty v-else-if="roles.length === 0" description="暂无分配角色" />
      <el-tag
        v-for="roleId in roles"
        v-else
        :key="roleId"
        style="margin: 4px"
        type="primary"
      >
        角色 {{ roleId }}
      </el-tag>
      <template #footer>
        <el-button @click="rolesDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.user-manage {
  background: #fff;
  padding: 16px;
  border-radius: 4px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: 18px;
  }
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>

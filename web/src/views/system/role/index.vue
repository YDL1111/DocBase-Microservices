<script setup lang="ts">
/**
 * 系统管理 - 角色管理列表页。
 *
 * 功能：查询（roleName 筛选）、分页、查看详情、新建、编辑基本信息、启停、删除、
 * 分配菜单权限、系统保留角色标识。
 *
 * 模式参照 views/system/user/index.vue：sequence guard（requestSeq）防异步覆盖、
 * inFlight + pending 合并保证用户操作不丢失、operatingIds 行级互斥。
 *
 * 安全设计：
 *  - 按钮级权限由 v-auth 控制（system:role:list/create/update/delete）；
 *  - 菜单授权使用"全量菜单树"(/api/system/menus/tree)，不使用调用者可见菜单，
 *    避免静默覆盖调用者不可见的既有授权；后端 assertGrantable 负责权限子集校验；
 *  - 菜单授权对话框独立隔离（menuRequestSeq + menuTargetRoleId 快照），
 *    A 的迟到响应不会写入 B；确认期间切换目标，API 只操作确认时的角色；
 *  - 提交失败保留对话框与当前勾选；不拼接/泄露敏感上下文；
 *  - 不允许客户端写入 isSystem、deleted、creatorId 等服务端字段。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import type { FormInstance, FormRules } from "element-plus";
import { Plus, Edit, Delete, Refresh, View, Key } from "@element-plus/icons-vue";
import {
  listRoles,
  getRole,
  createRole,
  updateRole,
  deleteRole,
  changeRoleStatus,
  getRoleMenuIds,
  assignRoleMenus,
  listMenuTree,
  MAX_ROLE_MENUS
} from "@/api/role";
import { message } from "@/utils/message";
import {
  type SysRole,
  type CreateRoleRequest,
  type UpdateRoleRequest,
  type MenuNode,
  RoleStatus,
  roleStatusLabel,
  roleStatusTagType,
  DataScope,
  DATA_SCOPE_OPTIONS,
  dataScopeLabel
} from "@/api/types";

/* ========================= 列表状态 ========================= */

const loading = ref(false);
const roles = ref<SysRole[]>([]);
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
const pendingParams = { current: 1, size: 10, roleName: undefined as string | undefined };

const pagination = reactive({ current: 1, size: 10 });
const filterRoleName = ref<string | undefined>(undefined);

/** 组件是否仍挂载，避免卸载后写入状态或补发请求 */
const mountedRef = ref(false);
onMounted(() => {
  mountedRef.value = true;
  menuMounted.value = true;
});
onBeforeUnmount(() => {
  mountedRef.value = false;
  menuMounted.value = false;
  // 使任何飞行中的请求/菜单请求立即失效：其 seq 将不再等于当前值，
  // 响应到达时被当作过期请求丢弃，不会写入已卸载组件的状态
  requestSeq++;
  menuRequestSeq++;
});

/* ========================= 操作互斥 ========================= */

/** 操作中的角色 ID 集合（删除/启停防重复点击） */
const operatingIds = ref<Set<number>>(new Set());

/* ========================= 新建/编辑对话框 ========================= */

type DialogMode = "create" | "edit";

const formDialogVisible = ref(false);
const dialogMode = ref<DialogMode>("create");
const editingRole = ref<SysRole | null>(null);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const form = reactive<CreateRoleRequest>({
  roleName: "",
  roleKey: "",
  roleSort: 0,
  dataScope: DataScope.ALL,
  status: RoleStatus.ENABLED,
  remark: ""
});

const rules = computed<FormRules>(() => ({
  roleName: [
    { required: true, message: "请输入角色名称", trigger: "blur" },
    { max: 64, message: "角色名称不超过 64 个字符", trigger: "blur" }
  ],
  roleKey: [
    { required: true, message: "请输入角色编码", trigger: "blur" },
    { max: 128, message: "角色编码不超过 128 个字符", trigger: "blur" }
  ],
  remark: [{ max: 512, message: "备注不超过 512 个字符", trigger: "blur" }]
}));

/* ========================= 详情对话框 ========================= */

const detailDialogVisible = ref(false);
const detailRole = ref<SysRole | null>(null);

/* ========================= 菜单授权对话框 ========================= */

const menuDialogVisible = ref(false);
const menuTargetRole = ref<SysRole | null>(null);
const menuTree = ref<MenuNode[]>([]);
const menuLoading = ref(false);
const menuSubmitting = ref(false);
const treeRef = ref<any>();
const menuMounted = ref(false);

/**
 * 菜单对话框是否"就绪可提交"：仅当菜单树与已选 ID 均成功加载、
 * nextTick 渲染完成、且 seq/target 仍有效时才置 true。
 * 加载中与加载失败时保持 false，避免确定按钮清空既有授权。
 */
const menuReady = ref(false);

/** 菜单授权请求序号：防止 A/B 角色切换时迟到响应覆盖当前角色数据 */
let menuRequestSeq = 0;
/** 当前菜单授权的目标角色 ID，用于校验响应归属 */
let menuTargetRoleId: number | null = null;

/* ========================= 列表请求 ========================= */

async function fetchRoles(source: "user") {
  // 用户触发：请求飞行中则记录 pending 与最新参数，等当前请求结束后补发
  if (source === "user" && inFlight) {
    needsRefresh = true;
    pendingParams.current = pagination.current;
    pendingParams.size = pagination.size;
    pendingParams.roleName = filterRoleName.value;
    return;
  }

  const seq = ++requestSeq;
  inFlight = true;
  loading.value = true;
  try {
    const res = await listRoles({
      current: pagination.current,
      size: pagination.size,
      roleName: filterRoleName.value
    });
    // 卸载后绝不写状态：视为过期请求，跳过写入与补发
    if (!mountedRef.value) return;
    if (seq !== requestSeq) return;
    if (needsRefresh) return;
    roles.value = res.records;
    total.value = res.total;
  } catch {
    // 错误提示已由请求层处理
  } finally {
    finishFetch(seq);
  }
}

/**
 * 请求完成的统一调度（fetchRoles 与 fetchRolesInternal 共用）。
 * 只有最终有效请求（seq 仍等于 requestSeq）且组件仍挂载时，才释放 loading 并补发 pending。
 */
function finishFetch(seq: number) {
  if (seq !== requestSeq) return;
  inFlight = false;
  loading.value = false;
  // 卸载后不再补发，避免写入已卸载组件的状态
  if (!mountedRef.value) return;
  maybeFirePendingRefresh();
}

/** 补发一次 pending 的用户请求（使用最新保存的参数）。多个 pending 合并为一次。 */
function maybeFirePendingRefresh() {
  if (!needsRefresh || !mountedRef.value) return;
  needsRefresh = false;
  pagination.current = pendingParams.current;
  pagination.size = pendingParams.size;
  filterRoleName.value = pendingParams.roleName;
  fetchRolesInternal();
}

/** 内部触发：直接发起请求（不经过 pending 合并判断），用于补发。 */
async function fetchRolesInternal() {
  const seq = ++requestSeq;
  inFlight = true;
  loading.value = true;
  try {
    const res = await listRoles({
      current: pagination.current,
      size: pagination.size,
      roleName: filterRoleName.value
    });
    // 卸载后绝不写状态
    if (!mountedRef.value) return;
    if (seq !== requestSeq) return;
    if (needsRefresh) return;
    roles.value = res.records;
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
  fetchRoles("user");
}

function handleResetFilter() {
  filterRoleName.value = undefined;
  pagination.current = 1;
  fetchRoles("user");
}

function handlePageChange(page: number) {
  pagination.current = page;
  fetchRoles("user");
}

function handleSizeChange(size: number) {
  pagination.size = size;
  pagination.current = 1;
  fetchRoles("user");
}

/* ========================= 新建/编辑 ========================= */

function resetForm() {
  form.roleName = "";
  form.roleKey = "";
  form.roleSort = 0;
  form.dataScope = DataScope.ALL;
  form.status = RoleStatus.ENABLED;
  form.remark = "";
}

function openCreateDialog() {
  dialogMode.value = "create";
  editingRole.value = null;
  resetForm();
  formDialogVisible.value = true;
}

function openEditDialog(role: SysRole) {
  dialogMode.value = "edit";
  editingRole.value = role;
  resetForm();
  form.roleName = role.roleName;
  form.roleKey = role.roleKey;
  form.roleSort = role.roleSort;
  form.dataScope = role.dataScope;
  form.remark = role.remark;
  formDialogVisible.value = true;
}

async function handleSubmit() {
  if (!formRef.value || submitting.value) return;
  // 在 await 校验前上锁，避免校验期间双击提交产生重复请求
  submitting.value = true;
  try {
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return; // finally 释放锁

    if (dialogMode.value === "edit" && editingRole.value) {
      // 编辑基本信息：menuIds 置为 undefined → 不修改菜单，避免意外清空
      const data: UpdateRoleRequest = {
        roleName: form.roleName,
        roleKey: form.roleKey,
        roleSort: form.roleSort,
        dataScope: form.dataScope,
        remark: form.remark,
        menuIds: undefined
      };
      await updateRole(editingRole.value.roleId, data);
      message.success("更新成功");
    } else {
      const data: CreateRoleRequest = {
        roleName: form.roleName,
        roleKey: form.roleKey,
        roleSort: form.roleSort,
        dataScope: form.dataScope,
        status: form.status,
        remark: form.remark
      };
      await createRole(data);
      message.success("创建成功");
    }
    formDialogVisible.value = false;
    fetchRoles("user");
  } catch {
    // 错误提示已由请求层处理；失败保留用户输入（不关闭对话框）
  } finally {
    submitting.value = false;
  }
}

/* ========================= 详情 ========================= */

async function openDetailDialog(role: SysRole) {
  detailRole.value = role;
  detailDialogVisible.value = true;
  // 异步加载最新详情，失败不影响展示（列表数据兜底）
  try {
    const fresh = await getRole(role.roleId);
    // 卸载后不写状态；仅当目标未变时才写入
    if (!mountedRef.value) return;
    if (detailRole.value?.roleId === role.roleId) {
      detailRole.value = fresh;
    }
  } catch {
    // 错误提示已由请求层处理
  }
}

/* ========================= 启停 ========================= */

async function handleStatusChange(role: SysRole) {
  if (operatingIds.value.has(role.roleId)) return;
  const targetStatus = role.status === RoleStatus.ENABLED ? RoleStatus.DISABLED : RoleStatus.ENABLED;
  const actionText = targetStatus === RoleStatus.ENABLED ? "启用" : "停用";
  // 在弹出确认框前立即上锁，防止快速双击打开多个确认框导致重复请求
  operatingIds.value.add(role.roleId);
  try {
    try {
      await message.confirm(`确定${actionText}该角色吗？`, "确认");
    } catch {
      return; // 用户取消确认，由 finally 统一释放锁
    }
    await changeRoleStatus(role.roleId, targetStatus);
    message.success(`${actionText}成功`);
    fetchRoles("user");
  } catch {
    // 错误提示已由请求层处理
  } finally {
    operatingIds.value.delete(role.roleId);
  }
}

/* ========================= 删除 ========================= */

async function handleDelete(role: SysRole) {
  if (operatingIds.value.has(role.roleId)) return;
  // 在弹出确认框前立即上锁，防止快速双击打开多个确认框导致重复请求
  operatingIds.value.add(role.roleId);
  try {
    try {
      await message.confirm(
        `确定删除角色"${role.roleName}"吗？删除后不可恢复。`,
        "删除确认"
      );
    } catch {
      return; // 用户取消确认，由 finally 统一释放锁
    }
    await deleteRole(role.roleId);
    message.success("删除成功");
    // 若删除的是当前页最后一条，回退到上一页
    if (roles.value.length === 1 && pagination.current > 1) {
      pagination.current -= 1;
    }
    fetchRoles("user");
  } catch {
    // 错误提示已由请求层处理（含系统保留角色等业务拒绝）
  } finally {
    operatingIds.value.delete(role.roleId);
  }
}

/* ========================= 菜单授权 ========================= */

/**
 * 打开菜单授权对话框。
 * 切换角色时立即清空旧勾选与旧树；使用独立的 menuRequestSeq + menuTargetRoleId
 * 隔离不同角色的加载与响应，避免 A 的迟到响应写入 B。
 */
async function openMenuDialog(role: SysRole) {
  // 立即清空旧数据，避免旧勾选在新查询期间残留展示
  menuTree.value = [];
  menuTargetRole.value = role;
  menuDialogVisible.value = true;
  menuLoading.value = true;
  // 打开后重置就绪态：只有本次加载全部成功 + 渲染完成才允许提交
  menuReady.value = false;
  const seq = ++menuRequestSeq;
  menuTargetRoleId = role.roleId;
  try {
    // 并行加载全量菜单树与角色已选 menuIds，两者共用同一 seq/target 守卫
    const [tree, ids] = await Promise.all([
      listMenuTree(),
      getRoleMenuIds(role.roleId)
    ]);
    // 仅当响应属于当前最新查询、目标角色未变、组件仍挂载时才写入
    if (seq !== menuRequestSeq || role.roleId !== menuTargetRoleId || !menuMounted.value) return;
    menuTree.value = tree;
    // 先结束 loading，让 el-tree（v-else 分支）挂载；树实例就绪后再设置勾选。
    menuLoading.value = false;
    // 轮询等待树实例就绪：单次 nextTick 时树可能尚未完成挂载。
    for (let i = 0; i < 10 && !treeRef.value; i++) {
      await nextTick();
    }
    // 渲染后再次确认仍属于当前查询（渲染期间可能已切换目标/卸载）
    if (seq !== menuRequestSeq || role.roleId !== menuTargetRoleId || !menuMounted.value) return;
    // 树实例仍未能挂载（异常）：绝不开放提交，避免确定按钮清空既有授权。
    // 只有成功调用 setCheckedKeys 后才置 menuReady=true。
    if (!treeRef.value) return;
    treeRef.value.setCheckedKeys(ids ?? []);
    // 树与勾选均已就绪，允许提交
    menuReady.value = true;
  } catch {
    // 加载失败（如缺少 system:menu:list）时：请求层已提示；保留空树与对话框，
    // 不静默清空既有授权；menuReady 保持 false，确定按钮不可点。
    if (seq === menuRequestSeq && role.roleId === menuTargetRoleId && menuMounted.value) {
      menuTree.value = [];
      menuLoading.value = false;
    }
  }
}

/**
 * 根据菜单树为每个选中的 menuId 显式补齐其全部祖先节点。
 *
 * <p>在 check-strictly 严格模式下，勾选叶子不会让父节点变为半选（indeterminate），
 * 因此不能依赖 getHalfCheckedKeys() 来获取访问路径上的祖先。这里根据 parentId 链
 * 向上追溯，确保提交集合包含每个被勾选节点的完整祖先路径，使前端菜单可正常展开访问。
 * 结果不包含兄弟节点（只沿祖先链上溯），并去重。
 */
function collectWithAncestors(checkedIds: number[], tree: MenuNode[]): number[] {
  const parentOf = new Map<number, number>();
  const indexParents = (nodes: MenuNode[]): void => {
    for (const node of nodes) {
      // parentId 为 0/null 表示根节点，无需记录；捕获到局部以让 TS 收窄类型
      const pid = node.parentId;
      if (pid != null && pid > 0) parentOf.set(node.menuId, pid);
      if (node.children?.length) indexParents(node.children);
    }
  };
  indexParents(tree);

  const result = new Set<number>();
  for (const id of checkedIds) {
    let cur: number | undefined = id;
    while (cur != null && !result.has(cur)) {
      result.add(cur);
      cur = parentOf.get(cur);
    }
  }
  return [...result];
}

/**
 * 提交菜单授权。
 *
 * <p>父子节点策略：提交"显式勾选的节点 + 其全部祖先节点"。父菜单/目录节点本身也是
 * 角色菜单集合的一部分（后端按 menuId 校验有效性并做权限子集校验），补齐祖先可保证
 * 角色保留父级菜单的可见性与可访问性。祖先由 menuTree 的 parentId 链显式计算
 * （不依赖 getHalfCheckedKeys，因为 check-strictly 模式下不会产生半选父节点）。
 *
 * <p>使用确认时的 targetRoleId 快照：确认期间切换目标，API 仍只操作确认时的角色。
 * 提交前在前端做去重、正整数校验与 500 项上限检查，非法不请求后端。
 */
async function handleAssignMenus() {
  // 未就绪（加载中 / 加载失败）时拒绝提交，防止清空既有授权
  if (menuSubmitting.value || !menuTargetRole.value || !menuReady.value) return;
  // 快照确认时的目标角色：后续切换目标不影响本次提交对象
  const target = menuTargetRole.value;
  const checked: number[] = treeRef.value?.getCheckedKeys?.() ?? [];
  // 严格模式下根据 menuTree 显式补齐祖先，确保访问路径完整
  const all = collectWithAncestors(checked, menuTree.value);

  // 去重 + 正整数校验
  const seen = new Set<number>();
  const deduped: number[] = [];
  for (const id of all) {
    if (!Number.isInteger(id) || id < 1) {
      message.error("存在非法菜单 ID，请检查勾选");
      return;
    }
    if (!seen.has(id)) {
      seen.add(id);
      deduped.push(id);
    }
  }
  if (deduped.length > MAX_ROLE_MENUS) {
    message.error(`菜单数量超过 ${MAX_ROLE_MENUS} 项上限`);
    return;
  }

  menuSubmitting.value = true;
  try {
    await assignRoleMenus(target.roleId, { menuIds: deduped });
    message.success("分配菜单成功");
    menuDialogVisible.value = false;
    fetchRoles("user");
  } catch {
    // 错误提示已由请求层处理（含权限子集/MENU_INVALID 等业务拒绝）；
    // 失败保留对话框与当前勾选，不丢失用户选择
  } finally {
    menuSubmitting.value = false;
  }
}

/* ========================= 生命周期 ========================= */

onMounted(() => {
  fetchRoles("user");
});
</script>

<template>
  <div v-auth="'system:role:list'" class="role-manage">
    <!-- 工具栏 -->
    <div class="toolbar">
      <h2>角色管理</h2>
      <div class="toolbar-actions">
        <el-input
          v-model="filterRoleName"
          placeholder="角色名称"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleResetFilter">重置</el-button>
        <el-button :icon="Refresh" circle aria-label="刷新角色列表" @click="fetchRoles('user')" />
        <el-button
          v-auth="'system:role:create'"
          type="primary"
          :icon="Plus"
          @click="openCreateDialog"
        >
          新建角色
        </el-button>
      </div>
    </div>

    <!-- 空状态 -->
    <el-empty v-if="!loading && roles.length === 0" description="暂无角色" />

    <!-- 角色列表 -->
    <el-table v-loading="loading" :data="roles" stripe style="width: 100%">
      <el-table-column prop="roleName" label="角色名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="roleKey" label="角色编码" min-width="160" show-overflow-tooltip />
      <el-table-column prop="roleSort" label="排序" width="80" />
      <el-table-column label="数据范围" width="140">
        <template #default="scope">
          <span v-if="scope?.row">{{ dataScopeLabel(scope.row.dataScope) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag v-if="scope?.row" :type="roleStatusTagType(scope.row.status)">
            {{ roleStatusLabel(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="系统保留" width="110">
        <template #default="scope">
          <el-tag v-if="scope?.row && scope.row.isSystem === 1" type="warning">系统保留</el-tag>
          <span v-else-if="scope?.row">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <el-table-column label="操作" width="420" fixed="right">
        <template #default="scope">
          <template v-if="scope?.row">
            <el-button
              v-auth="'system:role:list'"
              link
              type="primary"
              :icon="View"
              @click="openDetailDialog(scope.row)"
            >
              详情
            </el-button>
            <el-button
              v-auth="'system:role:update'"
              link
              type="primary"
              :icon="Edit"
              @click="openEditDialog(scope.row)"
            >
              编辑
            </el-button>
            <el-button
              v-auth="['system:role:update', 'system:role:list', 'system:menu:list']"
              link
              type="primary"
              :icon="Key"
              @click="openMenuDialog(scope.row)"
            >
              分配菜单
            </el-button>
            <el-button
              v-auth="'system:role:update'"
              link
              :type="scope.row.status === RoleStatus.ENABLED ? 'warning' : 'success'"
              :disabled="operatingIds.has(scope.row.roleId)"
              @click="handleStatusChange(scope.row)"
            >
              {{ scope.row.status === RoleStatus.ENABLED ? "停用" : "启用" }}
            </el-button>
            <el-button
              v-auth="'system:role:delete'"
              link
              type="danger"
              :icon="Delete"
              :disabled="operatingIds.has(scope.row.roleId)"
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

    <!-- 新建/编辑对话框（基本信息，不含菜单分配，避免误清空权限） -->
    <el-dialog
      v-model="formDialogVisible"
      :title="dialogMode === 'edit' ? '编辑角色' : '新建角色'"
      width="560px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="请输入角色名称" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色编码" prop="roleKey">
          <el-input v-model="form.roleKey" placeholder="请输入角色编码" maxlength="128" />
        </el-form-item>
        <el-form-item label="排序" prop="roleSort">
          <el-input-number
            v-model="form.roleSort"
            :min="0"
            :max="9999"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="数据范围" prop="dataScope">
          <el-select v-model="form.dataScope" style="width: 100%">
            <el-option
              v-for="opt in DATA_SCOPE_OPTIONS"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dialogMode === 'create'" label="状态" prop="status">
          <el-select v-model="form.status" style="width: 100%">
            <el-option :value="RoleStatus.ENABLED" label="启用" />
            <el-option :value="RoleStatus.DISABLED" label="停用" />
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
    <el-dialog v-model="detailDialogVisible" title="角色详情" width="560px" destroy-on-close>
      <el-descriptions v-if="detailRole" :column="1" border>
        <el-descriptions-item label="角色ID">{{ detailRole.roleId }}</el-descriptions-item>
        <el-descriptions-item label="角色名称">{{ detailRole.roleName }}</el-descriptions-item>
        <el-descriptions-item label="角色编码">{{ detailRole.roleKey }}</el-descriptions-item>
        <el-descriptions-item label="排序">{{ detailRole.roleSort }}</el-descriptions-item>
        <el-descriptions-item label="数据范围">{{ dataScopeLabel(detailRole.dataScope) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="roleStatusTagType(detailRole.status)">
            {{ roleStatusLabel(detailRole.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="系统保留">
          <el-tag v-if="detailRole.isSystem === 1" type="warning">是</el-tag>
          <span v-else>否</span>
        </el-descriptions-item>
        <el-descriptions-item label="备注">{{ detailRole.remark }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detailRole.createTime }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ detailRole.updateTime }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 菜单授权对话框 -->
    <el-dialog
      v-model="menuDialogVisible"
      :title="menuTargetRole ? `分配菜单 - ${menuTargetRole.roleName}` : '分配菜单'"
      width="640px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div v-if="menuLoading" class="menu-loading">
        <el-skeleton :rows="5" animated />
      </div>
      <div v-else>
        <el-tree
          ref="treeRef"
          :data="menuTree"
          :props="{ label: 'menuName', children: 'children' }"
          node-key="menuId"
          show-checkbox
          default-expand-all
          check-strictly
        />
      </div>
      <template #footer>
        <el-button @click="menuDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="menuSubmitting"
          :disabled="menuLoading || !menuReady"
          @click="handleAssignMenus"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.role-manage {
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

.menu-loading {
  padding: 16px;
}
</style>

<script setup lang="ts">
/**
 * 系统管理 - 菜单管理页。
 *
 * 功能：树形展示菜单、查看详情、新建、编辑、启停、删除、管理归属。
 *
 * 数据契约：与 iam-service MenuController 对齐
 *  - GET  /api/system/menus/tree        全量菜单树（含 status/isSystem）
 *  - GET  /api/system/menus/{menuId}    详情
 *  - POST /api/system/menus             新建（create 含 status）
 *  - PUT  /api/system/menus/{menuId}    编辑（update 不带 status！）
 *  - PUT  /api/system/menus/{menuId}/status  启停（专用状态接口）
 *  - DELETE /api/system/menus/{menuId}  删除
 *
 * 安全设计：
 *  - 按钮级权限由 v-auth 控制（system:menu:list/create/update/delete）；
 *  - 系统保留标记只认后端 isSystem 字段，不凭 menuName/routerName 推断；
 *  - 节点类型不变量在前端与后端 MenuService.validateMenuInput 对齐
 *    （按钮 permission 必填且 routerName/path 为空、isButton=1；
 *     目录/菜单 routerName/path 必填且匹配正则、isButton=0）；
 *  - 提交体绝不携带 isSystem/deleted/审计字段；update 不带 status；
 *  - Owner 仅超级管理员可管理，且只调用 /owners；绝不复用角色菜单授权接口；
 *  - Owner 为空表示“系统托管”，不授予任何角色菜单 permission。
 *
 * 异步隔离（与 role/index.vue 同模式）：
 *  - requestSeq 防迟到树响应覆盖新响应；
 *  - inFlight + needsRefresh 合并飞行中的用户刷新，绝不丢失最新意图；
 *  - operatingIds 行级互斥：确认框弹出前立即上锁，防双击重复操作；
 *  - submitting 防止新建/编辑双击提交；
 *  - 编辑提交使用打开时的 menuId 快照，切换目标不影响已确认的提交；
 *  - 卸载后不写状态、不补发请求。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import type { FormInstance, FormRules } from "element-plus";
import { Plus, Edit, Delete, Refresh, View } from "@element-plus/icons-vue";
import {
  listMenuTree,
  getMenu,
  getMenuOwners,
  replaceMenuOwners,
  createMenu,
  updateMenu,
  changeMenuStatus,
  deleteMenu,
  MENU_NAME_MAX,
  ROUTER_NAME_MAX,
  PATH_MAX,
  PERMISSION_MAX,
  META_INFO_MAX,
  REMARK_MAX,
  SORT_NUM_MAX,
  ROUTER_NAME_PATTERN,
  PATH_PATTERN,
  PERMISSION_PATTERN
} from "@/api/system-menu";
import { listAllRoles } from "@/api/role";
import { useUserStoreHook } from "@/store/modules/user";
import { message } from "@/utils/message";
import {
  type SysMenu,
  type MenuNode,
  type CreateMenuRequest,
  type UpdateMenuRequest,
  type SysRole,
  MenuType,
  MenuStatus,
  RoleStatus,
  menuTypeLabel,
  menuTypeTagType,
  menuStatusLabel,
  menuStatusTagType
} from "@/api/types";

defineOptions({ name: "SystemMenu" });

const userStore = useUserStoreHook();
/** 入口必须从普通管理员的 DOM 中移除；admin:all 仅作兼容性兜底。 */
const canManageOwners = computed(
  () => userStore.admin === true || userStore.hasPermission("admin:all")
);

/* ========================= 列表状态 ========================= */

const loading = ref(false);
const menuTree = ref<MenuNode[]>([]);

/** 树请求序号：防止旧响应覆盖新响应 */
let requestSeq = 0;

/** 请求互斥锁：确保手动刷新与初始加载不会并发 */
let inFlight = false;

/** 待执行的刷新（pending-refresh）：用户触发的刷新绝不丢失 */
let needsRefresh = false;

/** 组件是否仍挂载，避免卸载后写状态或补发请求 */
const mountedRef = ref(false);

/* ========================= 操作互斥 ========================= */

/** 操作中的菜单 ID 集合（启停/删除防重复点击） */
const operatingIds = ref<Set<number>>(new Set());

/* ========================= 新建/编辑对话框 ========================= */

type DialogMode = "create" | "edit";

const formDialogVisible = ref(false);
const dialogMode = ref<DialogMode>("create");
/** 编辑目标菜单 ID 快照：提交时只操作打开对话框那一刻的目标 */
const editTargetMenuId = ref<number | null>(null);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const form = reactive({
  parentId: 0,
  menuName: "",
  menuType: MenuType.MENU as number,
  routerName: "",
  path: "",
  permission: "",
  metaInfo: "",
  sortNum: 0,
  status: MenuStatus.ENABLED as number,
  remark: ""
});

/**
 * 表单规则：根据 menuType 动态变化。
 * 目录/菜单(1/2)：routerName/path 必填且匹配后端正则；
 * 按钮(3)：permission 必填；routerName/path 由节点类型规则排除。
 */
const rules = computed<FormRules>(() => {
  const base: FormRules = {
    menuName: [
      { required: true, message: "请输入菜单名称", trigger: "blur" },
      { max: MENU_NAME_MAX, message: `菜单名称不超过 ${MENU_NAME_MAX} 个字符`, trigger: "blur" }
    ],
    sortNum: [
      {
        validator: (_r, v: number, cb) => {
          if (v == null) return cb();
          if (!Number.isInteger(v) || v < 0 || v > SORT_NUM_MAX) {
            return cb(new Error(`排序为 0-${SORT_NUM_MAX} 的整数`));
          }
          return cb();
        },
        trigger: "change"
      }
    ],
    remark: [{ max: REMARK_MAX, message: `备注不超过 ${REMARK_MAX} 个字符`, trigger: "blur" }],
    metaInfo: [
      {
        validator: (_r, v: string, cb) => {
          if (v == null || v.trim() === "") return cb();
          const trimmed = v.trim();
          if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return cb(new Error("metaInfo 必须为合法的 JSON 对象"));
          }
          try {
            const parsed = JSON.parse(trimmed);
            if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
              return cb(new Error("metaInfo 必须为合法的 JSON 对象"));
            }
          } catch {
            return cb(new Error("metaInfo 必须为合法的 JSON 对象"));
          }
          return cb();
        },
        trigger: "blur"
      }
    ]
  };

  if (form.menuType === MenuType.BUTTON) {
    base.permission = [
      { required: true, message: "按钮必须填写权限标识", trigger: "blur" },
      { pattern: PERMISSION_PATTERN, message: "权限标识只能含小写字母、数字、冒号、点、下划线、连字符", trigger: "blur" },
      { max: PERMISSION_MAX, message: `权限标识不超过 ${PERMISSION_MAX} 个字符`, trigger: "blur" }
    ];
  } else {
    base.routerName = [
      { required: true, message: "请输入路由名称", trigger: "blur" },
      { pattern: ROUTER_NAME_PATTERN, message: "路由名称须以字母开头，仅含字母、数字、下划线、连字符", trigger: "blur" },
      { max: ROUTER_NAME_MAX, message: `路由名称不超过 ${ROUTER_NAME_MAX} 个字符`, trigger: "blur" }
    ];
    base.path = [
      { required: true, message: "请输入路由路径", trigger: "blur" },
      { pattern: PATH_PATTERN, message: "路径必须以 / 开头，段仅含字母、数字、下划线、连字符", trigger: "blur" },
      { max: PATH_MAX, message: `路径不超过 ${PATH_MAX} 个字符`, trigger: "blur" }
    ];
    base.permission = [
      // 目录/菜单的权限标识可选：空值允许；非空必须匹配格式（与后端 MENU_PERMISSION_INVALID 一致）
      { pattern: PERMISSION_PATTERN, message: "权限标识只能含小写字母、数字、冒号、点、下划线、连字符", trigger: "blur" },
      { max: PERMISSION_MAX, message: `权限标识不超过 ${PERMISSION_MAX} 个字符`, trigger: "blur" }
    ];
  }
  return base;
});

/** 当前编辑中的菜单（用于菜单类型切换时清理字段） */
let editingNodeRef: MenuNode | null = null;

/** 菜单类型切换：isButton 由 menuType 推导（1/2→0，3→1），按钮时清空 routerName/path */
function handleMenuTypeChange() {
  if (form.menuType === MenuType.BUTTON) {
    form.routerName = "";
    form.path = "";
  }
}

/* ========================= 详情对话框 ========================= */

const detailDialogVisible = ref(false);
const detailMenu = ref<SysMenu | null>(null);
const detailLoading = ref(false);
/** 详情请求序号：防止切换目标后旧详情响应覆盖新目标 */
let detailRequestSeq = 0;

/* ========================= 管理归属 ========================= */

const ownerDialogVisible = ref(false);
const ownerTargetMenu = ref<MenuNode | null>(null);
const ownerRoles = ref<SysRole[]>([]);
const ownerRoleIds = ref<number[]>([]);
const ownerLoading = ref(false);
const ownerReady = ref(false);
const ownerSubmitting = ref(false);

/** 每次打开/关闭都递增，隔离 Owner 和角色列表的迟到响应。 */
let ownerRequestSeq = 0;
let ownerDialogSession = 0;

function clearOwnerDialogState() {
  ownerRoleIds.value = [];
  ownerRoles.value = [];
  ownerReady.value = false;
  ownerLoading.value = false;
}

function isCurrentOwnerDialog(session: number, menuId: number) {
  return (
    mountedRef.value &&
    session === ownerDialogSession &&
    ownerDialogVisible.value &&
    ownerTargetMenu.value?.menuId === menuId
  );
}

function closeOwnerDialog() {
  if (ownerSubmitting.value) return;
  ownerDialogVisible.value = false;
  ownerTargetMenu.value = null;
  ownerDialogSession++;
  ownerRequestSeq++;
  clearOwnerDialogState();
}

/**
 * 两个数据源只有都成功时才让对话框进入可提交态。
 * 切换目标先同步清空旧选择/角色，随后用 requestSeq + session + target 三重守卫写入。
 */
async function openOwnerDialog(node: MenuNode) {
  if (ownerSubmitting.value) return;
  ownerDialogSession++;
  const session = ownerDialogSession;
  const targetMenuId = node.menuId;
  const seq = ++ownerRequestSeq;
  ownerTargetMenu.value = node;
  ownerDialogVisible.value = true;
  clearOwnerDialogState();
  ownerLoading.value = true;

  try {
    const [ownerIds, roles] = await Promise.all([
      getMenuOwners(targetMenuId),
      listAllRoles()
    ]);
    if (!mountedRef.value) return;
    if (seq !== ownerRequestSeq || session !== ownerDialogSession) return;
    if (ownerTargetMenu.value?.menuId !== targetMenuId) return;
    ownerRoles.value = roles;
    ownerRoleIds.value = ownerIds;
    ownerReady.value = true;
  } catch {
    // 任一加载失败都不能把它解释为空 Owner，确定按钮保持禁用。
    if (mountedRef.value && seq === ownerRequestSeq && session === ownerDialogSession) {
      ownerReady.value = false;
    }
  } finally {
    if (mountedRef.value && seq === ownerRequestSeq && session === ownerDialogSession) {
      ownerLoading.value = false;
    }
  }
}

async function handleReplaceOwners() {
  if (ownerSubmitting.value || !ownerReady.value || !ownerTargetMenu.value) return;
  const session = ownerDialogSession;
  const targetMenuId = ownerTargetMenu.value.menuId;
  const roleIds = [...ownerRoleIds.value];
  // 在二次确认前就上锁，防止连续点击产生多个确认框或多次 PUT。
  ownerSubmitting.value = true;
  try {
    if (roleIds.length === 0) {
      try {
        await message.confirm(
          "这将把菜单设为系统托管，普通角色将无法管理，是否继续？",
          "确认系统托管"
        );
      } catch {
        return;
      }
    }
    // 全局确认框等待期间可能已路由离开；此时绝不再改变菜单 Owner。
    if (!isCurrentOwnerDialog(session, targetMenuId)) return;
    await replaceMenuOwners(targetMenuId, roleIds);
    if (isCurrentOwnerDialog(session, targetMenuId)) {
      message.success("管理归属已更新");
      // 关闭会话会递增 session，必须先释放当前提交锁。
      ownerSubmitting.value = false;
      closeOwnerDialogAfterSubmit();
    }
  } catch (err) {
    // 后端失败时保留已选角色及对话框；请求层负责常规业务错误提示。
    if (
      isCurrentOwnerDialog(session, targetMenuId) &&
      (err instanceof RangeError || (err as Error)?.name === "RangeError")
    ) {
      message.error((err as Error).message || "参数校验失败");
    }
  } finally {
    if (isCurrentOwnerDialog(session, targetMenuId)) {
      ownerSubmitting.value = false;
    }
  }
}

/** 成功路径允许关闭，但不让 closeOwnerDialog 的提交中守卫阻断。 */
function closeOwnerDialogAfterSubmit() {
  ownerDialogVisible.value = false;
  ownerTargetMenu.value = null;
  ownerDialogSession++;
  ownerRequestSeq++;
  clearOwnerDialogState();
}

function ownerRoleDisabled(role: SysRole): boolean {
  return role.status !== RoleStatus.ENABLED;
}

/* ========================= 树请求 ========================= */

async function fetchTree(source: "user") {
  // 用户触发：请求飞行中则记录 pending，等当前请求结束后补发最新意图
  if (source === "user" && inFlight) {
    needsRefresh = true;
    return;
  }
  await fetchTreeInternal();
}

async function fetchTreeInternal() {
  const seq = ++requestSeq;
  inFlight = true;
  loading.value = true;
  try {
    const tree = await listMenuTree();
    // 卸载后绝不写状态；过期响应不覆盖；pending 用户请求将由补发覆盖
    if (!mountedRef.value) return;
    if (seq !== requestSeq) return;
    if (needsRefresh) return;
    menuTree.value = tree;
  } catch {
    // 错误提示已由请求层处理
  } finally {
    finishFetch(seq);
  }
}

/** 请求完成的统一调度：仅最终有效请求释放锁并补发 pending */
function finishFetch(seq: number) {
  if (seq !== requestSeq) return;
  inFlight = false;
  loading.value = false;
  if (!mountedRef.value) return;
  maybeFirePendingRefresh();
}

/** 补发一次 pending 的用户刷新（多次合并为一次） */
function maybeFirePendingRefresh() {
  if (!needsRefresh || !mountedRef.value) return;
  needsRefresh = false;
  void fetchTreeInternal();
}

/* ========================= 父节点选项 ========================= */

/**
 * 合法父节点 = 非按钮节点（目录/菜单）。
 * 停用节点（status=0）禁用；编辑时排除自身及其后代（防循环引用）。
 * 安全属性只认后端 isButton / status。
 */
const parentOptions = computed<MenuNode[]>(() => {
  const excludeId = dialogMode.value === "edit" ? editTargetMenuId.value : null;
  return buildParentOptions(menuTree.value, excludeId);
});

function buildParentOptions(nodes: MenuNode[], excludeId: number | null): MenuNode[] {
  const result: MenuNode[] = [];
  for (const node of nodes) {
    if (node.isButton === 1) continue; // 按钮不能作为父节点
    if (excludeId != null && node.menuId === excludeId) continue; // 排除自身及其后代
    const kids = node.children?.length ? buildParentOptions(node.children, excludeId) : [];
    result.push({
      ...node,
      children: kids.length ? kids : undefined
    });
  }
  return result;
}

/** el-tree-select 节点禁用逻辑：停用节点不可作为父节点 */
function parentNodeDisabled(data: MenuNode): boolean {
  return data.status === MenuStatus.DISABLED;
}

/* ========================= 新建/编辑 ========================= */

/**
 * 表单是否就绪可提交。
 * 新建：打开即就绪；编辑：必须等 GET /{menuId} 完整详情（含 remark）加载成功后才开放提交，
 * 避免用 MenuNode（不含 remark）直接填充导致保存时清空原备注（P0 修复）。
 */
const formReady = ref(false);

/** 对话框会话号：每次打开对话框递增，用于隔离异步加载/提交的归属 */
let dialogSession = 0;

/** 编辑详情请求序号：防止切换目标后旧详情响应覆盖新目标 */
let editRequestSeq = 0;

function resetForm() {
  form.parentId = 0;
  form.menuName = "";
  form.menuType = MenuType.MENU;
  form.routerName = "";
  form.path = "";
  form.permission = "";
  form.metaInfo = "";
  form.sortNum = 0;
  form.status = MenuStatus.ENABLED;
  form.remark = "";
  editingNodeRef = null;
}

function openCreateDialog() {
  // 提交期间禁止切换/打开其它对话框，避免 A 提交未完成时 B 干扰（P1 修复）
  if (submitting.value) return;
  dialogMode.value = "create";
  editTargetMenuId.value = null;
  resetForm();
  dialogSession++;
  formReady.value = true; // 新建无需加载详情
  formDialogVisible.value = true;
}

/**
 * 打开编辑对话框：先异步加载完整详情，成功后才开放提交。
 *
 * 守卫：seq（editRequestSeq）+ 目标 menuId + 会话号 + mountedRef，
 * 卸载后不写状态；详情失败时保持对话框打开但 formReady=false（确定按钮禁用）。
 */
async function openEditDialog(node: MenuNode) {
  // 提交期间禁止切换目标（P1 修复）
  if (submitting.value) return;
  dialogMode.value = "edit";
  editTargetMenuId.value = node.menuId;
  editingNodeRef = node;
  resetForm();
  dialogSession++;
  const session = dialogSession;
  formReady.value = false; // 详情未就绪前禁止提交
  formDialogVisible.value = true;

  const seq = ++editRequestSeq;
  const targetMenuId = node.menuId;
  try {
    const detail = await getMenu(targetMenuId);
    // 卸载 / 请求过期 / 目标已切换 / 会话已变化 → 一律不写入
    if (!mountedRef.value) return;
    if (seq !== editRequestSeq) return;
    if (editTargetMenuId.value !== targetMenuId) return;
    if (dialogSession !== session) return;
    fillEditForm(detail);
    formReady.value = true;
  } catch {
    // 加载失败：错误由请求层提示；保持对话框打开但不可提交（不丢失上下文）
    if (seq === editRequestSeq && dialogSession === session && mountedRef.value) {
      formReady.value = false;
    }
  }
}

/** 用完整详情填充编辑表单（MenuNode 不含 remark/createTime 等，必须用详情） */
function fillEditForm(detail: SysMenu) {
  form.parentId = detail.parentId ?? 0;
  form.menuName = detail.menuName ?? "";
  form.menuType = detail.menuType;
  form.routerName = detail.routerName ?? "";
  form.path = detail.path ?? "";
  form.permission = detail.permission ?? "";
  form.metaInfo = detail.metaInfo ?? "";
  form.sortNum = detail.sortNum ?? 0;
  form.status = detail.status ?? MenuStatus.ENABLED;
  form.remark = detail.remark ?? "";
}

function closeFormDialog() {
  formDialogVisible.value = false;
  editTargetMenuId.value = null;
  editingNodeRef = null;
  formReady.value = false;
}

async function handleSubmit() {
  if (submitting.value) return;
  if (!formRef.value) return;
  if (!formReady.value) return; // 编辑详情未加载完成时禁止提交

  // 快照提交时的会话/目标/模式/表单内容：提交期间即使有任何外部变化，
  // payload 仍使用快照、成功后只关闭仍属于本次会话的对话框（P1 修复）
  const session = dialogSession;
  const targetMenuId = editTargetMenuId.value;
  const mode = dialogMode.value;
  const snapshot = { ...form };

  submitting.value = true;
  try {
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return; // finally 释放锁

    const isButton = snapshot.menuType === MenuType.BUTTON ? 1 : 0;
    if (mode === "edit" && targetMenuId != null) {
      const data: UpdateMenuRequest = {
        parentId: snapshot.parentId,
        menuName: snapshot.menuName,
        menuType: snapshot.menuType,
        routerName: snapshot.routerName || undefined,
        path: snapshot.path || undefined,
        permission: snapshot.permission || undefined,
        metaInfo: snapshot.metaInfo || undefined,
        isButton,
        sortNum: snapshot.sortNum,
        remark: snapshot.remark || undefined
      };
      await updateMenu(targetMenuId, data);
      message.success("更新成功");
    } else {
      const data: CreateMenuRequest = {
        parentId: snapshot.parentId,
        menuName: snapshot.menuName,
        menuType: snapshot.menuType,
        routerName: snapshot.routerName || undefined,
        path: snapshot.path || undefined,
        permission: snapshot.permission || undefined,
        metaInfo: snapshot.metaInfo || undefined,
        isButton,
        sortNum: snapshot.sortNum,
        status: snapshot.status,
        remark: snapshot.remark || undefined
      };
      await createMenu(data);
      message.success("创建成功");
    }
    // 仅当对话框仍属于本次会话时才关闭；刷新树始终执行（最新意图）
    if (session === dialogSession) {
      closeFormDialog();
    }
    fetchTree("user");
  } catch (err) {
    // API 请求失败（含业务错误码 MENU_* 等）已由请求层统一提示；
    // API 层同步参数校验异常（RangeError，如权限码格式非法）在此直接提示，
    // 避免被静默吞掉（请求不会发出，用户也需要看到原因）。
    // 失败均保留用户输入（不关闭对话框）。
    if (err instanceof RangeError || (err as Error)?.name === "RangeError") {
      message.error((err as Error).message || "参数校验失败");
    }
  } finally {
    submitting.value = false;
  }
}

/* ========================= 详情 ========================= */

async function openDetailDialog(node: MenuNode) {
  // 先用行数据立即展示，异步加载最新详情
  detailMenu.value = {
    menuId: node.menuId,
    parentId: node.parentId ?? 0,
    menuName: node.menuName ?? "",
    menuType: node.menuType,
    routerName: node.routerName ?? "",
    path: node.path ?? "",
    permission: node.permission ?? "",
    metaInfo: node.metaInfo ?? "",
    isButton: node.isButton ?? 0,
    sortNum: node.sortNum ?? 0,
    isSystem: node.isSystem ?? 0,
    status: node.status ?? MenuStatus.ENABLED,
    remark: "",
    creatorId: 0,
    createTime: "",
    updaterId: 0,
    updateTime: ""
  };
  detailDialogVisible.value = true;

  const seq = ++detailRequestSeq;
  const targetMenuId = node.menuId;
  detailLoading.value = true;
  try {
    const fresh = await getMenu(targetMenuId);
    // 仅当仍展示同一目标且请求未过期时才写入
    if (!mountedRef.value) return;
    if (seq !== detailRequestSeq) return;
    if (detailMenu.value?.menuId !== targetMenuId) return;
    detailMenu.value = fresh;
  } catch {
    // 加载失败保留行数据兜底；错误提示已由请求层处理
  } finally {
    if (seq === detailRequestSeq) {
      detailLoading.value = false;
    }
  }
}

/* ========================= 启停 ========================= */

async function handleStatusChange(node: MenuNode) {
  if (operatingIds.value.has(node.menuId)) return;
  const targetStatus =
    node.status === MenuStatus.ENABLED ? MenuStatus.DISABLED : MenuStatus.ENABLED;
  const actionText = targetStatus === MenuStatus.ENABLED ? "启用" : "停用";
  // 在弹出确认框前立即上锁，防止快速双击打开多个确认框导致重复请求
  operatingIds.value.add(node.menuId);
  try {
    try {
      await message.confirm(`确定${actionText}菜单"${node.menuName}"吗？`, "确认");
    } catch {
      return; // 用户取消确认，由 finally 统一释放锁
    }
    await changeMenuStatus(node.menuId, targetStatus);
    message.success(`${actionText}成功`);
    fetchTree("user");
  } catch {
    // 错误提示已由请求层处理（含 MENU_PARENT_DISABLED / MENU_HAS_ENABLED_CHILDREN 等）
  } finally {
    operatingIds.value.delete(node.menuId);
  }
}

/* ========================= 删除 ========================= */

async function handleDelete(node: MenuNode) {
  if (operatingIds.value.has(node.menuId)) return;
  // 在弹出确认框前立即上锁，防止快速双击打开多个确认框导致重复请求
  operatingIds.value.add(node.menuId);
  try {
    try {
      await message.confirm(
        `确定删除菜单"${node.menuName}"吗？删除后不可恢复。`,
        "删除确认"
      );
    } catch {
      return; // 用户取消确认，由 finally 统一释放锁
    }
    await deleteMenu(node.menuId);
    message.success("删除成功");
    fetchTree("user");
  } catch {
    // 错误提示已由请求层处理（含 MENU_HAS_CHILDREN / MENU_NOT_FOUND 等）
  } finally {
    operatingIds.value.delete(node.menuId);
  }
}

/* ========================= 生命周期 ========================= */

onMounted(() => {
  mountedRef.value = true;
  fetchTree("user");
});

onBeforeUnmount(() => {
  mountedRef.value = false;
  // 使任何飞行中的请求立即失效：其 seq 将不再等于当前值
  requestSeq++;
  detailRequestSeq++;
  ownerRequestSeq++;
  ownerDialogSession++;
});
</script>

<template>
  <div v-auth="'system:menu:list'" class="menu-manage">
    <!-- 工具栏 -->
    <div class="toolbar">
      <h2>菜单管理</h2>
      <div class="toolbar-actions">
        <el-button
          :icon="Refresh"
          circle
          aria-label="刷新菜单树"
          @click="fetchTree('user')"
        />
        <el-button
          v-auth="'system:menu:create'"
          type="primary"
          :icon="Plus"
          @click="openCreateDialog"
        >
          新建菜单
        </el-button>
      </div>
    </div>

    <!-- 空状态 -->
    <el-empty v-if="!loading && menuTree.length === 0" description="暂无菜单" />

    <!-- 菜单树形表格 -->
    <el-table
      v-loading="loading"
      :data="menuTree"
      row-key="menuId"
      :tree-props="{ children: 'children' }"
      default-expand-all
      stripe
      style="width: 100%"
    >
      <el-table-column prop="menuName" label="菜单名称" min-width="160" show-overflow-tooltip />
      <el-table-column label="类型" width="80">
        <template #default="scope">
          <el-tag v-if="scope?.row" :type="menuTypeTagType(scope.row.menuType)" size="small">
            {{ menuTypeLabel(scope.row.menuType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="routerName" label="路由名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="path" label="路径" min-width="140" show-overflow-tooltip />
      <el-table-column prop="permission" label="权限标识" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="scope">
          <el-tag v-if="scope?.row" :type="menuStatusTagType(scope.row.status)" size="small">
            {{ menuStatusLabel(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sortNum" label="排序" width="70" />
      <el-table-column label="系统保留" width="100">
        <template #default="scope">
          <!-- 安全属性只认后端 isSystem，不凭名称/路由推断 -->
          <el-tag v-if="scope?.row && scope.row.isSystem === 1" type="warning" size="small">
            系统保留
          </el-tag>
          <span v-else-if="scope?.row">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="380" fixed="right">
        <template #default="scope">
          <template v-if="scope?.row">
            <el-button
              link
              type="primary"
              :icon="View"
              @click="openDetailDialog(scope.row)"
            >
              详情
            </el-button>
            <el-button
              v-auth="'system:menu:update'"
              link
              type="primary"
              :icon="Edit"
              :disabled="operatingIds.has(scope.row.menuId)"
              @click="openEditDialog(scope.row)"
            >
              编辑
            </el-button>
            <el-button
              v-auth="'system:menu:update'"
              link
              :type="scope.row.status === MenuStatus.ENABLED ? 'warning' : 'success'"
              :disabled="operatingIds.has(scope.row.menuId)"
              @click="handleStatusChange(scope.row)"
            >
              {{ scope.row.status === MenuStatus.ENABLED ? "停用" : "启用" }}
            </el-button>
            <el-button
              v-auth="'system:menu:delete'"
              link
              type="danger"
              :icon="Delete"
              :disabled="operatingIds.has(scope.row.menuId)"
              @click="handleDelete(scope.row)"
            >
              删除
            </el-button>
            <el-button
              v-if="canManageOwners"
              link
              type="primary"
              :disabled="ownerSubmitting"
              @click="openOwnerDialog(scope.row)"
            >
              管理归属
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑对话框 -->
    <el-dialog
      v-model="formDialogVisible"
      :title="dialogMode === 'edit' ? '编辑菜单' : '新建菜单'"
      width="640px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        v-loading="dialogMode === 'edit' && !formReady"
        :model="form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item label="父菜单">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            :props="{ label: 'menuName', children: 'children', disabled: parentNodeDisabled }"
            node-key="menuId"
            check-strictly
            clearable
            default-expand-all
            placeholder="请选择父菜单（不选则为根节点）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="节点类型">
          <el-radio-group v-model="form.menuType" @change="handleMenuTypeChange">
            <el-radio :value="MenuType.DIRECTORY">目录</el-radio>
            <el-radio :value="MenuType.MENU">菜单</el-radio>
            <el-radio :value="MenuType.BUTTON">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称" prop="menuName">
          <el-input v-model="form.menuName" :maxlength="MENU_NAME_MAX" placeholder="请输入菜单名称" />
        </el-form-item>
        <template v-if="form.menuType !== MenuType.BUTTON">
          <el-form-item label="路由名称" prop="routerName">
            <el-input
              v-model="form.routerName"
              :maxlength="ROUTER_NAME_MAX"
              placeholder="须以字母开头，仅含字母、数字、下划线、连字符"
            />
          </el-form-item>
          <el-form-item label="路径" prop="path">
            <el-input v-model="form.path" :maxlength="PATH_MAX" placeholder="以 / 开头，如 /system/menu" />
          </el-form-item>
        </template>
        <el-form-item label="权限标识" prop="permission">
          <el-input
            v-model="form.permission"
            :maxlength="PERMISSION_MAX"
            :placeholder="form.menuType === MenuType.BUTTON ? '按钮必须填写权限标识' : '可选，如 system:menu:list'"
          />
        </el-form-item>
        <el-form-item label="metaInfo" prop="metaInfo">
          <el-input
            v-model="form.metaInfo"
            type="textarea"
            :rows="2"
            :maxlength="META_INFO_MAX"
            placeholder="可选，合法 JSON 对象，如 {}"
          />
        </el-form-item>
        <el-form-item label="排序" prop="sortNum">
          <el-input-number v-model="form.sortNum" :min="0" :max="SORT_NUM_MAX" controls-position="right" />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'create'" label="状态">
          <el-switch
            v-model="form.status"
            :active-value="MenuStatus.ENABLED"
            :inactive-value="MenuStatus.DISABLED"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" :maxlength="REMARK_MAX" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeFormDialog">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!formReady"
          @click="handleSubmit"
        >
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailDialogVisible" title="菜单详情" width="560px">
      <div v-if="detailMenu" v-loading="detailLoading" class="detail-grid">
        <div class="detail-item"><span class="label">ID</span><span>{{ detailMenu.menuId }}</span></div>
        <div class="detail-item"><span class="label">名称</span><span>{{ detailMenu.menuName }}</span></div>
        <div class="detail-item"><span class="label">类型</span><span>{{ menuTypeLabel(detailMenu.menuType) }}</span></div>
        <div class="detail-item">
          <span class="label">系统保留</span>
          <span>
            <el-tag v-if="detailMenu.isSystem === 1" type="warning" size="small">系统保留</el-tag>
            <span v-else>-</span>
          </span>
        </div>
        <div class="detail-item"><span class="label">状态</span><span>{{ menuStatusLabel(detailMenu.status) }}</span></div>
        <div class="detail-item"><span class="label">父节点</span><span>{{ detailMenu.parentId }}</span></div>
        <div class="detail-item"><span class="label">路由名称</span><span>{{ detailMenu.routerName || "-" }}</span></div>
        <div class="detail-item"><span class="label">路径</span><span>{{ detailMenu.path || "-" }}</span></div>
        <div class="detail-item"><span class="label">权限标识</span><span>{{ detailMenu.permission || "-" }}</span></div>
        <div class="detail-item"><span class="label">isButton</span><span>{{ detailMenu.isButton }}</span></div>
        <div class="detail-item"><span class="label">排序</span><span>{{ detailMenu.sortNum }}</span></div>
        <div class="detail-item"><span class="label">metaInfo</span><span>{{ detailMenu.metaInfo || "-" }}</span></div>
        <div class="detail-item"><span class="label">备注</span><span>{{ detailMenu.remark || "-" }}</span></div>
        <div class="detail-item"><span class="label">创建时间</span><span>{{ detailMenu.createTime || "-" }}</span></div>
        <div class="detail-item"><span class="label">更新时间</span><span>{{ detailMenu.updateTime || "-" }}</span></div>
      </div>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- Owner 是资源管理边界，不等同于角色菜单权限授权。 -->
    <el-dialog
      v-model="ownerDialogVisible"
      :title="ownerTargetMenu ? `管理归属 - ${ownerTargetMenu.menuName}` : '管理归属'"
      width="640px"
      destroy-on-close
      :close-on-click-modal="false"
      :close-on-press-escape="!ownerSubmitting"
      :show-close="!ownerSubmitting"
      @closed="closeOwnerDialog"
    >
      <div v-if="ownerLoading" class="owner-loading">
        <el-skeleton :rows="5" animated />
      </div>
      <div v-else class="owner-content">
        <el-alert
          title="管理归属不授予菜单权限"
          description="此处只决定哪些角色可以管理该菜单，不会修改角色的菜单 permission 或菜单授权。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="ownerReady && ownerRoleIds.length === 0"
          class="system-managed-notice"
          title="系统托管，仅超级管理员可管理"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-checkbox-group
          v-model="ownerRoleIds"
          class="owner-role-list"
          :disabled="!ownerReady || ownerSubmitting"
        >
          <div v-for="role in ownerRoles" :key="role.roleId" class="owner-role-row">
            <el-checkbox :label="role.roleId" :disabled="ownerRoleDisabled(role)">
              <span class="owner-role-name">{{ role.roleName }}</span>
              <span class="owner-role-key">{{ role.roleKey }}</span>
            </el-checkbox>
            <div class="owner-role-meta">
              <el-tag :type="role.status === RoleStatus.ENABLED ? 'success' : 'info'" size="small">
                {{ role.status === RoleStatus.ENABLED ? "启用" : "停用" }}
              </el-tag>
              <el-tag v-if="role.isSystem === 1" type="warning" size="small">系统保留</el-tag>
            </div>
          </div>
        </el-checkbox-group>
      </div>
      <template #footer>
        <el-button :disabled="ownerSubmitting" @click="closeOwnerDialog">取消</el-button>
        <el-button
          type="primary"
          :loading="ownerSubmitting"
          :disabled="!ownerReady || ownerLoading"
          @click="handleReplaceOwners"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.menu-manage {
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
  align-items: center;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 24px;
  min-height: 60px;
}

.detail-item {
  display: flex;
  gap: 8px;
  font-size: 14px;

  .label {
    color: #909399;
    white-space: nowrap;
  }
}

.owner-loading {
  padding: 16px;
}

.owner-content {
  display: grid;
  gap: 12px;
}

.owner-role-list {
  display: grid;
  gap: 8px;
}

.owner-role-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 36px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.owner-role-name {
  margin-right: 8px;
}

.owner-role-key {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.owner-role-meta {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
</style>

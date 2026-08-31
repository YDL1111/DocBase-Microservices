<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import type { FormInstance, FormRules } from "element-plus";
import { Delete, Edit, OfficeBuilding, Plus, Refresh } from "@element-plus/icons-vue";
import { message } from "@/utils/message";
import {
  createOrganization,
  deleteOrganization,
  listOrganizations,
  updateOrganization
} from "@/api/organization";
import type { OrganizationRequest, SysOrganization } from "@/api/types";

const loading = ref(false);
const organizations = ref<SysOrganization[]>([]);
const dialogVisible = ref(false);
const submitting = ref(false);
const editing = ref<SysOrganization | null>(null);
const formRef = ref<FormInstance>();
const operatingIds = ref(new Set<number>());
let mounted = false;
let requestSeq = 0;

const form = reactive<OrganizationRequest>({
  parentId: 0,
  organizationName: "",
  organizationCode: "",
  sortNum: 0,
  status: 1,
  remark: ""
});

const rules: FormRules = {
  organizationName: [
    { required: true, message: "请输入组织名称", trigger: "blur" },
    { max: 128, message: "组织名称不超过 128 个字符", trigger: "blur" }
  ],
  organizationCode: [
    { required: true, message: "请输入组织编码", trigger: "blur" },
    { pattern: /^[a-z][a-z0-9_-]{1,63}$/, message: "以小写字母开头，可使用小写字母、数字、_、-", trigger: "blur" }
  ]
};

function buildTree(rows: SysOrganization[]): SysOrganization[] {
  const nodes = new Map<number, SysOrganization>();
  rows.forEach(row => nodes.set(row.organizationId, { ...row, children: [] }));
  const roots: SysOrganization[] = [];
  nodes.forEach(node => {
    const parent = nodes.get(node.parentId);
    if (parent && parent.organizationId !== node.organizationId) parent.children!.push(node);
    else roots.push(node);
  });
  const sort = (items: SysOrganization[]) => {
    items.sort((a, b) => a.sortNum - b.sortNum || a.organizationId - b.organizationId);
    items.forEach(item => sort(item.children ?? []));
  };
  sort(roots);
  return roots;
}

const tree = computed(() => buildTree(organizations.value));
const activeParentTree = computed(() => {
  const clone = (nodes: SysOrganization[]): SysOrganization[] => nodes.map(node => ({
    ...node,
    disabled: node.status !== 1,
    children: clone(node.children ?? [])
  } as SysOrganization));
  return clone(tree.value);
});

function organizationName(id: number | null | undefined): string {
  if (!id) return "顶级组织";
  return organizations.value.find(item => item.organizationId === id)?.organizationName ?? `组织 ${id}`;
}

async function fetchOrganizations() {
  const seq = ++requestSeq;
  loading.value = true;
  try {
    const rows = await listOrganizations();
    if (mounted && seq === requestSeq) organizations.value = rows;
  } catch {
    // 请求层统一提示。
  } finally {
    if (mounted && seq === requestSeq) loading.value = false;
  }
}

function resetForm(parentId = 0) {
  form.parentId = parentId;
  form.organizationName = "";
  form.organizationCode = "";
  form.sortNum = 0;
  form.status = 1;
  form.remark = "";
}

function openCreate(parent?: SysOrganization) {
  editing.value = null;
  resetForm(parent?.organizationId ?? 0);
  dialogVisible.value = true;
}

function openEdit(row: SysOrganization) {
  editing.value = row;
  form.parentId = row.parentId;
  form.organizationName = row.organizationName;
  form.organizationCode = row.organizationCode;
  form.sortNum = row.sortNum;
  form.status = row.status;
  form.remark = row.remark;
  dialogVisible.value = true;
}

async function submit() {
  if (!formRef.value || submitting.value) return;
  submitting.value = true;
  try {
    if (!await formRef.value.validate().catch(() => false)) return;
    const payload: OrganizationRequest = {
      parentId: form.parentId,
      organizationName: form.organizationName.trim(),
      organizationCode: form.organizationCode.trim(),
      sortNum: form.sortNum,
      status: form.status,
      remark: form.remark?.trim() ?? ""
    };
    if (editing.value) await updateOrganization(editing.value.organizationId, payload);
    else await createOrganization(payload);
    message.success(editing.value ? "组织已更新" : "组织已创建");
    dialogVisible.value = false;
    await fetchOrganizations();
  } catch {
    // 失败保留输入。
  } finally {
    submitting.value = false;
  }
}

async function remove(row: SysOrganization) {
  if (operatingIds.value.has(row.organizationId)) return;
  operatingIds.value.add(row.organizationId);
  try {
    await message.confirm(`确定删除组织“${row.organizationName}”吗？`, "删除组织");
    await deleteOrganization(row.organizationId);
    message.success("组织已删除");
    await fetchOrganizations();
  } catch {
    // 取消与请求失败均不改变页面数据。
  } finally {
    operatingIds.value.delete(row.organizationId);
  }
}

onMounted(() => { mounted = true; fetchOrganizations(); });
onBeforeUnmount(() => { mounted = false; requestSeq++; });
</script>

<template>
  <div v-auth="'system:org:list'" class="organization-page">
    <header class="page-header">
      <div>
        <p class="eyebrow"><el-icon><OfficeBuilding /></el-icon> SYSTEM DIRECTORY</p>
        <h2>组织管理</h2>
        <p class="description">维护人员归属，并作为知识库“部门可见”的隔离边界。</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" circle aria-label="刷新组织" @click="fetchOrganizations" />
        <el-button v-auth="'system:org:create'" type="primary" :icon="Plus" @click="openCreate()">新建顶级组织</el-button>
      </div>
    </header>

    <div class="summary-strip">
      <span><strong>{{ organizations.length }}</strong> 个组织节点</span>
      <span><i class="status-dot active" /> {{ organizations.filter(item => item.status === 1).length }} 个启用</span>
      <span><i class="status-dot" /> {{ organizations.filter(item => item.status !== 1).length }} 个停用</span>
    </div>

    <el-table v-loading="loading" :data="tree" row-key="organizationId" default-expand-all :tree-props="{ children: 'children' }">
      <el-table-column prop="organizationName" label="组织名称" min-width="230">
        <template #default="{ row }">
          <span class="organization-name">{{ row.organizationName }}</span>
          <el-tag v-if="row.isSystem === 1" size="small" type="info">系统</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="organizationCode" label="组织编码" min-width="180" />
      <el-table-column label="上级组织" min-width="150"><template #default="{ row }">{{ organizationName(row.parentId) }}</template></el-table-column>
      <el-table-column prop="sortNum" label="排序" width="90" align="center" />
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? "启用" : "停用" }}</el-tag></template></el-table-column>
      <el-table-column prop="remark" label="说明" min-width="210" show-overflow-tooltip />
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button v-auth="'system:org:create'" link type="primary" :icon="Plus" :disabled="row.status !== 1" @click="openCreate(row)">新建下级</el-button>
          <el-button v-auth="'system:org:update'" link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
          <el-button v-auth="'system:org:delete'" link type="danger" :icon="Delete" :disabled="row.isSystem === 1 || operatingIds.has(row.organizationId)" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑组织' : '新建组织'" width="560px" destroy-on-close :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="上级组织" prop="parentId">
          <el-tree-select v-model="form.parentId" :data="activeParentTree" node-key="organizationId" :props="{ label: 'organizationName', value: 'organizationId', children: 'children', disabled: 'disabled' }" check-strictly clearable placeholder="不选择表示顶级组织" style="width:100%" />
        </el-form-item>
        <el-form-item label="组织名称" prop="organizationName"><el-input v-model="form.organizationName" maxlength="128" /></el-form-item>
        <el-form-item label="组织编码" prop="organizationCode"><el-input v-model="form.organizationCode" maxlength="64" :disabled="editing?.isSystem === 1" /></el-form-item>
        <el-form-item label="状态" prop="status"><el-segmented v-model="form.status" :options="[{ label: '启用', value: 1 }, { label: '停用', value: 0 }]" /></el-form-item>
        <el-form-item label="排序" prop="sortNum"><el-input-number v-model="form.sortNum" :min="0" :max="9999" /></el-form-item>
        <el-form-item label="备注" prop="remark"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="512" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="submit">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.organization-page { padding: 18px 20px 24px; background: #fff; border-radius: 6px; }
.page-header { display:flex; align-items:flex-start; justify-content:space-between; gap:24px; padding: 4px 0 18px; border-bottom:1px solid #e8edf5; }
.page-header h2 { margin: 3px 0 5px; color:#1f2d3d; font-size:22px; font-weight:650; letter-spacing:0; }
.eyebrow { display:flex; align-items:center; gap:6px; margin:0; color:#5479a8; font-size:11px; font-weight:700; letter-spacing:.08em; }
.description { margin:0; color:#7a8798; font-size:13px; }
.header-actions { display:flex; gap:8px; padding-top:10px; }
.summary-strip { display:flex; gap:28px; padding:14px 2px; color:#667487; font-size:13px; }
.summary-strip strong { color:#315f96; font-size:17px; }
.status-dot { display:inline-block; width:7px; height:7px; margin-right:6px; border-radius:50%; background:#aab4c1; }
.status-dot.active { background:#42b983; }
.organization-name { margin-right:8px; color:#27364a; font-weight:600; }
@media (max-width: 760px) { .page-header { flex-direction:column; } .header-actions { width:100%; justify-content:flex-end; } .summary-strip { gap:12px; flex-wrap:wrap; } }
</style>

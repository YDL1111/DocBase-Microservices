<script setup lang="ts">
/**
 * 知识库成员管理标签页。
 * 功能：查看成员列表、添加成员、修改角色、移除成员。
 *
 * 权限控制：
 *  - 按钮显示由 v-auth="'knowledge:member:manage'" 控制；
 *  - 最终权限由后端决定（403 表示无权操作）；
 *  - 前端仅做体验控制（按钮隐藏），不替代后端鉴权。
 */
import { onMounted, reactive, ref, watch } from "vue";
import type { FormInstance, FormRules } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import {
  listMembers,
  addMember,
  updateMemberRole,
  removeMember
} from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { message } from "@/utils/message";
import { formatBackendDateTime } from "@/utils/date-time";
import {
  MemberRole,
  memberRoleLabel,
  type AddMemberRequest
} from "@/api/types";

const props = defineProps<{ knowledgeBaseId: number | null }>();

const knowledgeStore = useKnowledgeStoreHook();

const loading = ref(false);
const dialogVisible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

/** 操作中的用户 ID 集合（角色修改/移除防重复点击） */
const operatingIds = ref<Set<number>>(new Set());

const form = reactive<AddMemberRequest>({
  userId: 0,
  role: MemberRole.VIEWER
});

const rules: FormRules = {
  userId: [{ required: true, message: "请输入用户 ID", trigger: "blur" }],
  role: [{ required: true, message: "请选择角色", trigger: "blur" }]
};

const roleOptions = [
  { value: MemberRole.ADMIN, label: "管理员" },
  { value: MemberRole.EDITOR, label: "编辑者" },
  { value: MemberRole.VIEWER, label: "查看者" }
];

async function fetchMembers() {
  if (props.knowledgeBaseId === null) return;
  loading.value = true;
  // 捕获请求序号，写入时校验，防止异步乱序响应串库
  const seq = knowledgeStore.getRequestSeq();
  try {
    const members = await listMembers(props.knowledgeBaseId);
    knowledgeStore.setMembers(members, seq);
  } catch {
    // 错误提示已由请求层处理
  } finally {
    loading.value = false;
  }
}

function openAddDialog() {
  form.userId = 0;
  form.role = MemberRole.VIEWER;
  dialogVisible.value = true;
}

async function handleAdd() {
  if (!formRef.value || submitting.value) return;
  if (props.knowledgeBaseId === null) return;
  submitting.value = true;
  try {
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;
    await addMember(props.knowledgeBaseId, { ...form });
    message.success("添加成功");
    dialogVisible.value = false;
    fetchMembers();
  } catch {
    // 错误提示已由请求层处理，失败时保留表单供修正后重试
  } finally {
    submitting.value = false;
  }
}

async function handleRoleChange(userId: number, role: number) {
  if (props.knowledgeBaseId === null || operatingIds.value.has(userId)) return;
  operatingIds.value.add(userId);
  try {
    await updateMemberRole(props.knowledgeBaseId, userId, { role });
    knowledgeStore.updateMemberRole(userId, role);
    message.success("角色更新成功");
  } catch {
    fetchMembers(); // 失败时刷新以恢复原状态
  } finally {
    operatingIds.value.delete(userId);
  }
}

async function handleRemove(userId: number) {
  if (props.knowledgeBaseId === null || operatingIds.value.has(userId)) return;
  operatingIds.value.add(userId);
  try {
    // 用户点击"取消"时 confirm 会 reject，必须捕获，否则产生未处理的 Promise 异常
    try {
      await message.confirmDanger("确定移除该成员吗？移除后其将无法访问本知识库。", "移除确认");
    } catch {
      return;
    }
    await removeMember(props.knowledgeBaseId, userId);
    knowledgeStore.removeMember(userId);
    message.success("移除成功");
  } catch {
    // 错误提示已由请求层处理
  } finally {
    operatingIds.value.delete(userId);
  }
}

/**
 * 成员列表在知识库详情加载完成后才请求。
 */
watch(
  () => knowledgeStore.currentBase,
  (base) => {
    if (base && base.id === props.knowledgeBaseId) {
      fetchMembers();
    }
  },
  { immediate: true }
);
</script>

<template>
  <div class="detail-members">
    <div class="members-toolbar">
      <p class="members-hint">成员角色决定其对知识库的访问与维护权限</p>
      <el-button
        v-auth="'knowledge:member:manage'"
        type="primary"
        :icon="Plus"
        @click="openAddDialog"
      >
        添加成员
      </el-button>
    </div>

    <el-empty
      v-if="!loading && knowledgeStore.members.length === 0"
      description="暂无成员"
      :image-size="72"
    />

    <el-table v-else v-loading="loading" :data="knowledgeStore.members" stripe>
      <el-table-column prop="userId" label="用户 ID" width="120" />
      <el-table-column label="角色" width="220">
        <template #default="{ row }">
          <el-select
            v-if="row.memberRole !== MemberRole.OWNER"
            v-auth="'knowledge:member:manage'"
            :model-value="row.memberRole"
            :disabled="operatingIds.has(row.userId)"
            @change="(val: number) => handleRoleChange(row.userId, val)"
          >
            <el-option
              v-for="opt in roleOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
          <el-tag v-else type="warning" effect="light">{{ memberRoleLabel(row.memberRole) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="加入时间" min-width="170">
        <template #default="{ row }">{{ formatBackendDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.memberRole !== MemberRole.OWNER"
            v-auth="'knowledge:member:manage'"
            link
            type="danger"
            :disabled="operatingIds.has(row.userId)"
            @click="handleRemove(row.userId)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 添加成员对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="添加成员"
      width="420px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户 ID" prop="userId">
          <el-input-number
            v-model="form.userId"
            :min="1"
            style="width: 100%"
            placeholder="请输入用户 ID"
          />
          <p class="form-hint">用户 ID 可在「系统管理 → 用户管理」中查看。</p>
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%">
            <el-option
              v-for="opt in roleOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleAdd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.detail-members {
  padding: 16px 0;
}

.members-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.members-hint {
  margin: 0;
  color: var(--docbase-subtle);
  font-size: 12px;
}

.form-hint {
  margin: 6px 0 0;
  color: var(--docbase-subtle);
  font-size: 12px;
  line-height: 1.5;
}
</style>

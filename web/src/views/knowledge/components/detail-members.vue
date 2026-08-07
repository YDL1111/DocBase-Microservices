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
import { ElMessageBox } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import {
  listMembers,
  addMember,
  updateMemberRole,
  removeMember
} from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { message } from "@/utils/message";
import {
  MemberRole,
  memberRoleLabel,
  type AddMemberRequest
} from "@/api/types";

const props = defineProps<{ knowledgeBaseId: number | null }>();

const knowledgeStore = useKnowledgeStoreHook();

const loading = ref(false);
const dialogVisible = ref(false);
const formRef = ref<FormInstance>();

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
  if (!formRef.value) return;
  if (props.knowledgeBaseId === null) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  try {
    await addMember(props.knowledgeBaseId, { ...form });
    message.success("添加成功");
    dialogVisible.value = false;
    fetchMembers();
  } catch {
    // 错误提示已由请求层处理
  }
}

async function handleRoleChange(userId: number, role: number) {
  if (props.knowledgeBaseId === null) return;
  try {
    await updateMemberRole(props.knowledgeBaseId, userId, { role });
    knowledgeStore.updateMemberRole(userId, role);
    message.success("角色更新成功");
  } catch {
    fetchMembers(); // 失败时刷新以恢复原状态
  }
}

async function handleRemove(userId: number) {
  if (props.knowledgeBaseId === null) return;
  await ElMessageBox.confirm("确定移除该成员吗？", "移除确认", {
    type: "warning"
  });
  try {
    await removeMember(props.knowledgeBaseId, userId);
    knowledgeStore.removeMember(userId);
    message.success("移除成功");
  } catch {
    // 错误提示已由请求层处理
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
    <div class="toolbar">
      <el-button
        v-auth="'knowledge:member:manage'"
        type="primary"
        :icon="Plus"
        @click="openAddDialog"
      >
        添加成员
      </el-button>
    </div>

    <el-table v-loading="loading" :data="knowledgeStore.members" stripe>
      <el-table-column prop="userId" label="用户 ID" width="120" />
      <el-table-column label="角色" width="200">
        <template #default="{ row }">
          <el-select
            v-if="row.memberRole !== MemberRole.OWNER"
            v-auth="'knowledge:member:manage'"
            :model-value="row.memberRole"
            @change="(val: number) => handleRoleChange(row.userId, val)"
          >
            <el-option
              v-for="opt in roleOptions"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
          <span v-else>{{ memberRoleLabel(row.memberRole) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="加入时间" width="180" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.memberRole !== MemberRole.OWNER"
            v-auth="'knowledge:member:manage'"
            link
            type="danger"
            @click="handleRemove(row.userId)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 添加成员对话框 -->
    <el-dialog v-model="dialogVisible" title="添加成员" width="400px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户 ID" prop="userId">
          <el-input-number
            v-model="form.userId"
            :min="1"
            style="width: 100%"
            placeholder="请输入用户 ID"
          />
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
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAdd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.detail-members {
  padding: 16px 0;
}

.toolbar {
  margin-bottom: 16px;
}
</style>

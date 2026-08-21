<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { isAxiosError } from "axios";
import type { FormInstance, FormRules, UploadFile, UploadFiles, UploadRawFile } from "element-plus";
import { UploadFilled } from "@element-plus/icons-vue";
import { uploadDocument } from "@/api/knowledge";
import type { FolderNode } from "@/api/types";
import { message } from "@/utils/message";

const DOCUMENT_UPLOAD_MAX_BYTES = 100 * 1024 * 1024;
const DOCUMENT_UPLOAD_ACCEPT = ".pdf,.docx,.xlsx,.pptx,.txt";
const ALLOWED_EXTENSIONS = new Set(["pdf", "docx", "xlsx", "pptx", "txt"]);

const props = defineProps<{
  modelValue: boolean;
  knowledgeBaseId: number | null;
  folderTree: FolderNode[];
  defaultFolderId: number;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  uploaded: [documentId: number, sourceKnowledgeBaseId: number];
  refresh: [sourceKnowledgeBaseId: number];
}>();

const formRef = ref<FormInstance>();
const selectedFile = ref<File | null>(null);
const uploadFiles = ref<UploadFiles>([]);
const uploading = ref(false);
const progress = ref(0);
const submittedFingerprint = ref<string | null>(null);
const clientRequestId = ref<string | null>(null);
const sourceChangedDuringUpload = ref(false);
const form = reactive({ title: "", folderId: props.defaultFolderId || 0, visibility: 1, publishForChat: true });

const rules: FormRules = {
  title: [{ required: true, message: "请输入文档标题", trigger: "blur" }, { max: 256, message: "标题不能超过 256 个字符", trigger: "blur" }],
  folderId: [{ required: true, message: "请选择目标目录", trigger: "change" }],
  visibility: [{ required: true, message: "请选择可见性", trigger: "change" }]
};

const folderOptions = computed(() => [{ id: 0, name: "根目录", children: props.folderTree }]);
const folderProps = { value: "id", label: "name", children: "children" };

function createAttemptId(): string {
  return crypto.randomUUID();
}

function filenameTitle(file: File): string {
  const dot = file.name.lastIndexOf(".");
  return (dot > 0 ? file.name.slice(0, dot) : file.name).trim();
}

function fileExtension(file: File): string {
  const dot = file.name.lastIndexOf(".");
  return dot > 0 ? file.name.slice(dot + 1).toLowerCase() : "";
}

function validateFile(file: File): boolean {
  if (!ALLOWED_EXTENSIONS.has(fileExtension(file))) {
    message.error("仅支持 PDF、DOCX、XLSX、PPTX 或 TXT 文件");
    return false;
  }
  if (file.size <= 0 || file.size > DOCUMENT_UPLOAD_MAX_BYTES) {
    message.error("文件大小必须大于 0 且不超过 100 MiB");
    return false;
  }
  return true;
}

function fingerprint(): string | null {
  const file = selectedFile.value;
  return file ? [file.name, file.size, file.lastModified, form.title, form.folderId, form.visibility, form.publishForChat].join("|") : null;
}

function beginNewAttempt(): void {
  clientRequestId.value = createAttemptId();
  submittedFingerprint.value = null;
}

function handleFileChange(file: UploadFile): void {
  const raw = file.raw;
  if (!raw || !validateFile(raw)) {
    selectedFile.value = null;
    uploadFiles.value = [];
    return;
  }
  selectedFile.value = raw;
  form.title = filenameTitle(raw);
  beginNewAttempt();
}

function handleFileExceed(files: File[]): void {
  uploadFiles.value = [];
  const file = files[0];
  if (file) handleFileChange({ name: file.name, raw: file } as UploadFile);
}

function beforeUpload(rawFile: UploadRawFile): boolean {
  return validateFile(rawFile);
}

function resetForm(): void {
  selectedFile.value = null;
  uploadFiles.value = [];
  form.title = "";
  form.folderId = props.defaultFolderId || 0;
  form.visibility = 1;
  form.publishForChat = true;
  progress.value = 0;
  clientRequestId.value = null;
  submittedFingerprint.value = null;
  formRef.value?.clearValidate();
}

function closeDialog(): void {
  if (uploading.value) return;
  emit("update:modelValue", false);
  resetForm();
}

type UploadError = { code: string; status?: number };

function uploadError(error: unknown): UploadError {
  if (isAxiosError(error)) {
    const responseCode = error.response?.data?.code;
    return {
      code: typeof responseCode === "string" ? responseCode : (error.code || ""),
      status: error.response?.status
    };
  }
  return { code: error instanceof Error ? error.message : "" };
}

function isUncertainFailure(error: UploadError): boolean {
  return error.status !== undefined && error.status >= 500 ||
    !error.code || error.code === "ECONNABORTED" || /network|timeout|reset/i.test(error.code);
}

function showUploadError(code: string): void {
  const known: Record<string, string> = {
    FILE_TOO_LARGE: "文件超过服务端允许的大小",
    UNSUPPORTED_FILE_TYPE: "不支持该文件类型",
    CONTENT_TYPE_MISMATCH: "文件内容类型不匹配",
    INVALID_FILENAME: "文件名不符合要求",
    FORBIDDEN: "没有上传文档的权限"
  };
  message.error(known[code] || "上传失败，请稍后重试");
}

async function submit(): Promise<void> {
  if (uploading.value || props.knowledgeBaseId === null || !selectedFile.value) return;
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  const currentFingerprint = fingerprint();
  if (!clientRequestId.value || (submittedFingerprint.value && submittedFingerprint.value !== currentFingerprint)) {
    beginNewAttempt();
  }
  const sourceKnowledgeBaseId = props.knowledgeBaseId;
  const attemptId = clientRequestId.value!;
  submittedFingerprint.value = currentFingerprint;
  uploading.value = true;
  progress.value = 0;
  try {
    const documentId = await uploadDocument(sourceKnowledgeBaseId, {
      file: selectedFile.value,
      clientRequestId: attemptId,
      title: form.title.trim(),
      folderId: form.folderId,
      visibility: form.visibility,
      publishForChat: form.publishForChat
    }, { onUploadProgress: percent => { progress.value = percent; } });
    message.success("文件上传成功，正在入库");
    emit("uploaded", documentId, sourceKnowledgeBaseId);
    emit("update:modelValue", false);
    resetForm();
  } catch (error) {
    const uploadFailure = uploadError(error);
    const code = uploadFailure.code;
    if (code === "UPLOAD_IN_PROGRESS") {
      message.info("该上传请求仍在处理中，已刷新文档列表");
      emit("refresh", sourceKnowledgeBaseId);
    } else if (code === "IDEMPOTENCY_CONFLICT") {
      beginNewAttempt();
      message.warning("上传参数已变更，请确认后重新提交");
    } else if (isUncertainFailure(uploadFailure)) {
      message.warning("上传结果未确认，请使用“开始上传”重试；将复用本次请求标识");
    } else {
      showUploadError(code);
    }
  } finally {
    uploading.value = false;
    if (sourceChangedDuringUpload.value) {
      sourceChangedDuringUpload.value = false;
      emit("update:modelValue", false);
      resetForm();
    }
  }
}

watch(() => props.modelValue, visible => {
  if (visible && !uploading.value) resetForm();
});
watch(() => props.defaultFolderId, folderId => {
  if (!selectedFile.value && !uploading.value) form.folderId = folderId || 0;
});
watch(() => props.knowledgeBaseId, (knowledgeBaseId, previousKnowledgeBaseId) => {
  if (knowledgeBaseId === previousKnowledgeBaseId) return;
  if (uploading.value) {
    sourceChangedDuringUpload.value = true;
    return;
  }
  emit("update:modelValue", false);
  resetForm();
});
watch([() => form.title, () => form.folderId, () => form.visibility, () => form.publishForChat], () => {
  if (submittedFingerprint.value && submittedFingerprint.value !== fingerprint()) beginNewAttempt();
});
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="上传文档"
    width="560px"
    :close-on-click-modal="!uploading"
    :close-on-press-escape="!uploading"
    :show-close="!uploading"
    @close="closeDialog"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
      <el-form-item label="文件" required>
        <el-upload
          v-model:file-list="uploadFiles"
          drag
          :auto-upload="false"
          :limit="1"
          :accept="DOCUMENT_UPLOAD_ACCEPT"
          :disabled="uploading"
          :before-upload="beforeUpload"
          :on-change="handleFileChange"
          :on-exceed="handleFileExceed"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖入单个文件，或<em>点击选择</em></div>
          <template #tip>
            <div class="el-upload__tip">支持 PDF、DOCX、XLSX、PPTX、TXT，最大 100 MiB。服务端会再次校验。</div>
          </template>
        </el-upload>
      </el-form-item>
      <el-form-item label="标题" prop="title"><el-input v-model="form.title" maxlength="256" show-word-limit :disabled="uploading" /></el-form-item>
      <el-form-item label="目标目录" prop="folderId">
        <el-tree-select v-model="form.folderId" :data="folderOptions" :props="folderProps" check-strictly default-expand-all :disabled="uploading" style="width: 100%" />
      </el-form-item>
      <el-form-item label="可见性" prop="visibility">
        <el-radio-group v-model="form.visibility" :disabled="uploading">
          <el-radio :value="1">私有</el-radio><el-radio :value="2">部门</el-radio><el-radio :value="3">公开</el-radio>
        </el-radio-group>
        <div v-if="form.visibility === 2" class="visibility-note">部门可见性当前按后端 fail-closed 策略处理，部门成员不保证可见。</div>
      </el-form-item>
      <el-form-item label="AI 问答">
        <el-switch v-model="form.publishForChat" :disabled="uploading" active-text="入库完成后用于问答" />
        <div class="visibility-note">关闭后文档将保存为草稿，不会被 AI 对话检索。</div>
      </el-form-item>
      <el-form-item v-if="uploading" label="传输进度"><el-progress :percentage="progress" /></el-form-item>
    </el-form>
    <p class="progress-note">上传进度仅表示浏览器到 Gateway/Knowledge 的传输，不代表异步入库完成。</p>
    <template #footer>
      <el-button :disabled="uploading" @click="closeDialog">取消</el-button>
      <el-button type="primary" :loading="uploading" :disabled="!selectedFile || uploading" @click="submit">开始上传</el-button>
    </template>
  </el-dialog>
</template>

<style lang="scss" scoped>
.visibility-note, .progress-note { margin: 8px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.progress-note { margin-left: 88px; }
</style>

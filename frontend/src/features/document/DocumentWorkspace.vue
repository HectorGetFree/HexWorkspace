<template>
  <section class="document-workspace">
    <div v-if="loading" class="document-state">正在加载文档…</div>
    <div v-else-if="loadError" class="document-state document-state-error"><p>{{ loadError }}</p><button type="button" @click="loadDocument">重试</button></div>
    <template v-else-if="document">
      <DocumentHeader :document="document" :dirty="dirty" :saving="saving" @save="saveDocument" />
      <div v-if="saveError" class="document-save-error">{{ saveError }}</div>
      <div v-if="conflict" class="document-conflict"><span>文档已在其他位置更新。当前修改尚未保存。</span><button type="button" @click="reloadLatest">加载最新版本</button></div>
      <DocumentContent :content="draft" :editable="true" @change="handleDraftChange" />
    </template>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import * as documentApi from '../../api/document'
import { ApiError } from '../../api/errors'
import type { DocumentDetail } from '../../types/document'
import DocumentContent from './DocumentContent.vue'
import DocumentHeader from './DocumentHeader.vue'

const props = defineProps<{ workspaceId: number | null; documentCode: string }>()
const document = ref<DocumentDetail | null>(null)
const draft = ref('')
const baseVersionId = ref<number | null>(null)
const dirty = ref(false)
const loading = ref(false)
const saving = ref(false)
const loadError = ref<string | null>(null)
const saveError = ref<string | null>(null)
const conflict = ref(false)
let requestSequence = 0

async function loadDocument(): Promise<void> {
  if (!props.workspaceId || !props.documentCode) return
  const sequence = ++requestSequence
  loading.value = true; loadError.value = null; saveError.value = null; conflict.value = false; document.value = null
  try {
    const result = await documentApi.getDocument(props.workspaceId, props.documentCode)
    if (sequence !== requestSequence) return
    document.value = result; draft.value = result.content ?? ''; baseVersionId.value = result.currentVersionId; dirty.value = false
  } catch (cause) { if (sequence === requestSequence) loadError.value = cause instanceof Error ? cause.message : '文档加载失败' }
  finally { if (sequence === requestSequence) loading.value = false }
}

function handleDraftChange(content: string): void { draft.value = content; dirty.value = content !== (document.value?.content ?? '') }

function confirmDiscard(): boolean {
  return !dirty.value || window.confirm('当前文档有未保存修改，确定要离开吗？')
}

async function saveDocument(): Promise<void> {
  if (!document.value || !props.workspaceId || !baseVersionId.value || !dirty.value || saving.value) return
  saving.value = true; saveError.value = null; conflict.value = false
  try {
    const result = await documentApi.updateDocument(props.workspaceId, props.documentCode, baseVersionId.value, draft.value)
    document.value = result; draft.value = result.content ?? draft.value; baseVersionId.value = result.currentVersionId; dirty.value = false
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 'D409') conflict.value = true
    else saveError.value = cause instanceof Error ? cause.message : '文档保存失败'
  } finally { saving.value = false }
}

async function reloadLatest(): Promise<void> {
  if (!window.confirm('重新加载将丢弃当前未保存修改，是否继续？')) return
  await loadDocument()
}

watch(() => [props.workspaceId, props.documentCode], () => { void loadDocument() }, { immediate: true })

onBeforeRouteLeave(() => confirmDiscard())
onBeforeRouteUpdate((to) => {
  if (to.params.documentCode === props.documentCode) return true
  return confirmDiscard()
})

function handleBeforeUnload(event: BeforeUnloadEvent): void {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => window.addEventListener('beforeunload', handleBeforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', handleBeforeUnload))
</script>

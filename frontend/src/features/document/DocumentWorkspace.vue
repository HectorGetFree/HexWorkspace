<template>
  <section class="document-workspace">
    <div v-if="loading" class="document-state">正在加载文档…</div>
    <div v-else-if="error" class="document-state document-state-error"><p>{{ error }}</p><button type="button" @click="loadDocument">重试</button></div>
    <template v-else-if="document"><DocumentHeader :document="document" /><DocumentContent :content="document.content" /></template>
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import * as documentApi from '../../api/document'
import type { DocumentDetail } from '../../types/document'
import DocumentContent from './DocumentContent.vue'
import DocumentHeader from './DocumentHeader.vue'

const props = defineProps<{ workspaceId: number | null; documentCode: string }>()
const document = ref<DocumentDetail | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
let requestSequence = 0
async function loadDocument(): Promise<void> {
  if (!props.workspaceId || !props.documentCode) { document.value = null; error.value = null; loading.value = false; return }
  const sequence = ++requestSequence
  loading.value = true; error.value = null; document.value = null
  try { const result = await documentApi.getDocument(props.workspaceId, props.documentCode); if (sequence === requestSequence) document.value = result }
  catch (cause) { if (sequence === requestSequence) error.value = cause instanceof Error ? cause.message : '文档加载失败' }
  finally { if (sequence === requestSequence) loading.value = false }
}
watch(() => [props.workspaceId, props.documentCode], () => { void loadDocument() }, { immediate: true })
</script>

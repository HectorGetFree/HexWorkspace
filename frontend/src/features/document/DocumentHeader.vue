<template>
  <header class="document-header">
    <div class="document-header-title"><span class="eyebrow">DOCUMENT</span><h2>{{ document.title || document.fileName }}</h2></div>
    <div class="document-header-actions"><span v-if="dirty" class="document-unsaved">未保存</span><span class="document-version">V{{ document.currentVersionNo }}</span><button type="button" class="document-save" :disabled="!dirty || saving" @click="$emit('save')">{{ saving ? '保存中…' : '保存' }}</button></div>
  </header>
</template>
<script setup lang="ts">
import type { DocumentDetail } from '../../types/document'
defineProps<{ document: DocumentDetail; dirty: boolean; saving: boolean }>()
defineEmits<{ save: [] }>()
</script>

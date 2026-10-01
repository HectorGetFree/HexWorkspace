<template><header class="document-header"><div class="document-header-title"><span class="eyebrow">DOCUMENT</span><h2>{{ document.title || document.fileName }}</h2></div><span class="document-version">V{{ document.currentVersionNo }}</span></header></template>
<script setup lang="ts">
import type { DocumentDetail } from '../../types/document'
defineProps<{ document: DocumentDetail }>()
</script>

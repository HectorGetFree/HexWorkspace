<template><div v-if="!content" class="document-empty-content">此文档暂无内容</div><pre v-else class="document-content">{{ content }}</pre></template>
<script setup lang="ts">
defineProps<{ content: string | null }>()
</script>

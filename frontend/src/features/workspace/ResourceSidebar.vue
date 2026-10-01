<template>
  <aside class="resource-sidebar" :class="{ 'is-collapsed': collapsed }">
    <template v-if="!collapsed">
      <div class="resource-header">
        <div>
          <span class="eyebrow">WORKSPACE</span>
          <h2>我的资料</h2>
        </div>
        <button class="icon-button" type="button" title="收起资料栏" @click="$emit('toggle')">
          <PanelLeftClose :size="17" />
        </button>
      </div>
      <div class="resource-section-label">
        <span>文档</span>
        <button class="icon-button small" type="button" title="新建文档"><Plus :size="15" /></button>
      </div>
      <div class="resource-list">
        <button v-for="document in documents" :key="document" class="resource-item" type="button">
          <FileText :size="16" />
          <span>{{ document }}</span>
        </button>
      </div>
    </template>
    <button v-else class="collapsed-toggle" type="button" title="展开资料栏" @click="$emit('toggle')">
      <PanelLeftOpen :size="18" />
    </button>
  </aside>
</template>

<script setup lang="ts">
import { FileText, PanelLeftClose, PanelLeftOpen, Plus } from 'lucide-vue-next'

defineProps<{ collapsed: boolean }>()
defineEmits<{ toggle: [] }>()

const documents = ['项目文档', '产品方案.md', '技术笔记.md']
</script>

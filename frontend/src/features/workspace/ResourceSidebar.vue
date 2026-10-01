<template>
  <aside class="resource-sidebar" :class="{ 'is-collapsed': collapsed }">
    <template v-if="!collapsed">
      <div class="resource-header"><div><span class="eyebrow">WORKSPACE</span><h2>{{ workspaceName }}</h2></div><button class="icon-button" type="button" title="收起资料栏" @click="$emit('toggle')"><PanelLeftClose :size="17" /></button></div>
      <div class="resource-section-label"><span>资源</span><button class="icon-button small" type="button" title="新建资源"><Plus :size="15" /></button></div>
      <div class="resource-list resource-tree-list">
        <div v-if="loading" class="resource-status">正在加载资源…</div>
        <div v-else-if="error" class="resource-status resource-status-error"><span>{{ error }}</span><button type="button" @click="$emit('retry')">重试</button></div>
        <ResourceTree v-else :nodes="nodes" :expanded-ids="expandedIds" :children-by-folder="childrenByFolder" :child-errors="childErrors" :loading-folder-id="loadingFolderId" :selected-document-code="selectedDocumentCode" @toggle-folder="$emit('toggle-folder', $event)" @retry-folder="$emit('retry-folder', $event)" @select-document="$emit('select-document', $event)" />
      </div>
    </template>
    <button v-else class="collapsed-toggle" type="button" title="展开资料栏" @click="$emit('toggle')"><PanelLeftOpen :size="18" /></button>
  </aside>
</template>

<script setup lang="ts">
import { PanelLeftClose, PanelLeftOpen, Plus } from 'lucide-vue-next'
import ResourceTree from './ResourceTree.vue'
import type { ResourceNode } from '../../types/workspace'

withDefaults(defineProps<{ collapsed: boolean; workspaceName: string; nodes: ResourceNode[]; expandedIds: Set<number>; childrenByFolder: Record<number, ResourceNode[]>; childErrors: Record<number, string>; loading: boolean; loadingFolderId: number | null; error: string | null; selectedDocumentCode?: string }>(), { selectedDocumentCode: undefined })
defineEmits<{ toggle: []; retry: []; 'toggle-folder': [folderId: number]; 'retry-folder': [folderId: number]; 'select-document': [documentCode: string] }>()
</script>

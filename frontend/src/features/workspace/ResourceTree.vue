<template>
  <div class="resource-tree">
    <div v-for="node in nodes" :key="node.id" class="resource-tree-node">
      <button v-if="node.type === 'folder'" class="resource-item folder-item" type="button" @click="$emit('toggle-folder', node.folder!.folderId)">
        <ChevronRight v-if="!expandedIds.has(node.folder!.folderId)" :size="15" />
        <ChevronDown v-else :size="15" />
        <FolderOpen v-if="expandedIds.has(node.folder!.folderId)" :size="16" />
        <Folder v-else :size="16" />
        <span>{{ node.name }}</span>
        <LoadingIcon v-if="loadingFolderId === node.folder!.folderId" class="resource-loading" :size="14" />
      </button>
      <button v-else class="resource-item document-item" :class="{ 'is-selected': node.document!.documentCode === selectedDocumentCode }" type="button" @click="$emit('select-document', node.document!.documentCode)">
        <FileText :size="16" /><span>{{ node.name }}</span>
      </button>
      <div v-if="node.type === 'folder' && expandedIds.has(node.folder!.folderId)" class="resource-children">
        <div v-if="loadingFolderId !== node.folder!.folderId && childErrors[node.folder!.folderId]" class="resource-child-error">
          <span>{{ childErrors[node.folder!.folderId] }}</span><button type="button" @click.stop="$emit('retry-folder', node.folder!.folderId)">重试</button>
        </div>
        <div v-else-if="loadingFolderId !== node.folder!.folderId && childrenByFolder[node.folder!.folderId]?.length === 0" class="resource-empty">空文件夹</div>
        <ResourceTree v-else :nodes="childrenByFolder[node.folder!.folderId] ?? []" :expanded-ids="expandedIds" :children-by-folder="childrenByFolder" :child-errors="childErrors" :loading-folder-id="loadingFolderId" :selected-document-code="selectedDocumentCode" @toggle-folder="$emit('toggle-folder', $event)" @retry-folder="$emit('retry-folder', $event)" @select-document="$emit('select-document', $event)" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ChevronDown, ChevronRight, FileText, Folder, FolderOpen, LoaderCircle as LoadingIcon } from 'lucide-vue-next'
import type { ResourceNode } from '../../types/workspace'

defineProps<{ nodes: ResourceNode[]; expandedIds: Set<number>; childrenByFolder: Record<number, ResourceNode[]>; childErrors: Record<number, string>; loadingFolderId: number | null; selectedDocumentCode?: string }>()
defineEmits<{ 'toggle-folder': [folderId: number]; 'retry-folder': [folderId: number]; 'select-document': [documentCode: string] }>()
</script>

<template>
  <section class="workspace-page">
    <ResourceSidebar :collapsed="resourceSidebarCollapsed" :workspace-name="workspace?.workspaceName ?? '我的资料'" :nodes="rootNodes" :expanded-ids="expandedFolderIds" :children-by-folder="childrenByFolder" :child-errors="childErrors" :loading="loading" :loading-folder-id="loadingFolderId" :error="error" :selected-document-code="selectedDocumentCode" @toggle="resourceSidebarCollapsed = !resourceSidebarCollapsed" @retry="loadWorkspaceTree" @toggle-folder="toggleFolder" @retry-folder="loadFolderChildren" @select-document="selectDocument" />
    <main class="workspace-main">
      <header class="workspace-header">
        <div>
          <span class="eyebrow">MY WORKSPACE</span>
          <h1>{{ route.params.documentCode ? '文档预览' : 'Workspace' }}</h1>
        </div>
        <el-button plain size="small"><Settings2 :size="15" /> 设置</el-button>
      </header>
      <div class="editor-placeholder">
        <div class="placeholder-icon"><FileText :size="21" /></div>
        <h2>{{ route.params.documentCode ? '准备打开文档' : '选择一个文档开始' }}</h2>
        <p>这里将显示文档内容与编辑器。当前阶段仅提供页面骨架。</p>
        <el-button type="primary" plain>新建文档</el-button>
      </div>
    </main>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FileText, Settings2 } from 'lucide-vue-next'
import * as workspaceApi from '../api/workspace'
import * as folderApi from '../api/folder'
import ResourceSidebar from '../features/workspace/ResourceSidebar.vue'
import type { DirectoryResponse, ResourceNode, Workspace } from '../types/workspace'

const route = useRoute()
const router = useRouter()
const resourceSidebarCollapsed = ref(false)
const workspace = ref<Workspace | null>(null)
const rootNodes = ref<ResourceNode[]>([])
const expandedFolderIds = ref(new Set<number>())
const childrenByFolder = ref<Record<number, ResourceNode[]>>({})
const childErrors = ref<Record<number, string>>({})
const loading = ref(true)
const loadingFolderId = ref<number | null>(null)
const error = ref<string | null>(null)
const selectedDocumentCode = computed(() => String(route.params.documentCode ?? ''))

function toNodes(directory: DirectoryResponse): ResourceNode[] {
  return [
    ...directory.folders.map((folder) => ({ type: 'folder' as const, id: `folder-${folder.folderId}`, name: folder.name, folder })),
    ...directory.documents.map((document) => ({ type: 'document' as const, id: `document-${document.documentCode}`, name: document.title || document.fileName || document.documentCode, document })),
  ]
}

async function loadWorkspaceTree(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const workspaces = await workspaceApi.listMyWorkspaces()
    workspace.value = workspaces.find((item) => item.isDefault) ?? workspaces[0] ?? null
    if (!workspace.value) throw new Error('当前用户没有可用 Workspace')
    const root = await folderApi.getRootFolder(workspace.value.workspaceId)
    const directory = await folderApi.getFolderChildren(workspace.value.workspaceId, root.folderId)
    rootNodes.value = toNodes(directory)
    childrenByFolder.value = { [root.folderId]: rootNodes.value }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Workspace 资源加载失败'
  } finally {
    loading.value = false
  }
}

async function loadFolderChildren(folderId: number): Promise<void> {
  if (!workspace.value || loadingFolderId.value === folderId) return
  if (childrenByFolder.value[folderId] && !childErrors.value[folderId]) {
    expandedFolderIds.value = new Set(expandedFolderIds.value).add(folderId)
    return
  }
  loadingFolderId.value = folderId
  childErrors.value = { ...childErrors.value, [folderId]: '' }
  expandedFolderIds.value = new Set(expandedFolderIds.value).add(folderId)
  try {
    const directory = await folderApi.getFolderChildren(workspace.value.workspaceId, folderId)
    childrenByFolder.value = { ...childrenByFolder.value, [folderId]: toNodes(directory) }
    childErrors.value = { ...childErrors.value, [folderId]: '' }
  } catch (cause) {
    childErrors.value = { ...childErrors.value, [folderId]: cause instanceof Error ? cause.message : '文件夹加载失败' }
  } finally {
    loadingFolderId.value = null
  }
}

function toggleFolder(folderId: number): void {
  if (expandedFolderIds.value.has(folderId)) {
    const next = new Set(expandedFolderIds.value)
    next.delete(folderId)
    expandedFolderIds.value = next
  } else void loadFolderChildren(folderId)
}

function selectDocument(documentCode: string): void {
  void router.push(`/workspace/documents/${documentCode}`)
}

onMounted(() => { void loadWorkspaceTree() })
</script>

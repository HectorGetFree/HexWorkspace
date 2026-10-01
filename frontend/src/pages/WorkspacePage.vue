<template>
  <section class="workspace-page">
    <ResourceSidebar :collapsed="resourceSidebarCollapsed" @toggle="resourceSidebarCollapsed = !resourceSidebarCollapsed" />
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
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { FileText, Settings2 } from 'lucide-vue-next'
import ResourceSidebar from '../features/workspace/ResourceSidebar.vue'

const route = useRoute()
const resourceSidebarCollapsed = ref(false)
</script>

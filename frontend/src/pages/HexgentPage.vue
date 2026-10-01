<template>
  <section class="page-shell hexgent-page">
    <div class="page-topline">
      <span class="status-dot" /> <span>Hexgent Workspace</span>
    </div>
    <div class="agent-hero">
      <div class="hero-icon"><Sparkles :size="23" /></div>
      <h1>你好，今天想完成什么？</h1>
      <p>让 Hexgent 帮你整理资料、生成内容或探索 Workspace。</p>
    </div>
    <div class="prompt-card">
      <el-input type="textarea" :rows="4" placeholder="告诉 Hexgent 你想做什么……" resize="none" />
      <div class="prompt-toolbar">
        <div class="prompt-options">
          <el-button plain size="small"><FolderKanban :size="14" /> Workspace</el-button>
          <el-button plain size="small"><ShieldCheck :size="14" /> 默认权限</el-button>
        </div>
        <el-button type="primary" circle title="提交"><ArrowUp :size="17" /></el-button>
      </div>
    </div>
    <div class="quick-actions">
      <span class="eyebrow">QUICK ACTIONS</span>
      <div class="quick-grid">
        <button v-for="action in actions" :key="action.label" class="quick-card" type="button">
          <component :is="action.icon" :size="18" />
          <span>{{ action.label }}</span>
        </button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ArrowUp, FilePlus2, FolderKanban, Search, ShieldCheck, Sparkles, WandSparkles } from 'lucide-vue-next'

const actions = [
  { label: '总结文档', icon: FilePlus2 },
  { label: '生成新文档', icon: WandSparkles },
  { label: '问答与检索', icon: Search },
]
</script>

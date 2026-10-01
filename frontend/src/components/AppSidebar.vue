<template>
  <aside class="app-sidebar">
    <div class="brand">
      <div class="brand-mark"><Sparkles :size="17" /></div>
      <span>HexWorkspace</span>
    </div>

    <nav class="primary-nav" aria-label="主导航">
      <RouterLink class="nav-item" to="/hexgent">
        <Bot :size="17" />
        <span>Hexgent</span>
      </RouterLink>
      <RouterLink class="nav-item" to="/workspace">
        <FolderKanban :size="17" />
        <span>Workspace</span>
      </RouterLink>
    </nav>

    <section class="conversation-history" aria-label="会话历史">
      <button class="history-toggle" type="button" @click="historyExpanded = !historyExpanded">
        <ChevronDown v-if="historyExpanded" :size="15" />
        <ChevronRight v-else :size="15" />
        <MessageSquareText :size="16" />
        <span>会话历史</span>
      </button>
      <div v-if="historyExpanded" class="history-list">
        <span class="history-date">今天</span>
        <button v-for="conversation in todayConversations" :key="conversation" class="history-item" type="button">
          {{ conversation }}
        </button>
        <span class="history-date">昨天</span>
        <button v-for="conversation in yesterdayConversations" :key="conversation" class="history-item" type="button">
          {{ conversation }}
        </button>
      </div>
    </section>

    <div class="sidebar-footer">
      <div class="user-placeholder">
        <div class="avatar">H</div>
        <div>
          <strong>{{ auth.currentUser?.username ?? '个人账户' }}</strong>
          <span>{{ auth.currentUser?.status ?? 'Workspace MVP' }}</span>
        </div>
        <button class="profile-menu-button" type="button" title="退出登录" @click="handleLogout">
          <LogOut :size="16" />
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bot, ChevronDown, ChevronRight, FolderKanban, LogOut, MessageSquareText, Sparkles } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function handleLogout(): Promise<void> {
  try {
    await auth.logout()
  } finally {
    await router.replace('/login')
  }
}

const historyExpanded = ref(true)
const todayConversations = ['示例会话 A', '示例会话 B']
const yesterdayConversations = ['示例会话 C']
</script>

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
        <button class="history-new-conversation" type="button" @click="startNewConversation">
          <Plus :size="14" />
          <span>新对话</span>
        </button>
        <span v-if="agentConversation.history.length" class="history-date">最近会话</span>
        <button
          v-for="conversation in agentConversation.history"
          :key="conversation.conversationId"
          class="history-item"
          :class="{ 'is-current': conversation.conversationId === agentConversation.currentConversationId }"
          type="button"
          @click="openConversation(conversation.conversationId)"
        >
          <span>{{ agentConversation.displayLabel(conversation) }}</span>
        </button>
      </div>
    </section>

    <div class="sidebar-footer">
      <el-popover placement="top-start" :width="240" trigger="click" popper-class="account-menu-popper">
        <template #reference>
          <button class="user-profile-trigger" type="button" aria-label="打开账户菜单">
            <div class="avatar">{{ userInitial }}</div>
            <div class="user-profile-copy">
              <strong>{{ auth.currentUser?.username ?? '个人账户' }}</strong>
            </div>
            <ChevronUp :size="15" />
          </button>
        </template>
        <div class="account-menu">
          <div class="account-menu-user">
            <strong>{{ auth.currentUser?.username ?? '个人账户' }}</strong>
          </div>
          <div class="account-menu-divider" />
          <button type="button" @click="settingsVisible = true">
            <Settings :size="16" />
            <span>设置</span>
          </button>
          <button class="is-danger" type="button" @click="handleLogout">
            <LogOut :size="16" />
            <span>退出登录</span>
          </button>
        </div>
      </el-popover>
    </div>

    <el-dialog v-model="settingsVisible" title="设置" width="360px" append-to-body>
      <p class="settings-placeholder">设置功能将在后续版本提供。</p>
    </el-dialog>
  </aside>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bot,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  FolderKanban,
  LogOut,
  MessageSquareText,
  Plus,
  Settings,
  Sparkles,
} from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { useAgentConversationStore } from '../stores/agentConversation'

const auth = useAuthStore()
const router = useRouter()
const agentConversation = useAgentConversationStore()
const historyExpanded = ref(true)
const settingsVisible = ref(false)
const userInitial = computed(() => auth.currentUser?.username?.slice(0, 1).toUpperCase() ?? 'U')

onMounted(() => {
  if (auth.currentUser) void agentConversation.refreshHistory()
})

function startNewConversation(): void {
  agentConversation.selectConversation(null)
  void router.push('/hexgent')
}

function openConversation(conversationId: string): void {
  agentConversation.selectConversation(conversationId)
  void router.push(`/hexgent/conversations/${conversationId}`)
}

async function handleLogout(): Promise<void> {
  try {
    await auth.logout()
  } finally {
    agentConversation.clear()
    await router.replace('/login')
  }
}
</script>

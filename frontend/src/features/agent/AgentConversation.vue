<template>
  <section class="agent-conversation" :class="`agent-conversation--${variant}`">
    <header v-if="variant === 'panel'" class="agent-conversation-header">
      <div>
        <span class="eyebrow">HEXGENT</span>
        <h2>Agent</h2>
      </div>
      <div class="agent-header-actions">
        <span v-if="state?.runStatus" class="agent-status">{{ state.runStatus }}</span>
        <button class="agent-collapse-button" type="button" @click="emit('collapse')">收起</button>
      </div>
    </header>

    <div class="agent-content" :class="{ 'is-empty-page': variant === 'page' && !state?.messages.length }">
      <div v-if="variant === 'page' && !state?.messages.length" class="agent-welcome">
        <div class="hero-icon"><Sparkles :size="23" /></div>
        <h1>你好，今天想完成什么？</h1>
        <p>让 Hexgent 帮你整理资料、生成内容或探索 Workspace。</p>
      </div>

      <div
        v-show="variant === 'panel' || state?.messages.length"
        ref="messagesElement"
        class="agent-messages"
        @scroll="updateFollowOutput"
      >
        <div v-if="variant === 'panel' && !state?.messages.length" class="agent-empty">
          向 Agent 描述你想完成的任务。
        </div>
        <article
          v-for="message in state?.messages ?? []"
          :key="message.id"
          class="agent-message"
          :class="[`is-${message.role}`, { 'is-failed': message.delivery === 'failed' }]"
        >
          <span v-if="variant === 'panel'" class="agent-message-role">
            {{ message.role === 'user' ? '你' : 'Agent' }}
          </span>
          <div>{{ message.content }}</div>
        </article>
        <div v-if="state?.toolActivities.length" class="agent-tool-activity">
          <span>工具活动</span>
          <div v-for="tool in state.toolActivities" :key="tool.callId" class="agent-tool">
            {{ tool.toolName }} · {{ tool.status }}
          </div>
        </div>
      </div>

      <div v-if="state?.streamError || submitError" class="agent-error">
        {{ state?.streamError || submitError }}
      </div>
      <div v-if="state?.waitingForFirstDelta && !state.pendingPermission" class="agent-waiting" aria-live="polite">
        <LoaderCircle :size="17" />
        <span>正在处理…</span>
      </div>
      <div v-if="state?.pendingPermission" class="agent-permission">
        <strong>等待你的确认</strong>
        <p>{{ state.pendingPermission.description || `工具 ${state.pendingPermission.toolName || ''} 请求操作` }}</p>
        <div>
          <button type="button" :disabled="state.permissionSubmitting" @click="resolvePermission('allow')">允许</button>
          <button type="button" class="deny" :disabled="state.permissionSubmitting" @click="resolvePermission('deny')">拒绝</button>
        </div>
      </div>

      <div v-if="showRunControls" class="agent-controls">
        <span class="agent-controls-status">{{ controlsLabel }}</span>
        <div class="agent-controls-actions">
          <button v-if="state?.runStatus === 'RUNNING'" type="button" :disabled="Boolean(state.controlPending)" @click="command('pause')">暂停</button>
          <button v-if="state?.runStatus === 'PENDING' || state?.runStatus === 'RUNNING' || state?.runStatus === 'PAUSED' || state?.runStatus === 'INCOMPLETE'" type="button" :disabled="Boolean(state.controlPending)" @click="command('cancel')">取消</button>
          <button v-if="state?.runStatus === 'PAUSED'" type="button" :disabled="Boolean(state.controlPending)" @click="command('resume')">继续</button>
          <button v-if="state?.runStatus === 'INCOMPLETE'" type="button" :disabled="Boolean(state.controlPending)" @click="command('continue')">继续</button>
        </div>
      </div>

      <div class="agent-composer">
        <textarea
          ref="composerElement"
          v-model="instruction"
          :disabled="false"
          :placeholder="state?.runStatus === 'RUNNING' ? '补充指令…' : '输入任务…'"
          rows="1"
          @input="resizeComposer"
          @keydown="handleComposerKeydown"
        />
        <div class="agent-composer-actions">
          <el-popover v-if="variant === 'page'" placement="top-start" :width="220" trigger="click">
            <template #reference>
              <button class="agent-composer-action" type="button" title="当前 Workspace">
                <FolderKanban :size="14" />
                <span>{{ workspaceName || '当前 Workspace' }}</span>
                <ChevronDown :size="13" />
              </button>
            </template>
            <div class="agent-workspace-scope">
              <strong>当前 Workspace</strong>
              <p>{{ workspaceName || '未加载 Workspace' }}</p>
              <span>V1 暂不支持切换其他 Workspace。</span>
            </div>
          </el-popover>
          <el-popover v-if="variant === 'page'" placement="top-start" :width="280" trigger="click">
            <template #reference>
              <button class="agent-composer-action" type="button">
                <ShieldCheck :size="14" />
                <span>权限设置</span>
              </button>
            </template>
            <div class="agent-permission-scope">
              <strong>权限范围（只读）</strong>
              <p>当前 StartRun 合同未配置 Workspace capability scope。</p>
              <span>后续 Run 的权限策略将由 Workspace 授权边界决定。</span>
            </div>
          </el-popover>
          <button
            class="agent-send-button"
            type="button"
            :disabled="!canSubmit"
            title="发送"
            @click="send"
          >
            <ArrowUp :size="17" />
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowUp, ChevronDown, FolderKanban, LoaderCircle, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { useAgentConversationStore } from '../../stores/agentConversation'

const props = withDefaults(defineProps<{
  conversationId?: string | null
  workspaceId: number | null
  workspaceName?: string
  variant?: 'page' | 'panel'
}>(), {
  conversationId: null,
  workspaceName: '',
  variant: 'page',
})
const emit = defineEmits<{ collapse: []; 'conversation-created': [conversationId: string] }>()
const agentConversation = useAgentConversationStore()
const instruction = ref('')
const messagesElement = ref<HTMLElement | null>(null)
const composerElement = ref<HTMLTextAreaElement | null>(null)
const followOutput = ref(true)
const submitError = ref('')
const resolvedConversationId = computed(() => (
  props.variant === 'page'
    ? props.conversationId
    : props.conversationId ?? agentConversation.currentConversationId
))
const state = computed(() => agentConversation.conversationOf(resolvedConversationId.value))
const workspaceName = computed(() => state.value?.workspaceName || props.workspaceName)
const nonTerminalStatuses = new Set(['PENDING', 'RUNNING', 'PAUSED', 'INCOMPLETE'])
const showRunControls = computed(() => Boolean(state.value?.activeRunId && nonTerminalStatuses.has(state.value.runStatus)))
const controlsLabel = computed(() => state.value?.runStatus === 'PAUSED'
  ? '已暂停'
  : state.value?.runStatus === 'INCOMPLETE'
    ? '等待继续'
    : state.value?.runStatus === 'PENDING'
      ? '正在启动'
      : state.value?.controlPending === 'pause'
        ? '正在暂停'
        : state.value?.controlPending === 'cancel'
          ? '正在取消'
          : state.value?.controlPending === 'resume' || state.value?.controlPending === 'continue'
            ? '正在继续'
            : '正在执行')
const canStartRun = computed(() => {
  const status = state.value?.runStatus
  return !status || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status)
})
const canSteer = computed(() => state.value?.runStatus === 'RUNNING')
const canSubmit = computed(() => {
  if (!instruction.value.trim()) return false
  return canStartRun.value || canSteer.value
})
const messageRevision = computed(() => (state.value?.messages ?? [])
  .map((message) => `${message.id}:${message.content.length}`)
  .join('|'))

watch(() => props.conversationId, (conversationId) => {
  agentConversation.selectConversation(conversationId)
}, { immediate: true })

watch(messageRevision, () => {
  void nextTick(scrollToLatest)
})

onMounted(() => window.addEventListener('keydown', handleGlobalKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleGlobalKeydown))

function resizeComposer(): void {
  const element = composerElement.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 160)}px`
}

function handleComposerKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void send()
}

function handleGlobalKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || event.repeat || event.isComposing || event.defaultPrevented) return
  if (state.value?.runStatus !== 'RUNNING' || state.value.controlPending || state.value.pendingPermission) return
  const hasVisibleOverlay = [...document.querySelectorAll<HTMLElement>('.el-overlay, .el-popper')]
    .some((element) => element.offsetParent !== null && element.getAttribute('aria-hidden') !== 'true')
  if (hasVisibleOverlay) return
  event.preventDefault()
  void command('pause')
}

function updateFollowOutput(): void {
  const element = messagesElement.value
  if (!element) return
  followOutput.value = element.scrollHeight - element.scrollTop - element.clientHeight < 80
}

function scrollToLatest(): void {
  const element = messagesElement.value
  if (!element || !followOutput.value) return
  element.scrollTop = element.scrollHeight
}

async function send(): Promise<void> {
  if (!props.workspaceId || !instruction.value.trim() || !canSubmit.value) return
  const text = instruction.value.trim()
  submitError.value = ''
  instruction.value = ''
  await nextTick(resizeComposer)
  if (state.value?.runStatus === 'RUNNING' && resolvedConversationId.value) {
    await agentConversation.steer(resolvedConversationId.value, text)
    return
  }
  try {
    const conversationId = await agentConversation.startRun({
      conversationId: resolvedConversationId.value,
      workspaceId: props.workspaceId,
      workspaceName: props.workspaceName,
      instruction: text,
    })
    emit('conversation-created', conversationId)
  } catch (cause) {
    instruction.value = text
    submitError.value = cause instanceof Error ? cause.message : '创建会话失败'
    await nextTick(resizeComposer)
  }
}

async function resolvePermission(decision: 'allow' | 'deny'): Promise<void> {
  if (!resolvedConversationId.value) return
  await agentConversation.resolvePermission(resolvedConversationId.value, decision)
}

async function command(commandName: string): Promise<void> {
  if (!resolvedConversationId.value) return
  await agentConversation.command(resolvedConversationId.value, commandName)
}
</script>

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as agentApi from '../api/agent'
import type { Conversation, ConversationDetail, StreamEvent, ToolActivity } from '../types/agent'

export type AgentMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  delivery?: 'sent' | 'failed'
}

export type PendingPermission = {
  requestId: string
  responseId?: string
  toolName?: string
  description?: string
}

export type AgentConversationState = {
  conversationId: string
  workspaceId: number
  workspaceName: string
  title: string
  messages: AgentMessage[]
  activeRunId: string | null
  runStatus: string
  toolActivities: ToolActivity[]
  pendingPermission: PendingPermission | null
  permissionSubmitting: boolean
  controlPending: string | null
  waitingForFirstDelta: boolean
  streamError: string
  busy: boolean
  hydrating: boolean
  createdAt: number
  updatedAt: number
}

type StartOptions = {
  conversationId: string | null
  workspaceId: number
  workspaceName: string
  instruction: string
}

export const useAgentConversationStore = defineStore('agentConversation', () => {
  const conversations = ref<Record<string, AgentConversationState>>({})
  const currentConversationId = ref<string | null>(null)
  const controllers = new Map<string, AbortController>()
  const appliedEventIds = new Map<string, Set<string>>()

  const currentConversation = computed(() => (
    currentConversationId.value ? conversations.value[currentConversationId.value] ?? null : null
  ))
  const history = computed(() => Object.values(conversations.value)
    .sort((left, right) => right.updatedAt - left.updatedAt))

  function selectConversation(conversationId: string | null): void {
    currentConversationId.value = conversationId && conversations.value[conversationId] ? conversationId : null
  }

  function conversationOf(conversationId: string | null): AgentConversationState | null {
    return conversationId ? conversations.value[conversationId] ?? null : null
  }

  function updateConversation(conversationId: string, patch: Partial<AgentConversationState>): void {
    const current = conversations.value[conversationId]
    if (!current) return
    conversations.value = {
      ...conversations.value,
      [conversationId]: { ...current, ...patch, updatedAt: patch.updatedAt ?? Date.now() },
    }
  }

  function createState(
    conversationId: string,
    workspaceId: number,
    workspaceName: string,
    select = true,
    title = '新对话',
  ): AgentConversationState {
    const now = Date.now()
    const state: AgentConversationState = {
      conversationId,
      workspaceId,
      workspaceName,
      title,
      messages: [],
      activeRunId: null,
      runStatus: '',
      toolActivities: [],
      pendingPermission: null,
      permissionSubmitting: false,
      controlPending: null,
      waitingForFirstDelta: false,
      streamError: '',
      busy: false,
      hydrating: false,
      createdAt: now,
      updatedAt: now,
    }
    conversations.value = { ...conversations.value, [conversationId]: state }
    if (select) currentConversationId.value = conversationId
    return state
  }

  function ensureState(conversation: Conversation, workspaceName = ''): AgentConversationState {
    const existing = conversationOf(conversation.conversationId)
    if (existing) {
      updateConversation(conversation.conversationId, {
        workspaceId: conversation.workspaceId,
        workspaceName: existing.workspaceName || workspaceName,
        title: conversation.title || existing.title,
        runStatus: existing.runStatus || conversation.latestRunStatus || '',
      })
      return conversationOf(conversation.conversationId) as AgentConversationState
    }
    const created = createState(
      conversation.conversationId,
      conversation.workspaceId,
      workspaceName,
      false,
      conversation.title,
    )
    updateConversation(conversation.conversationId, {
      runStatus: conversation.latestRunStatus || '',
      updatedAt: conversation.updatedAt ? Date.parse(conversation.updatedAt) : Date.now(),
    })
    return conversationOf(created.conversationId) as AgentConversationState
  }

  async function refreshHistory(): Promise<void> {
    const historyItems = await agentApi.listConversations()
    historyItems.forEach((conversation) => ensureState(conversation))
  }

  async function hydrateConversation(conversationId: string, workspaceName = ''): Promise<void> {
    const existing = conversationOf(conversationId)
    if (existing?.hydrating) return
    if (existing) updateConversation(conversationId, { hydrating: true })
    try {
      const detail: ConversationDetail = await agentApi.getConversation(conversationId)
      const latestRun = detail.runs.length > 0 ? detail.runs[detail.runs.length - 1] : undefined
      const state = ensureState(detail, workspaceName)
      updateConversation(conversationId, {
        workspaceId: detail.workspaceId,
        workspaceName: state.workspaceName || workspaceName,
        messages: detail.messages.map((message) => ({
          id: message.id,
          role: message.role,
          content: message.content,
          delivery: 'sent',
        })),
        activeRunId: latestRun?.runId ?? null,
        runStatus: latestRun?.status ?? '',
        waitingForFirstDelta: false,
        streamError: '',
        busy: false,
        hydrating: false,
        updatedAt: detail.updatedAt ? Date.parse(detail.updatedAt) : Date.now(),
      })
      currentConversationId.value = conversationId
      if (latestRun && ['PENDING', 'RUNNING', 'PAUSED', 'INCOMPLETE'].includes(latestRun.status)) {
        void connectStream(conversationId, latestRun.runId)
      }
    } catch (cause) {
      if (conversationOf(conversationId)) {
        updateConversation(conversationId, {
          hydrating: false,
          streamError: cause instanceof Error ? cause.message : '会话加载失败',
        })
      }
      throw cause
    }
  }

  async function startRun(options: StartOptions): Promise<string> {
    let conversationId = options.conversationId
    let state = conversationOf(conversationId)

    if (!conversationId || !state) {
      const conversation = await agentApi.createConversation(options.workspaceId)
      conversationId = conversation.conversationId
      state = createState(
        conversationId,
        options.workspaceId,
        options.workspaceName,
        true,
        options.instruction,
      )
    }

    if (state.busy) return conversationId
    if (state.activeRunId) appliedEventIds.delete(state.activeRunId)
    updateConversation(conversationId, {
      messages: [...state.messages, { id: `user-${Date.now()}`, role: 'user', content: options.instruction, delivery: 'sent' }],
      waitingForFirstDelta: true,
      toolActivities: [],
      pendingPermission: null,
      controlPending: null,
      streamError: '',
      busy: true,
    })

    try {
      const run = await agentApi.startRun(conversationId, options.workspaceId, options.instruction)
      updateConversation(conversationId, {
        activeRunId: run.runId,
        runStatus: run.status,
      })
      void connectStream(conversationId, run.runId)
    } catch (cause) {
      updateConversation(conversationId, {
        waitingForFirstDelta: false,
        busy: false,
        streamError: cause instanceof Error ? cause.message : 'Agent 请求失败',
      })
    }
    return conversationId
  }

  function appendTextDelta(conversationId: string, runId: string, event: StreamEvent): void {
    const state = conversationOf(conversationId)
    const delta = String(event.payload.delta ?? '')
    if (!state || !delta) return
    const responseId = String(event.payload.responseId ?? 'default')
    const messageId = `assistant-${runId}-${responseId}`
    const existing = state.messages.find((message) => message.id === messageId)
    updateConversation(conversationId, {
      waitingForFirstDelta: false,
      messages: existing
        ? state.messages.map((message) => (message.id === messageId
          ? { ...message, content: message.content + delta }
          : message))
        : [...state.messages, { id: messageId, role: 'assistant', content: delta }],
    })
  }

  function handleStreamEvent(
    conversationId: string,
    runId: string,
    event: StreamEvent,
  ): void {
    const state = conversationOf(conversationId)
    if (!state || state.activeRunId !== runId) return
    if (event.eventId) {
      const applied = appliedEventIds.get(runId) ?? new Set<string>()
      if (applied.has(event.eventId)) return
      applied.add(event.eventId)
      appliedEventIds.set(runId, applied)
    }
    if (event.type === 'text_delta') {
      appendTextDelta(conversationId, runId, event)
      return
    }
    if (event.type === 'tool_started') {
      updateConversation(conversationId, {
        toolActivities: [...state.toolActivities, {
          callId: String(event.payload.callId ?? ''),
          toolName: String(event.payload.toolName ?? ''),
          status: '运行中',
        }],
      })
      return
    }
    if (event.type === 'tool_finished') {
      const callId = String(event.payload.callId ?? '')
      updateConversation(conversationId, {
        toolActivities: state.toolActivities.map((tool) => (
          tool.callId === callId ? { ...tool, status: String(event.payload.status ?? '完成') } : tool
        )),
      })
      return
    }
    if (event.type === 'lifecycle') {
      const status = String(event.payload.status ?? event.payload.type ?? '')
      if (['RUNNING', 'PAUSED', 'INCOMPLETE', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status)) {
        const terminal = ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status)
        updateConversation(conversationId, {
          runStatus: status,
          waitingForFirstDelta: terminal ? false : state.waitingForFirstDelta,
          busy: terminal ? false : state.busy,
          pendingPermission: terminal ? null : state.pendingPermission,
          controlPending: terminal ? null : state.controlPending,
        })
      }
      return
    }
    if (event.type === 'permission_requested') {
      // 仅接受 WS durable callback 投影的 camelCase requestId；
      // Hexgent 原始 runtime SSE 的 request_id 不能绕过 WS 收据状态机直接驱动裁定 UI。
      const requestId = String(event.payload.requestId ?? '')
      if (!requestId) return
      updateConversation(conversationId, {
        waitingForFirstDelta: false,
        pendingPermission: {
          requestId,
          responseId: String(event.payload.responseId ?? `permission-${Date.now()}`),
          toolName: String(event.payload.toolName ?? ''),
          description: String(event.payload.description ?? event.payload.reason ?? ''),
        },
      })
    }
  }

  async function connectStream(conversationId: string, runId: string): Promise<void> {
    controllers.get(conversationId)?.abort()
    const controller = new AbortController()
    controllers.set(conversationId, controller)
    try {
      await agentApi.streamRun(runId, (event) => handleStreamEvent(conversationId, runId, event), controller.signal)
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        const state = conversationOf(conversationId)
        if (state?.activeRunId === runId) {
          updateConversation(conversationId, {
            waitingForFirstDelta: false,
            streamError: cause instanceof Error ? cause.message : '实时连接失败',
          })
        }
      }
    } finally {
      if (controllers.get(conversationId) === controller) controllers.delete(conversationId)
      const state = conversationOf(conversationId)
      if (state?.activeRunId === runId) {
        updateConversation(conversationId, {
          waitingForFirstDelta: false,
          busy: false,
        })
      }
    }
  }

  async function resolvePermission(conversationId: string, decision: 'allow' | 'deny'): Promise<void> {
    const state = conversationOf(conversationId)
    if (!state?.activeRunId || !state.pendingPermission || state.permissionSubmitting) return
    updateConversation(conversationId, { permissionSubmitting: true })
    try {
      await agentApi.resolvePermission(
        state.activeRunId,
        state.pendingPermission.requestId,
        state.pendingPermission.responseId ?? `permission-${Date.now()}`,
        decision,
      )
      updateConversation(conversationId, { pendingPermission: null, permissionSubmitting: false })
    } catch (cause) {
      updateConversation(conversationId, {
        permissionSubmitting: false,
        streamError: cause instanceof Error ? cause.message : '权限响应失败',
      })
    }
  }

  async function steer(conversationId: string, instruction: string): Promise<void> {
    const state = conversationOf(conversationId)
    if (!state?.activeRunId || state.runStatus !== 'RUNNING') return
    const messageId = `steer-${Date.now()}`
    updateConversation(conversationId, {
      messages: [...state.messages, { id: messageId, role: 'user', content: instruction, delivery: 'sent' }],
      streamError: '',
    })
    try {
      await agentApi.controlRun(conversationId, state.activeRunId, 'steer', instruction)
    } catch (cause) {
      const latest = conversationOf(conversationId)
      if (!latest) return
      updateConversation(conversationId, {
        messages: latest.messages.map((message) => (
          message.id === messageId ? { ...message, delivery: 'failed' } : message
        )),
        streamError: cause instanceof Error ? cause.message : '补充指令发送失败',
      })
    }
  }

  async function command(conversationId: string, commandName: string): Promise<void> {
    const state = conversationOf(conversationId)
    if (!state?.activeRunId || state.controlPending) return
    updateConversation(conversationId, { controlPending: commandName, streamError: '' })
    try {
      await agentApi.controlRun(conversationId, state.activeRunId, commandName)
      updateConversation(conversationId, { controlPending: null })
      if (commandName === 'pause') updateConversation(conversationId, { waitingForFirstDelta: false })
    } catch (cause) {
      updateConversation(conversationId, {
        controlPending: null,
        streamError: cause instanceof Error ? cause.message : '命令发送失败',
      })
    }
  }

  function displayLabel(state: AgentConversationState): string {
    const firstInstruction = state.messages.find((message) => message.role === 'user')?.content.trim()
    const label = firstInstruction || state.title || '新对话'
    return label.length > 24 ? `${label.slice(0, 24)}…` : label
  }

  function clear(): void {
    controllers.forEach((controller) => controller.abort())
    controllers.clear()
    appliedEventIds.clear()
    conversations.value = {}
    currentConversationId.value = null
  }

  return {
    conversations,
    currentConversationId,
    currentConversation,
    history,
    selectConversation,
    conversationOf,
    startRun,
    refreshHistory,
    hydrateConversation,
    steer,
    resolvePermission,
    command,
    displayLabel,
    clear,
  }
})

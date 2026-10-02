export interface Conversation {
  conversationId: string
  workspaceId: number
  title: string
  updatedAt?: string
  latestRunStatus?: string | null
}
export interface AgentRun {
  runId: string
  conversationId: string
  workspaceId: number
  status: string
  instruction: string
  failureCode?: string | null
  failureMessage?: string | null
  createdAt?: string | null
  finishedAt?: string | null
}
export interface StreamEvent {
  eventId?: string
  integrationRunId: string
  streamId: string
  sequence: number
  type: string
  createdAt: string
  payload: Record<string, unknown>
}
export interface ToolActivity { callId?: string; toolName?: string; status?: string }
export interface ConversationMessage { id: string; role: 'user' | 'assistant'; content: string; createdAt: string }
export interface ConversationDetail extends Conversation {
  createdAt: string
  runs: AgentRun[]
  messages: ConversationMessage[]
}

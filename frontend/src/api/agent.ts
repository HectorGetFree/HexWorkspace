import client, { authenticatedFetch } from './client'
import { ApiError } from './errors'
import type { AgentRun, Conversation, ConversationDetail, StreamEvent } from '../types/agent'
import type { ApiEnvelope } from '../types/auth'

const WORKSPACE_CAPABILITIES = [
  'list_folder',
  'search_workspace',
  'get_document',
  'create_document',
  'update_document',
] as const

function requireData<T>(response: { status: number; data: ApiEnvelope<T> }): T {
  if (response.data.code !== '0000' || !response.data.data) {
    throw new ApiError(response.status, response.data.code, response.data.info || 'Agent 请求失败')
  }
  return response.data.data
}

export async function createConversation(workspaceId: number): Promise<Conversation> {
  const response = await client.post<ApiEnvelope<Conversation>>(
    `/api/v1/workspaces/${workspaceId}/conversations`,
    { data: { title: 'Workspace Agent' } },
  )
  return requireData(response)
}

export async function listConversations(): Promise<Conversation[]> {
  const response = await client.get<ApiEnvelope<Conversation[]>>('/api/v1/conversations')
  return requireData(response)
}

export async function getConversation(conversationId: string): Promise<ConversationDetail> {
  const response = await client.get<ApiEnvelope<ConversationDetail>>(
    `/api/v1/conversations/${encodeURIComponent(conversationId)}`,
  )
  return requireData(response)
}

export async function startRun(
  conversationId: string,
  workspaceId: number,
  instruction: string,
): Promise<AgentRun> {
  const response = await client.post<ApiEnvelope<AgentRun>>(
    `/api/v1/conversations/${conversationId}/runs`,
    {
      data: {
        instruction,
        resourceScope: JSON.stringify({ workspaceId }),
        allowedTools: JSON.stringify(WORKSPACE_CAPABILITIES),
      },
    },
  )
  return requireData(response)
}

export async function controlRun(
  conversationId: string,
  runId: string,
  command: string,
  instruction = '',
): Promise<AgentRun> {
  const response = await client.post<ApiEnvelope<AgentRun>>(
    `/api/v1/conversations/${conversationId}/runs/${runId}/${command}`,
    { data: { instruction } },
  )
  return requireData(response)
}

export async function resolvePermission(
  runId: string,
  requestId: string,
  responseId: string,
  decision: 'allow' | 'deny',
): Promise<void> {
  await client.post(`/api/agent-runs/${runId}/permissions/${requestId}`, { responseId, decision })
}

function readFrame(buffer: string): { frame: string; remainder: string } | null {
  const separator = /\r?\n\r?\n/.exec(buffer)
  if (!separator || separator.index === undefined) return null
  return {
    frame: buffer.slice(0, separator.index),
    remainder: buffer.slice(separator.index + separator[0].length),
  }
}

function parseFrame(frame: string): StreamEvent | null {
  let eventType = ''
  const dataLines: string[] = []

  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) eventType = line.slice('event:'.length).trim()
    if (line.startsWith('data:')) {
      const value = line.slice('data:'.length)
      dataLines.push(value.startsWith(' ') ? value.slice(1) : value)
    }
  }

  if (dataLines.length === 0) return null
  const parsed = JSON.parse(dataLines.join('\n')) as Partial<StreamEvent>
  if (!parsed.type && !eventType) return null
  return {
    eventId: parsed.eventId,
    integrationRunId: parsed.integrationRunId ?? '',
    streamId: parsed.streamId ?? '',
    sequence: parsed.sequence ?? 0,
    type: parsed.type ?? eventType,
    createdAt: parsed.createdAt ?? '',
    payload: parsed.payload ?? {},
  }
}

export async function streamRun(
  runId: string,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await authenticatedFetch(`/api/agent-runs/${encodeURIComponent(runId)}/events`, {
    headers: {
      Accept: 'text/event-stream',
    },
    signal,
  })
  if (!response.ok || !response.body) {
    throw new ApiError(response.status, 'STREAM_ERROR', 'Agent 实时连接失败')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const consumeFrames = (): void => {
    let parsedFrame = readFrame(buffer)
    while (parsedFrame) {
      buffer = parsedFrame.remainder
      const event = parseFrame(parsedFrame.frame)
      if (event) onEvent(event)
      parsedFrame = readFrame(buffer)
    }
  }

  while (true) {
    const chunk = await reader.read()
    if (chunk.done) break
    buffer += decoder.decode(chunk.value, { stream: true })
    consumeFrames()
  }

  buffer += decoder.decode()
  consumeFrames()
}

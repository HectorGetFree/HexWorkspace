import client from './client'
import { ApiError } from './errors'
import type { Workspace, WorkspaceListResponse } from '../types/workspace'

export async function listMyWorkspaces(): Promise<Workspace[]> {
  const response = await client.get<WorkspaceListResponse>('/api/v1/workspaces/my')
  if (response.data.code !== '0000' || !response.data.data) throw new ApiError(response.status, response.data.code, response.data.info || 'Workspace 加载失败')
  return response.data.data.workspaces ?? []
}

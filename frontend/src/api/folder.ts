import client from './client'
import { ApiError } from './errors'
import type { DirectoryApiResponse, DirectoryResponse, Folder, FolderResponse } from '../types/workspace'

function requireData<T extends Folder | DirectoryResponse>(response: { status: number; data: { code: string; info: string; data: T | null } }): T {
  if (response.data.code !== '0000' || !response.data.data) throw new ApiError(response.status, response.data.code, response.data.info || '目录加载失败')
  return response.data.data
}
export async function getRootFolder(workspaceId: number): Promise<Folder> {
  const response = await client.get<FolderResponse>(`/api/v1/workspaces/${workspaceId}/folders/root`)
  return requireData(response)
}
export async function getFolderChildren(workspaceId: number, folderId: number): Promise<DirectoryResponse> {
  const response = await client.get<DirectoryApiResponse>(`/api/v1/workspaces/${workspaceId}/folders/${folderId}/children`)
  return requireData(response)
}

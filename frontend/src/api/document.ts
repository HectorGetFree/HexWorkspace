import client from './client'
import { ApiError } from './errors'
import type { DocumentDetail, DocumentResponse } from '../types/document'

export async function getDocument(workspaceId: number, documentCode: string): Promise<DocumentDetail> {
  const response = await client.get<DocumentResponse>(`/api/v1/workspaces/${workspaceId}/documents/${encodeURIComponent(documentCode)}`)
  if (response.data.code !== '0000' || !response.data.data) throw new ApiError(response.status, response.data.code, response.data.info || '文档加载失败')
  return response.data.data
}

export async function updateDocument(workspaceId: number, documentCode: string, expectedVersionId: number, content: string): Promise<DocumentDetail> {
  const response = await client.put<DocumentResponse>(`/api/v1/workspaces/${workspaceId}/documents/${encodeURIComponent(documentCode)}/content`, { data: { expectedVersionId, content } })
  if (response.data.code !== '0000' || !response.data.data) throw new ApiError(response.status, response.data.code, response.data.info || '文档保存失败')
  return response.data.data
}

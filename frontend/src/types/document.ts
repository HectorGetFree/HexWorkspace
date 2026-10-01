import type { ApiEnvelope } from './auth'

export interface DocumentDetail {
  documentId: number
  documentCode: string
  workspaceId: number
  parentId: number | null
  ownerUserId: number
  title: string
  fileName: string
  source?: string
  content: string | null
  documentType: string
  status: string
  currentVersionNo: number
  currentVersionId: number
  deletedAt: string | null
  createdAt?: string
  updatedAt?: string
}
export type DocumentResponse = ApiEnvelope<DocumentDetail>

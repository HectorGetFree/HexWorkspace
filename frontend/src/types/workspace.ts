import type { ApiEnvelope } from './auth'

export interface Workspace { workspaceId: number; workspaceCode: string; workspaceName: string; isDefault: boolean; status: string; ownerUserId: number; description?: string }
export interface Folder { folderId: number; folderCode: string; workspaceId: number; parentId: number | null; name: string; root: boolean; status: string; deletedAt: string | null }
export interface DocumentResource { documentId?: number; documentCode: string; workspaceId: number; parentId: number | null; title: string; fileName?: string; status: string; currentVersionNo?: number; currentVersionId?: number; deletedAt?: string | null }
export interface DirectoryResponse { currentFolder: Folder; folders: Folder[]; documents: DocumentResource[] }
export type WorkspaceListResponse = ApiEnvelope<{ workspaces: Workspace[] }>
export type FolderResponse = ApiEnvelope<Folder>
export type DirectoryApiResponse = ApiEnvelope<DirectoryResponse>
export interface ResourceNode { type: 'folder' | 'document'; id: string; name: string; folder?: Folder; document?: DocumentResource }

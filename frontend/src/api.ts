// Typed client for the Spring API. The shapes mirror backend/src/main/java/com/merklelog/api/Dto.java.

const BASE = (import.meta.env.VITE_API_BASE ?? '') + '/api'

export type Params = Record<string, string>

export interface StrategyInfo {
  name: string
  description: string
  defaults: Params
}

export interface DatasetInfo {
  id: number
  name: string
  seed: number
  size: number
  createdAt: string
}

export interface EntryView {
  position: number
  id: number
  timestamp: string
  level: string
  source: string
  message: string
  leafHash: string
}

export interface ChunkView {
  index: number
  start: number
  size: number
  depth: number
  root: string
  startTime: string
  endTime: string
}

export interface ForestView {
  strategy: string
  parameters: Params
  entryCount: number
  chunkCount: number
  superRoot: string
  superTreeDepth: number
  chunks: ChunkView[]
}

export interface TreeView {
  kind: 'chunk' | 'super'
  chunkIndex: number
  start: number
  /** Bottom-up: levels[0] are the leaves, the last level is the root. */
  levels: string[][]
}

export interface TraceView {
  before: string
  sibling: string
  side: 'left' | 'right'
  after: string
}

export interface StageView {
  leafIndex: number
  leafCount: number
  steps: { sibling: string; side: 'left' | 'right' }[]
  trace: TraceView[]
  computedRoot: string
  expectedRoot: string
  valid: boolean
}

export interface ProofView {
  strategy: string
  entry: EntryView
  chunkIndex: number
  localIndex: number
  entryStage: StageView
  chunkStage: StageView
  totalSteps: number
  sizeInBytes: number
  superRoot: string
  valid: boolean
}

export interface TamperOutcome {
  subject: string
  chunkCountBefore: number
  chunkCountAfter: number
  chunkSizesBefore: number[]
  chunkSizesAfter: number[]
  changedChunks: number[]
  rebuildHashOps: number
  superRootBefore: string
  superRootAfter: string
  detected: boolean
}

export interface TamperResult {
  operation: 'edit' | 'insert'
  position: number
  entryCountBefore: number
  outcomes: TamperOutcome[]
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(BASE + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!response.ok) {
    // The API answers errors with RFC 9457 problem details: { detail: "..." }.
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? `${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

function query(strategy: string, params: Params = {}): string {
  return '?' + new URLSearchParams({ strategy, ...params }).toString()
}

export const api = {
  strategies: () => request<StrategyInfo[]>('/strategies'),
  datasets: () => request<DatasetInfo[]>('/datasets'),
  entries: (id: number, offset: number, limit: number) =>
    request<{ offset: number; total: number; entries: EntryView[] }>(
      `/datasets/${id}/entries?offset=${offset}&limit=${limit}`,
    ),
  chunks: (id: number, strategy: string, params?: Params) =>
    request<ForestView>(`/datasets/${id}/chunks${query(strategy, params)}`),
  chunkTree: (id: number, chunk: number, strategy: string, params?: Params) =>
    request<TreeView>(`/datasets/${id}/chunks/${chunk}/tree${query(strategy, params)}`),
  superTree: (id: number, strategy: string, params?: Params) =>
    request<TreeView>(`/datasets/${id}/supertree${query(strategy, params)}`),
  proof: (id: number, index: number, strategy: string, params?: Params) =>
    request<ProofView>(`/datasets/${id}/proof/${index}${query(strategy, params)}`),
  tamper: (id: number, operation: 'edit' | 'insert', position: number, message: string, parameters?: Params) =>
    request<TamperResult>(`/datasets/${id}/tamper`, {
      method: 'POST',
      body: JSON.stringify({ operation, position, message, parameters }),
    }),
}

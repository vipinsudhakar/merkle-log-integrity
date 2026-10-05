import { useEffect, useState } from 'react'
import { api, type DatasetInfo, type Params } from './api'
import { ChunkingStrip } from './components/ChunkingStrip'
import { TreeProof } from './components/TreeProof'
import { fmt } from './strategies'

type Tab = 'chunking' | 'proof' | 'tamper' | 'results'

const TABS: { id: Tab; label: string; ready: boolean }[] = [
  { id: 'chunking', label: 'Chunking', ready: true },
  { id: 'proof', label: 'Tree & proof', ready: true },
  { id: 'tamper', label: 'Tamper & insert', ready: false },
  { id: 'results', label: 'Results', ready: false },
]

export default function App() {
  const [datasets, setDatasets] = useState<DatasetInfo[]>([])
  const [datasetId, setDatasetId] = useState<number | null>(null)
  // The tab follows the URL hash (#chunking, #proof, ...) so a view can be linked or bookmarked.
  const [tab, setTabState] = useState<Tab>(() => {
    const fromHash = window.location.hash.slice(1) as Tab
    return TABS.some((t) => t.id === fromHash && t.ready) ? fromHash : 'chunking'
  })
  const setTab = (next: Tab) => {
    setTabState(next)
    window.history.replaceState(null, '', '#' + next)
  }
  const [error, setError] = useState<string | null>(null)

  // Proof view selection, shared so a click in the chunking strip can open it.
  const [proofStrategy, setProofStrategy] = useState('caac')
  const [proofParams, setProofParams] = useState<Params>({})
  const [proofEntry, setProofEntry] = useState(0)

  useEffect(() => {
    api
      .datasets()
      .then((list) => {
        setDatasets(list)
        setDatasetId((current) => current ?? list.find((d) => d.name === 'demo-2k')?.id ?? list[0]?.id ?? null)
      })
      .catch((e: Error) => setError(`Cannot reach the API: ${e.message}`))
  }, [])

  const dataset = datasets.find((d) => d.id === datasetId)

  return (
    <div className="min-h-screen">
      <header className="border-b border-line bg-panel">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-3 px-4 py-4 sm:px-6">
          <div className="mr-auto">
            <h1 className="text-lg font-semibold tracking-tight">
              Content-Anchored Adaptive Chunking <span className="text-accent">(CAAC)</span>
            </h1>
            <p className="text-sm text-muted">Tamper-evident log integrity with Merkle forests</p>
          </div>
          <label className="flex items-center gap-2 text-sm text-muted">
            Dataset
            <select
              className="input"
              value={datasetId ?? ''}
              onChange={(e) => {
                setDatasetId(Number(e.target.value))
                setProofEntry(0)
              }}
            >
              {datasets.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name} ({fmt(d.size)} entries)
                </option>
              ))}
            </select>
          </label>
        </div>
        <nav className="mx-auto flex max-w-7xl gap-1 overflow-x-auto px-4 sm:px-6">
          {TABS.map((t) => (
            <button
              key={t.id}
              disabled={!t.ready}
              onClick={() => setTab(t.id)}
              className={`-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
                tab === t.id ? 'border-accent text-accent' : 'border-transparent text-muted hover:text-ink'
              } disabled:cursor-not-allowed disabled:opacity-40`}
            >
              {t.label}
              {!t.ready && <span className="ml-1 text-xs">(soon)</span>}
            </button>
          ))}
        </nav>
      </header>

      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
        {error && <p className="rounded-lg bg-bad/10 p-4 text-sm text-bad">{error}</p>}
        {dataset && tab === 'chunking' && (
          <ChunkingStrip
            dataset={dataset}
            onOpenChunk={(strategy, entryIndex, params) => {
              setProofStrategy(strategy)
              setProofParams(params)
              setProofEntry(entryIndex)
              setTab('proof')
            }}
          />
        )}
        {dataset && tab === 'proof' && (
          <TreeProof
            dataset={dataset}
            strategy={proofStrategy}
            params={proofParams}
            entryIndex={Math.min(proofEntry, dataset.size - 1)}
            onStrategyChange={(s) => {
              setProofStrategy(s)
              setProofParams({})
            }}
            onEntryChange={setProofEntry}
          />
        )}
      </main>
    </div>
  )
}

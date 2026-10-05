import { useEffect, useState } from 'react'
import { api, type DatasetInfo, type Params } from './api'
import { ChunkingStrip } from './components/ChunkingStrip'
import { TreeProof } from './components/TreeProof'
import { fmt } from './strategies'
import { Tabs, type TabItem } from './ui/Tabs'
import { ThemeToggle } from './ui/ThemeToggle'

type Tab = 'chunking' | 'proof' | 'tamper' | 'results'

const TABS: TabItem<Tab>[] = [
  { id: 'chunking', number: '01', label: 'Chunking', ready: true },
  { id: 'proof', number: '02', label: 'Tree & proof', ready: true },
  { id: 'tamper', number: '03', label: 'Tamper & insert', ready: false },
  { id: 'results', number: '04', label: 'Results', ready: false },
]

export default function App() {
  const [datasets, setDatasets] = useState<DatasetInfo[]>([])
  const [datasetId, setDatasetId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  // The tab follows the URL hash (#chunking, #proof, ...) so a view can be linked or bookmarked.
  const [tab, setTabState] = useState<Tab>(() => {
    const fromHash = window.location.hash.slice(1) as Tab
    return TABS.some((t) => t.id === fromHash && t.ready) ? fromHash : 'chunking'
  })
  const setTab = (next: Tab) => {
    setTabState(next)
    window.history.replaceState(null, '', '#' + next)
  }

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
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-rule">
        <div className="mx-auto w-full max-w-[88rem] px-gutter">
          <div className="flex flex-wrap items-center justify-between gap-4 pt-block">
            <span className="kicker">Advanced DSA · Tamper-evident log integrity</span>
            <div className="flex items-center gap-3">
              <label className="flex items-center gap-2">
                <span className="kicker">Dataset</span>
                <select
                  className="field"
                  value={datasetId ?? ''}
                  onChange={(e) => {
                    setDatasetId(Number(e.target.value))
                    setProofEntry(0)
                  }}
                >
                  {datasets.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name} · {fmt(d.size)} entries
                    </option>
                  ))}
                </select>
              </label>
              <ThemeToggle />
            </div>
          </div>

          <div className="pt-[clamp(1.75rem,1rem+3vw,4rem)] pb-block">
            <h1 className="max-w-[22ch] font-display text-display leading-[1.02] font-light tracking-[-0.02em]">
              Content-Anchored Adaptive Chunking
            </h1>
            <p className="mt-5 max-w-[60ch] font-display text-lede leading-snug text-ink-soft italic">
              Keeping changes to a log local inside a Merkle forest. An extension of the adaptive chunking of
              Yağız, Horasan &amp; Yurttakal (2026).
            </p>
          </div>

          <Tabs items={TABS} active={tab} onChange={setTab} />
        </div>
      </header>

      <main className="mx-auto w-full max-w-[88rem] flex-1 px-gutter pt-[clamp(2rem,1.2rem+3vw,4rem)] pb-section">
        {error && (
          <p className="rounded-[3px] border border-tampered bg-tampered-wash px-4 py-3 text-small text-tampered">
            {error}
          </p>
        )}
        {dataset && tab === 'chunking' && (
          <ChunkingStrip
            key={dataset.id}
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
            key={dataset.id}
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

      <footer className="border-t border-rule">
        <div className="mx-auto flex w-full max-w-[88rem] flex-wrap justify-between gap-4 px-gutter py-block text-small text-ink-faint">
          <span>Every hash on this page is computed by the Java engine and served by the API.</span>
          <span className="font-display italic">
            Base paper: Yağız, Horasan, Yurttakal · arXiv:2605.00065 · 2026
          </span>
        </div>
      </footer>
    </div>
  )
}

# CAAC Visualiser (frontend)

React + TypeScript + Vite + Tailwind CSS + Recharts. It shows what the Java engine computes;
it never hashes or chunks anything itself. Every number and hash comes from the Spring API.

## Run locally

Start the API first (`cd backend && mvn spring-boot:run`, port 8080), then:

```bash
npm install
npm run dev        # http://localhost:5173 — /api is proxied to :8080
npm run build      # type-check and production build into dist/
npm run lint
```

In production, set `VITE_API_BASE` to the API's URL at build time (for example
`https://merkle-log-api.onrender.com`), and add the site's URL to the API's
`APP_CORS_ALLOWED_ORIGINS`.

## Views

| Tab | Shows |
|---|---|
| Chunking (`#chunking`) | The same log stream cut by all five strategies, to scale, with parameters and memory pressure adjustable. Click a chunk to open its proof. |
| Tree & proof (`#proof`) | An entry's two-stage proof drawn on the real trees (chunk tree, then super-tree), with every verification hash. |
| Tamper & insert | Coming next. |
| Results | Coming next: the benchmark graphs from `docs/benchmarks/results.json`. |

Design system ("The Ledger": typography, colour, space, motion, primitives): [`DESIGN.md`](DESIGN.md).

Code: `src/api.ts` (typed API client, mirrors `Dto.java`), `src/strategies.ts` (colours and
labels), `src/ui/` (design-system primitives), `src/components/` (the views).

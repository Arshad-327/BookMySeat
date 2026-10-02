# BookMySeat frontend

React 18, TypeScript, Vite, React Router 6, TanStack Query, Axios, Tailwind.

```
npm install
npm run dev        # http://localhost:5173 - and only 5173, see vite.config.ts
npm run build      # tsc --noEmit, then vite build
npm test           # vitest, once, in plain Node
```

`npm test` covers the pure modules: `src/lib/seatSelection.ts`, `src/lib/checkout.ts` and
`src/api/errors.ts`. There are no component tests and no jsdom. The HTTP interceptor is
deliberately not unit tested - `src/api/errors.test.ts` says why.

`npm run build` does not run the tests, on purpose: the build is what produces the artefact,
and it should not need a test runner to do it. CI runs both.

The app talks to the api-gateway on `http://localhost:8080` and nothing else. There is no
dev proxy: the browser makes real cross-origin requests, so CORS is exercised in
development rather than discovered at deployment. Override the gateway URL with
`VITE_API_BASE_URL` in `.env.local` (see `.env.example`).

It is not part of the Maven build. The root `pom.xml` lists its modules by name and this
directory is not one of them.

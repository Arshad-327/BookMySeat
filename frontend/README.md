# BookMySeat frontend

React 18, TypeScript, Vite, React Router 6, TanStack Query, Axios, Tailwind.

```
npm install
npm run dev        # http://localhost:5173 - and only 5173, see vite.config.ts
npm run build      # tsc --noEmit, then vite build
```

The app talks to the api-gateway on `http://localhost:8080` and nothing else. There is no
dev proxy: the browser makes real cross-origin requests, so CORS is exercised in
development rather than discovered at deployment. Override the gateway URL with
`VITE_API_BASE_URL` in `.env.local` (see `.env.example`).

It is not part of the Maven build. The root `pom.xml` lists its modules by name and this
directory is not one of them.

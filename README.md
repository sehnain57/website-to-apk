# WebToAPK (free, public)

Browser form -> Cloudflare Worker (holds the GitHub token) -> GitHub Actions build -> Release with app.apk + source.zip.

## Deploy the public API (Cloudflare, free, ~5 min)
1. Create a NEW fine-grained GitHub token: only repo `website-to-apk`, permissions **Actions: Read and write** (nothing else).
2. cloudflare.com -> Workers & Pages -> Create -> Worker -> name it `webtoapk-api` -> Deploy -> Edit code -> paste `worker/worker.js` -> Deploy.
3. Worker -> Settings -> Variables and Secrets:
   - Secret `GH_TOKEN` = your new token
   - Text `REPO` = sehnain57/website-to-apk
   - Text `REF` = main
   - Text `ALLOWED_ORIGIN` = https://sehnain57.github.io
   - Text `MAX_PER_HOUR` = 5 (optional), `BLOCKED_DOMAINS` = comma list (optional)
4. Optional rate limit: Storage & Databases -> KV -> create namespace -> Worker -> Bindings -> add KV binding named `RATE`.
5. Copy the worker URL (https://webtoapk-api.<you>.workers.dev) into `const WORKER = ""` in `docs/index.html`, commit.

Old builds are deleted after 7 days by `.github/workflows/cleanup.yml`.
Optional permanent signing key: repo secrets `KS_B64`, `KS_PASS`, `KS_ALIAS`.

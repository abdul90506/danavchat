# Danav Chat backend

Current Worker: `https://danav-chat.wahabxedits.workers.dev`

The Worker is deployed, the D1 schema is applied, and the runtime secrets are configured. Health check:

```sh
curl https://danav-chat.wahabxedits.workers.dev/health
```

To redeploy from this folder (use a Cloudflare token scoped to the Danav account with **Workers Scripts: Edit** and **D1: Edit**):

```sh
npm install
export CLOUDFLARE_API_TOKEN='your-token'
export CLOUDFLARE_ACCOUNT_ID='your-account-id'
npx wrangler deploy
```

Required runtime secrets are stored in Cloudflare, not in source: `SESSION_SECRET`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`, `TURN_USER`, `TURN_PASS`, `FCM_SA`. If rotating a secret, set only the replacement value:

```sh
npx wrangler secret put NAME_OF_SECRET
```

Never add Firebase Admin JSON, API tokens or private API keys to Git or the Android app. The Cloudinary cloud name, Google Web Client ID and TURN host are non-secret configuration in `wrangler.jsonc`.

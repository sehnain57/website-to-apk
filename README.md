# WebToAPK (free, no server)

1. Create a **public** GitHub repo and upload everything in this folder.
2. Settings → Pages → deploy from branch `main`, folder `/docs`. Your builder is at `https://USER.github.io/REPO/`.
3. Settings → Actions → General → Workflow permissions → **Read and write**.
4. Create a fine-grained token for that repo: Actions (read/write), Contents (read/write).
5. Open the builder, fill in the form, paste repo + token, click **Build APK**. ~3-5 min later you get the download link.

Optional secrets for a permanent signing key (needed to update an app over a previous install): `KS_B64` (base64 of your .jks), `KS_PASS`, `KS_ALIAS`.
Use AdMob test IDs while testing. Banner shows at the bottom; interstitial every 4th page load. No App ID = no ads.

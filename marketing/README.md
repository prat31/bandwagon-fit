# bandwagon.fit

The marketing site. Plain HTML and CSS, no framework, deployed to GitHub Pages by
`.github/workflows/marketing.yml` whenever `marketing/` or `web/` changes on `main`.

| Path | What |
|---|---|
| `src/index.html`, `src/styles.css` | The landing page. Colours follow the palette in `AGENTS.md`; type is Roboto, like the app |
| `src/assets/` | Logo, social preview and screen recordings (MP4 + poster frame, made from `docs/assets/*.gif`) |
| `build.sh` | Assembles `dist/`: the page, plus privacy, terms, delete account, the invite page (as `404.html`, so `/i/CODE` links work) and `.well-known/assetlinks.json` from `web/` |

```bash
make marketing-serve   # build and open http://localhost:4173
```

The store badges are placeholders. When the app is live, turn the Google Play badge into a link to
`https://play.google.com/store/apps/details?id=fit.bandwagon` in both places in `index.html`.

To refresh a screen recording from a new GIF:

```bash
ffmpeg -i docs/assets/home.gif -movflags +faststart -pix_fmt yuv420p -vf "scale=560:-2:flags=lanczos" -c:v libx264 -crf 24 -an marketing/src/assets/home.mp4
ffmpeg -i docs/assets/home.gif -vframes 1 -vf "scale=560:-2:flags=lanczos" -q:v 3 marketing/src/assets/home.jpg
```

## One-time GitHub Pages and domain setup

1. **Verify the domain** (stops anyone else claiming it on Pages): GitHub → your profile
   **Settings → Pages → Add a domain** → `bandwagon.fit`. Add the `TXT` record it shows
   (`_github-pages-challenge-<user>`) at your DNS provider, then click **Verify**.
2. **Point DNS at GitHub Pages** at your DNS provider:

   | Type | Name | Value |
   |---|---|---|
   | `A` | `@` | `185.199.108.153` |
   | `A` | `@` | `185.199.109.153` |
   | `A` | `@` | `185.199.110.153` |
   | `A` | `@` | `185.199.111.153` |
   | `AAAA` | `@` | `2606:50c0:8000::153` |
   | `AAAA` | `@` | `2606:50c0:8001::153` |
   | `AAAA` | `@` | `2606:50c0:8002::153` |
   | `AAAA` | `@` | `2606:50c0:8003::153` |
   | `CNAME` | `www` | `<user>.github.io` |

   Remove any other `A`/`AAAA`/`ALIAS` records on `@` (for example a registrar parking page).
   Leave `MX` and `TXT` records alone so email keeps working.
3. **Turn on Pages** in the repository: **Settings → Pages → Build and deployment → Source:
   GitHub Actions**.
4. **Run the workflow** once: **Actions → Marketing site → Run workflow** (or push a change under
   `marketing/`).
5. **Set the custom domain**: **Settings → Pages → Custom domain** → `bandwagon.fit` → **Save**.
   With an Actions deploy no `CNAME` file is needed. Wait for the DNS check to pass, then tick
   **Enforce HTTPS** (the certificate can take up to an hour or so).

Check it with `dig bandwagon.fit +short` (should list the four `185.199.x.153` addresses) and by
opening `https://bandwagon.fit/privacy` and `https://bandwagon.fit/.well-known/assetlinks.json`.

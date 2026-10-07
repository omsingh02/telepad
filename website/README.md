# The Telepad website

A single static page: plain HTML, CSS and a little JavaScript. No framework, no build step, and nothing is loaded from other sites (the fonts are served from here too), which is also what the Content Security Policy in `vercel.json` enforces.

```
index.html            the page
404.html              the "not found" page
assets/site.css       all the styling (dark by default, light with prefers-color-scheme)
assets/site.js        scroll reveal, the screenshot strip's buttons, the "recommended for you" download
assets/fonts/         Cal Sans and Inter, subset to Latin (OFL.txt has their license)
assets/img/           logo, icons, social image, and screens/ (made from the app's screenshots)
vercel.json           security headers (CSP and friends) and caching
tools/                preview server, image builder, URL changer
```

## Working on it

```bash
python3 website/tools/serve.py           # http://127.0.0.1:8080, with the same headers as production
python3 website/tools/build_images.py    # rebuild assets/img/screens/ after re-recording the app screenshots
docs/brand/export.sh                     # rebuild the icons and the social images
```

`serve.py` sends the production Content Security Policy, so an inline style or a script from another site shows up in the browser's console while you work, not after a deploy. Nothing else needs installing.

Keep the copy honest: say only what the app and the server do today. Download buttons point at `releases/latest/download/<name>`, and the release workflow publishes those version-less names (`telepad-android.apk`, `telepad-server-windows-x86_64.exe`, `telepad-server-linux-x86_64.tar.gz`, `telepad-server-macos-universal.tar.gz`).

## Deploying

The site is a Vercel project named `telepad-app` (its address is `https://telepad-app.vercel.app`). It is connected to this repository, so **a push to `main` that changes `website/` deploys it**, and a pull request that changes it gets a preview. Pushes that only touch the app or the server build nothing.

The project is set up as follows (Vercel dashboard, Project → Settings):

- *Git*: connected to `omsingh02/telepad`, production branch `main`.
- *Root Directory*: `website`.
- *Ignored Build Step*: builds only when `website/` has changed, so code changes and dependency updates do not use up the free plan's 100 deployments a day. It skips branches named `dependabot/*` and commits by Dependabot, and any push that changes nothing under `website/` since the last successful deployment (or since the previous commit, when there is none). If git cannot tell, for example because that last commit is not in Vercel's shallow clone, it builds. Exit code 0 means "skip", anything else means "build"; a skipped build is not a failure:
  `case "$VERCEL_GIT_COMMIT_REF" in dependabot/*) exit 0;; esac; [ "$VERCEL_GIT_COMMIT_AUTHOR_LOGIN" = "dependabot[bot]" ] && exit 0; git diff --quiet "${VERCEL_GIT_PREVIOUS_SHA:-HEAD^}" HEAD -- ":/website" && exit 0; exit 1`
  (`:/website` is relative to the top of the repository, so it works whatever directory the command runs in. The limit for this field is 256 characters; this command uses 221.)

Because the Root Directory is set, `vercel deploy` has to be run from the repository root (not from inside `website/`), if it is ever needed by hand.

### A custom domain

1. Add the domain to the project: `vercel domains add telepad.example.org`, and create the DNS record Vercel asks for.
2. Point the page's own links at it: `website/tools/set-url.sh https://telepad.example.org`
3. Commit and push (Vercel deploys from the push), and update the repository's homepage: `gh repo edit --homepage https://telepad.example.org`

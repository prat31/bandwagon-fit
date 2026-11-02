#!/usr/bin/env bash
# Assembles the bandwagon.fit site into marketing/dist for GitHub Pages.
#
# The marketing page lives in marketing/src. The pages the app and store listing link to (privacy,
# terms, delete account, invite links, App Links verification) stay in web/, their single source,
# and are copied in so the domain keeps serving them.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
web="$here/../web"
out="$here/dist"

rm -rf "$out"
mkdir -p "$out"
cp -R "$here/src/." "$out/"

# GitHub Pages serves /privacy from privacy.html, like Firebase Hosting's cleanUrls.
cp "$web/privacy.html" "$web/terms.html" "$web/delete-account.html" "$out/"
cp -R "$web/.well-known" "$out/"

# Invite links (/i/CODE) have no file of their own, so Pages answers them with 404.html: make that
# the invite page. It sends any other unknown path to the home page.
cp "$web/index.html" "$out/404.html"

echo "Built $(find "$out" -type f | wc -l | tr -d ' ') files into ${out#"$PWD"/}"

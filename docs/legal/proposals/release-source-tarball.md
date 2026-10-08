# Proposal: attach a source archive to every release

> PROPOSAL — a diff for the Chief / android-engineer to apply to `.github/workflows/release.yml` and `docs/maintainer-release-guide.md`. Nothing here has been applied. Not legal advice.

## Why

GPLv3 section 6(d) needs the complete corresponding source offered from a designated place. Today:

- `release.yml` only builds and attests unsigned artifacts; it never creates a release. The maintainer publishes by hand with `gh release create` (`docs/maintainer-release-guide.md`, section 8).
- That guide says: "GitHub automatically exposes source archives for the tag; do not upload separate source ZIP or tar files."

GitHub's automatic "Source code (zip / tar.gz)" assets do satisfy the practical need. But they are not controlled by the project: GitHub can change their byte content over time, and they carry no checksum or attestation. An explicit archive, attested next to the binaries, gives users a stable, checkable copy. This is hardening, not a fix for a hard FAIL (see `GPL_RELEASE_CHECKLIST.md`, B1).

## Design constraints (reproducibility is preserved)

- The reproducible-build jobs (`build`, `compare`) are **not touched**.
- The tarball is **not** added to the directory handled by `prepare-github-release.sh`. That script rejects unexpected files and builds `SHA256SUMS`, so adding the tarball there would break it.
- The source archive is its own job and its own artifact (`release-source`), with its own `.sha256` file and its own build-provenance attestation. It is made with `git archive` from the tag, which is deterministic for a given commit. `gzip -n` strips the timestamp and name.

## Diff 1: `.github/workflows/release.yml` (append a job at the end)

```diff
--- a/.github/workflows/release.yml
+++ b/.github/workflows/release.yml
@@ -115,3 +115,53 @@ jobs:
           name: verified-unsigned-release
           path: ${{ runner.temp }}/release-a/*
           retention-days: 30
           if-no-files-found: error
+
+  source-archive:
+    name: Source archive (GPLv3 section 6(d))
+    runs-on: ubuntu-24.04
+    needs: compare
+    permissions:
+      attestations: write
+      contents: read
+      id-token: write
+
+    steps:
+      - name: Checkout tagged source
+        uses: actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803 # v6
+        with:
+          ref: ${{ env.RELEASE_TAG }}
+
+      - name: Verify workflow provenance ref
+        run: |
+          if [ "$(git rev-parse HEAD)" != "$GITHUB_SHA" ]; then
+            echo "error: run the workflow from the $RELEASE_TAG tag ref" >&2
+            exit 1
+          fi
+
+      - name: Create deterministic source archive
+        run: |
+          mkdir "$RUNNER_TEMP/source"
+          name="nearbird-${RELEASE_TAG}-source"
+          git archive --format=tar --prefix="${name}/" "$RELEASE_TAG" \
+            | gzip -n -9 > "$RUNNER_TEMP/source/${name}.tar.gz"
+          (cd "$RUNNER_TEMP/source" && sha256sum "${name}.tar.gz" > "${name}.tar.gz.sha256")
+          cat "$RUNNER_TEMP/source/${name}.tar.gz.sha256"
+
+      - name: Attest source archive
+        uses: actions/attest-build-provenance@977bb373ede98d70efdf65b84cb5f73e068dcc2a # v3
+        with:
+          subject-path: ${{ runner.temp }}/source/*.tar.gz
+
+      - name: Upload source archive
+        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4
+        with:
+          name: release-source
+          path: ${{ runner.temp }}/source/*
+          retention-days: 30
+          if-no-files-found: error
```

(The action SHAs are copied from the existing file. Line numbers in the hunk header are indicative.)

## Diff 2: `docs/maintainer-release-guide.md` (section 8, after "Inspect the draft")

```diff
-`--verify-tag` prevents `gh` from silently creating a tag at the wrong commit.
-GitHub automatically exposes source archives for the tag; do not upload separate
-source ZIP or tar files.
+`--verify-tag` prevents `gh` from silently creating a tag at the wrong commit.
+GitHub also exposes automatic source archives for the tag. In addition, attach the
+attested `nearbird-$TAG-source.tar.gz` and its `.sha256` from the `release-source`
+artifact (GPLv3 section 6(d)); they are not part of `$RELEASE_DIR` and are not
+listed in `BITCHAT_SHA256SUMS`:
+
+```bash
+gh run download --repo "$REPOSITORY" -n release-source -D "$SOURCE_DIR"
+gh release upload "$TAG" "$SOURCE_DIR"/* --repo "$REPOSITORY"
+```
```

Also add to the release-notes template in that guide:

```diff
+- Source: nearbird-vX.Y.Z-source.tar.gz (attached), or tag vX.Y.Z at https://github.com/Rohanr1904/MeshUp
```

## Check after applying

- `verify-github-release.sh` downloads only `BITCHAT_*` and `nearbird-*.apk|aab`. The tarball name does not match `nearbird-*.apk` or `nearbird-*.aab`, so the script is unaffected. Re-run it once to confirm.
- `gh attestation verify nearbird-vX.Y.Z-source.tar.gz --repo Rohanr1904/MeshUp` should pass.
- Unrelated stale item found while reading the guide: its asset table still lists `bitchat-android-*.apk` names, while `prepare-github-release.sh` requires `nearbird-*`. Update the guide when applying this.

## Question for the lawyer

Is the automatic GitHub archive plus tag enough for section 6(d), or is the explicit attested archive advisable?

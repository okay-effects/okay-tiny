# Releasing

Releases are a Maven repository on GitHub Pages: the `gh-pages` branch, its `maven/` directory, served at
https://okay-effects.github.io/okay-tiny/maven. No account and no token is needed to use it, only a repository.
One artifact, `io.github.okay-effects:okay-tiny`, with its sources and its javadoc. It has no dependency.

While nobody depends on a version, a fix is released as that version again: the tag is moved and the files on
`gh-pages` are replaced. A new number is for a change somebody would have to adapt to.

## Making one

1. On `master`, everything green: `./gradlew test javadoc`.

2. The version: `version` in `build.gradle.kts` and in the README's Install; committed, tagged (`v0.1.0`).

3. The repository written into `build/pages-maven/`:

   ```sh
   rm -rf build/pages-maven
   ./gradlew publishAllPublicationsToPagesRepository
   ```

   The POM names no dependency, no SNAPSHOT and no `file:` repository.

4. Onto `gh-pages`, under `maven/`:

   ```sh
   # the first time: git worktree add --orphan -b gh-pages /tmp/okay-tiny-pages
   git worktree add /tmp/okay-tiny-pages gh-pages
   mkdir -p /tmp/okay-tiny-pages/maven
   rsync -a build/pages-maven/ /tmp/okay-tiny-pages/maven/
   touch /tmp/okay-tiny-pages/.nojekyll
   git -C /tmp/okay-tiny-pages add -A
   git -C /tmp/okay-tiny-pages commit -m "release 0.1.0"
   git -C /tmp/okay-tiny-pages push origin gh-pages
   git push origin v0.1.0
   ```

   The first time, GitHub Pages is turned on for the branch `gh-pages`.

5. Checked as a stranger would: a new Gradle project with an empty home (`--gradle-user-home <temp>`) that names
   only the Pages URL, runs a program with an effect of its own, one of `Std`'s and an observer.

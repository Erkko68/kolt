# Upgrading Jolt

Jolt is the `jolt/` submodule, pinned to a release tag (currently **v5.6.0**). Since the API is generated from its
headers, an upgrade is mostly: move the pin, regenerate, read the diff.

## Steps

1. **Move the submodule** to the new tag.

   ```sh
   git -C jolt fetch --tags
   git -C jolt checkout v<new>
   ```

2. **Regenerate.**

   ```sh
   ./gradlew generateApi
   ```

   If clang can't read a header, the task stops and says why. A type the generator has no rule for doesn't stop it:
   the functions using it become `todo` lines in the coverage report. Either way the fix is usually in the config (`build-logic/src/main/kotlin/kolt-root.gradle.kts`): a new ID type in `types.scalars`, a new
   template alias in `templates`. See [The Generator](generator.md#the-generator-knows-no-library).

3. **Read the diff.** It is the upgrade's changelog, in three places:

   - `c/api-coverage.txt`: its first lines give the totals. New `todo` lines are new API the generator can't map yet;
     decide whether to teach it, or leave them.
   - `kotlin/kolt/src/commonMain`: what changed for users. Removed or renamed functions are breaking changes.
   - `c/api-headers.txt`: Jolt may have added headers worth binding. New ones aren't picked up unless a glob already
     matches them.

4. **Check the hand-written parts** against the new headers: `c/kolt/manual/` (the bridge and the manual C functions),
   the math types in `kotlin/kolt/src/commonMain/.../math/`, and the Jolt options in `c/CMakeLists.txt`.

5. **Build and test** on every target. See [Testing](testing.md#running-them).

6. **Confirm nothing is stale**, and update the Jolt version in the README's badge and on this page.

   ```sh
   ./gradlew apiCheck
   ```

## Binding more of the same Jolt

No upgrade needed: add the header to `c/api-headers.txt`, run `./gradlew generateApi`, and add a test for what it
brought in.

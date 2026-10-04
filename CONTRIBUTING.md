# Contributing

Thank you for helping improve Caravan Leveler.

## Before starting

- Search existing issues before opening a new one.
- Discuss large behavioral or architectural changes in an issue first.
- Never include signing keys, credentials, personal sensor identifiers, or
  other private data in commits, screenshots, or logs.

## Development workflow

1. Fork the repository and create a focused branch.
2. Make the smallest coherent change that solves the issue.
3. Add or update tests for behavioral changes.
4. Run the verification suite:

   ```shell
   ./gradlew testDebugUnitTest assembleDebug lintDebug
   ```

5. Update documentation and the changelog when user-visible behavior changes.
6. Open a pull request describing the problem, solution, and verification.

## Code and resource guidelines

- Follow the existing Kotlin and Android resource style.
- Keep UI strings in resources and update both English and German translations.
- Preserve the minimum SDK and hardware requirements unless the change is
  explicitly intended to alter compatibility.
- Keep generated build output, IDE state, keystores, and local configuration
  out of Git.
- Prefer vector artwork that remains readable in light and dark modes.

## Licensing requirements

By submitting a contribution, you agree that it can be distributed under
GPL-3.0-only as part of this project.

Only contribute code and assets that you wrote or have the right to
redistribute. New dependencies or third-party assets must have GPLv3-compatible
terms, include required attribution, and be recorded in
`THIRD_PARTY_NOTICES.md`. Do not copy icons, images, models, or code merely
because they are available without payment.

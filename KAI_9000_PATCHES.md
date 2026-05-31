# Karl 9000 Patch Set

Karl 9000 is a public source snapshot of a heavily customized Kai APK build.
Use it, fork it, adapt it, or strip it for parts. Do not expect maintenance,
support, compatibility guarantees, issue response, or a stable roadmap from the
patch author.

## Base

- Upstream project: Kai by Simon Schubert
- Base tag: `v2.5.1`

Preserve upstream license terms and attribution when reusing this branch.

## What This Patch Set Adds

- `termuxSuite` Android product flavor using package id `com.termux.kai`.
- Shared Termux UID manifest overlay for integration with a Termux suite build.
- Termux-backed sandbox controller that can run commands through Termux bash and
  a `proot-distro` environment when available.
- Persistent per-session shell handling for terminal/tool execution.
- UI state for sandbox availability, package-manager visibility, reset
  visibility, and terminal lifecycle.
- Settings controls for local tool exposure:
  - safe allowlist
  - all enabled tools
  - disabled
- Settings controls for local LiteRT backend selection:
  - auto
  - GPU
  - CPU
- Additional LiteRT model catalog entries and side-loaded `.litertlm` discovery.
- Termux-suite model storage path support for sharing model files with Termux.
- No-op Play review helper for the Termux-suite flavor.

## Credits

- Kai upstream by Simon Schubert provides the base application.
- Termux provides the Android terminal/userland model this branch integrates
  with.
- LiteRT / litert-lm community model packages inspired the on-device model
  catalog and side-loading workflow.
- Ollama and local homelab LLM workflows informed the remote/local model routing
  expectations.

This branch is not an upstream Kai release and is not affiliated with the
upstream Kai or Termux projects.

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
- **Root shell access toggle**: persistent user-controlled setting (Settings > Sandbox, or Shield icon in chat top bar) that allows the assistant to use `su`/`sudo`/`tsu` directly through the persistent Termux shell when enabled. When disabled, root escalation is blocked in normal shell sessions and the assistant must use the `request_root_access` tool for one-shot audited root commands. Destructive device-level commands are always blocked.
- Side-loaded `.litertlm` model discovery under the shared Termux model directory.
- Additional LiteRT model catalog entries (Gemma 3 270M/1B, Qwen2.5 1.5B, DeepSeek R1 Distill Qwen 1.5B, Phi 4 Mini).

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

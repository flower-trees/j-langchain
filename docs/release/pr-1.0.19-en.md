# Pull Request: dev-1.0.19 → master

## Overview

Building on 1.0.18, this release improves Skill compatibility and script execution, custom model integration, JSON output, and long-running task memory, with HTTP and streaming fixes. The target version is **1.0.19**. Release preparation is in progress; publication has not occurred.

## Key Changes

### Skills and scripts

- Improve Claude Code-style `SKILL.md` directory loading and configuration parsing, including license and metadata.
- Add `ReferencesMode.INLINE` / `LAZY`: inline references by default or read them on demand through `read_reference`.
- In compatibility mode, provide scoped workspace file tools when `allowedTools` is empty. Code-built skills keep compatibility mode disabled by default.
- Improve argument splitting, execution in the original script directory, and Python package module execution; exclude helper modules from callable tools.
- Add the Article30 example and regression coverage for skills, scripts, and configuration loading.

### Models and requests

- Add `ChatCustom` / `CustomActuator` for custom OpenAI Chat Completions-compatible endpoints. Configure `models.custom.chat-url` and `models.custom.chat-key`; the key falls back to `CUSTOM_KEY`. No default endpoint is provided.
- Add `BaseChatModel.withJsonMode()` to request JSON output in supported implementations. Server support must be verified; this is not a universal JSON Schema validation guarantee.
- Fix third-party request JSON serialization and HTTP error handling, and adjust Hunyuan configuration.
- Fix messages potentially discarded when streaming iteration completes.

### Memory and context

- Add `PeriodicConversationSummaryMemory` and its Reader / Storer: when raw conversation entries reach `maxSize`, compact the entire batch into the summary and clear the raw buffer.
- Add `AgentTaskContext.getEarlyStepsSummary()` / `restoreSummary(String)` for restoring earlier-step summaries into a rebuilt context. Persistence remains the caller's responsibility.
- Improve task summary handling across agents, skills, and subagents.

## Breaking Changes and Migration

### String conversation IDs

The `appId`, `userId`, and `sessionId` parameters of `ConversationStorage.loadAll/append/replace/clear` change from `Long` to `String`. Related memory builders change accordingly.

- Replace calls such as `.sessionId(123L)` with `.sessionId("123")`; apply the same change to other IDs.
- Update custom `ConversationStorage` method signatures and recompile.
- Define a consistent ID conversion for existing persistent data so lookup keys still match. Do not convert null IDs into the literal string `"null"`.
- This is a source and binary compatibility change; downstream applications must be updated and rebuilt.

### Coze removal

Remove `ChatCoze`, the Coze actuator, OAuth helpers, and related DTOs. Existing Coze configuration no longer connects a built-in model. Applications needing Coze should retain the prior version or supply an adapter. `ChatDoubao` remains available. `ChatCustom` targets Chat Completions-compatible APIs and does not directly replace Coze-specific APIs.

## Validation

- [x] `mvn -o clean verify -Plocal-tests -Dgpg.skip=true`: 91 local tests, zero failures, errors, or skips; main, source, and Javadoc JARs generated.
- [x] Local test configuration and commands documented in the [release validation guide (Chinese)](../guide/release-validation.md).
- [ ] Four `AgentExecutorControlTest` cases and one `McpAgentExecutorReturnLastToolResultTest` case: the previous run failed because the model's free quota was exhausted. Rerun with available quota.
- [ ] `SkillCreatorClaudeCompatRegressionTest`: the previous run failed because the script's Python environment lacked PyYAML. Install the dependency in the appropriate environment and rerun.
- [ ] Other vendor, RAG, and TTS integration tests require their services; the full suite has not passed.
- [ ] GPG signing and Maven Central publication verification.

Existing compiler and Javadoc warnings remain. Local validation does not establish that all real providers have passed acceptance tests.

## Release Checklist

- [x] Set `pom.xml` to `1.0.19`.
- [x] Update both README and Quick Start dependency examples to `1.0.19`.
- [x] Add bilingual release notes, API additions, and migration guidance.
- [ ] Complete integration acceptance and GPG verification.
- [ ] Check regnexe-agent and regnexe-cli dependencies and compatibility.
- [ ] Commit, merge, tag, and publish.

Runtime baseline: Java 17, Spring Boot 3.2.12, and `salt-function-flow` 1.1.8. Historical release notes retain their original version numbers.

# Android Project GitHub Integration & Build Triage

Integrate your pre-existing Android Studio productivity project from GitHub, align dependencies and toolchain configurations with AI Studio's Android build environment, resolve all build/sync errors, and verify the app compiles and launches smoothly.

### User Review & Critical Decisions

> [!IMPORTANT]
> Please share your **GitHub repository URL** (and branch if not default/main) or push/upload your project files directly so we can begin the audit and build triage!

- **Confirmed Decision 1 (Import Method)**: Connect GitHub repository or share repo URL.
- **Confirmed Decision 2 (Domain)**: Productivity or utility application.
- **Confirmed Decision 3 (Goal)**: Fix bugs, resolve build or sync errors, and achieve a green compilation state.

---

### 1. Overview & Core Concept

- **What It Does**: Bridges your external Android Studio repository into the Google AI Studio cloud development environment, triages build issues (Gradle wrapper versions, AGP compatibility, dependency catalog alignment, SDK target settings), fixes syntax/compilation issues, and ensures the productivity app runs cleanly in the interactive emulator.
- **Target Audience / Persona**: You as the developer, working collaboratively to import, debug, and run your existing Android application.
- **Key Value**: Eliminates local environment mismatches, resolves Gradle/KSP/Kotlin synchronization failures, and brings your productivity app into a functional live-preview state.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Repository Ingestion & Audit**: Code and assets from GitHub are pulled into the workspace tree.
  2. **Build & Sync Diagnostic**: Fast pass over `build.gradle.kts`, `settings.gradle.kts`, and `libs.versions.toml` to verify Gradle 9.3+ / AGP 9.1+ compatibility and container environment parity.
  3. **Build Error Resolution**: Automated iterative triage of compilation issues, missing imports, or obsolete plugin hooks.
  4. **Live Verification**: Successful `compile_applet` build producing the debug APK and rendering the productivity app UI in the streaming Android preview.
- **Visual Identity & Theme**:
  - Preserve the existing UI styling, Material Design components, and brand tokens of your original productivity application.
  - Maintain edge-to-edge system bar insets and clean Compose or XML layouts.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Compatibility Alignment vs. Code Rewrite**
  - *Chosen Approach*: Keep the existing codebase intact. Only adjust Gradle plugins, SDK versions, or incompatible library versions strictly necessary to build in the AI Studio cloud runner.
  - *Why*: Minimizes risk of regression and respects your pre-existing architecture.
- **Decision 2: Secrets & Environment Properties Management**
  - *Chosen Approach*: Map any local API keys or endpoints through `.env` and `.env.example` using Secrets Gradle Plugin rather than hardcoded credentials or untracked `local.properties`.
  - *Why*: Ensures security compliance and seamless deployment inside AI Studio.

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────┐
│               GitHub Repository Source                 │
│         (Kotlin / Compose / XML / Resources)           │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│             AI Studio Gradle Build Pipeline            │
│   • AGP 9.1.1 + Gradle 9.3.1                          │
│   • SDK 36 (minSdk 24)                                 │
│   • Secrets Plugin (.env / .env.example)               │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│              App Module & Source Engine                │
│   • UI Layer: Jetpack Compose / View Binding           │
│   • Architecture: MVVM / Repository Layer              │
│   • Local Storage: Room DB / DataStore (Productivity)  │
└───────────────────────────┬────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│      Verified Debug APK & Live Android Emulator        │
└────────────────────────────────────────────────────────┘
```

- **Execution Steps**:
  1. Receive GitHub repository link or files.
  2. Inspect directory structure, manifests, and dependencies.
  3. Run `compile_applet` to identify any compiler or Gradle sync blockers.
  4. Resolve all errors iteratively.
  5. Update `metadata.json` and string resources with the actual app name.
  6. Finalize build verification.

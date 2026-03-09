# Development Notes

## Build Commands

| Command | Time | Output | Use for |
|---------|------|--------|---------|
| `./gradlew compileKotlin` | ~5s | Just checks compilation | Quick syntax check |
| `./gradlew test` | ~10s | Runs tests | Verification |
| `./gradlew installDist` | ~8s | `build/install/clawfare/bin/clawfare` | Fast iteration (JVM) |
| `./gradlew nativeCompile` | ~2min | `build/native/nativeCompile/clawfare` | Distribution binary |

## Workflow
1. Make changes
2. `./gradlew installDist` → test immediately
3. Reply to user
4. `./gradlew nativeCompile &` in background for the distributable binary

# Toolchain versions

quickjs-kt 1.0.15 is compiled with Kotlin 2.4, and AGP 9.5.1's built-in Kotlin is 2.2.10, so the Kotlin Gradle plugin is raised once in the root `buildscript`, as Spike 3 did. All versions are pinned in `gradle/libs.versions.toml`.

| Part | Version | Why |
|---|---|---|
| Gradle | 9.6.0 | Cached. Downloads from `services.gradle.org` time out |
| AGP | 9.5.1 | Cached, used by all spikes |
| Kotlin | 2.4.10 | Cached, worked with quickjs-kt in Spike 3. 2.4.21 exists but gains nothing here |
| KSP | 2.3.12 | Versioned apart from Kotlin since 2.3.0. 2.3.10 added Kotlin 2.4, and 2.3.13 has an open regression with concurrent runs ([releases](https://github.com/google/ksp/releases)) |
| Room | 2.8.5 | Stable. Room 3.0 is stable too, but renames packages and requires every query to be suspend or Flow ([releases](https://developer.android.com/jetpack/androidx/releases/room)) |
| Compose BOM | 2026.09.00 | Latest. The Compose compiler plugin follows Kotlin, so 2.4.10 |
| Media3 | 1.11.1 | Cached, used in Spike 2 |
| quickjs-kt | 1.0.15 | Cached for Android and JVM. The JVM jar includes a Windows x64 library |

**Risk, cleared 2026-11-09:** Room's KSP on AGP 9.5.1 with built-in Kotlin raised to 2.4.10 was unverified. Step 1 built the three-module skeleton with one Room entity: KSP generated the database and exported its schema, quickjs-kt evaluated JavaScript in a JVM test on Windows, and the app installed and created its database on the A26. Also pinned: `activity-compose` 1.13.0, `androidx.test.ext:junit` 1.3.0 and `androidx.test:runner` 1.7.0, the latest stable versions that day.

## Your choice

**Decided 2026-11-09: the versions above.**

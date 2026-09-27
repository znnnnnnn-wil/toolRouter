# Development

Requires JDK 21 and Maven 3.9 or later. Set JAVA_HOME to a JDK 21 installation before running Maven; the default Java on a developer machine may be older.

## Project layout

- pom.xml: Maven reactor and shared Java 21 build settings.
- tool-router/: library source and offline library tests. This module retains the io.github.toolrouter:tool-router artifact.
- tool-router-tooling/: CLI example, benchmark runner, and their tests. It depends on tool-router and is not part of the library JAR.
- tool-router-tooling/src/main/resources/benchmark/: labeled dataset packaged with the tooling module.
- tool-router-tooling/benchmark/: optional JSON export script.
- integration/consumer/: standalone Maven consumer used to check installed library metadata and transitive dependencies.
- docs/: architecture, benchmark methodology, and development notes.
- .github/workflows/ci.yml: reactor build, example smoke test, and library JAR boundary check.

Generated files in module target/ directories, .m2repo/, .benchmark-cache/, and tool-router-tooling/benchmark/dataset.json are ignored. The Maven local repository is configured in .mvn/maven.config.

## Commands

Run from the repository root:

    mvn clean verify
    mvn install
    mvn -q -f tool-router-tooling/pom.xml exec:java "-Dexec.mainClass=io.github.toolrouter.Example"
    mvn -q -f tool-router-tooling/pom.xml exec:java "-Dexec.mainClass=io.github.toolrouter.Benchmark"
    mvn -f integration/consumer/pom.xml verify

The example defaults to BM25, so it runs without a credential. Vector and hybrid modes require the environment variables described in the README. The benchmark also runs its BM25 portion without a credential; cached or online vector measurements have additional requirements described in the benchmark guide.

The standalone consumer checks the library after local installation, including resolution of its parent POM and transitive dependencies. For a remote release, publish the parent POM alongside the library artifact; this local check does not perform a remote publication.

## Change checklist

1. Keep public types in tool-router/src/main/java and developer utilities in tool-router-tooling/src/main/java.
2. Add focused offline tests for behavior changes.
3. Run mvn clean verify, mvn install, and the offline example.
4. Update the README or docs when commands, public behavior, or benchmark methodology changes.

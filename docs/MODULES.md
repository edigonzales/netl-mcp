# Modularer Build und Veröffentlichung

Die drei Repositories bleiben getrennt. INTERLIS und NETL exportieren jeweils ein
normales Modul-JAR und eine eigenständige Spring-Boot-Anwendung. Die Suite importiert
beide Modulkonfigurationen ausdrücklich in einem Spring-Kontext und einer JVM.
Fachliche Toolnamen, Schemas, Resource-URIs und Ergebnisse bleiben erhalten.

| Komponente | Snapshot-Koordinate | Import |
| --- | --- | --- |
| INTERLIS | `ch.so.agi:interlis-mcp-module:0.1.0-SNAPSHOT` | `ch.so.agi.mcp.InterlisMcpModuleConfiguration` |
| NETL | `ch.so.agi:netl-mcp-module:0.4.1-SNAPSHOT` | `ch.so.agi.netl.NetlMcpModuleConfiguration` |

Die Module enthalten keine Startklasse, eingebetteten Dependencies, globale
Logging-Konfiguration oder automatisch aktivierten Transporte. Publiziert werden
JAR, Maven-POM, Gradle-Metadaten, Sources und Javadoc auf
`https://jars.interlis.guru/snapshots/`. Anonymer Download benötigt keine Credentials.
Das Repository `https://jars.interlis.guru/mirror` liefert die INTERLIS-Bibliotheken.

Alle drei Wrapper verwenden Gradle 9.5.1, Java-21-Toolchains und `options.release=21`.
Die identische `gradle/dependencies.gradle` vereinheitlicht Spring Boot 4.1.1,
Spring AI 2.0.1, MCP SDK 2.0.1 und JSpecify 1.0.1. MCP verwendet Jackson 3; NETLs
persistierte JSON-Dateien und Fingerprints verwenden Jackson 2 aus dem Boot-BOM
(2.21.5 für Core/Databind). JSON-Regressionstests sichern diese Verträge.
Die Suite vergleicht ihre aufgelösten Dependencies mit den in beiden Modul-JARs
enthaltenen Manifesten `META-INF/mcp/*-module.json` und stoppt bei Abweichungen.

## Lokal entwickeln

In den Einzelrepos bleiben `./gradlew bootJar`, die bisherigen CLI-/JAR-Pfade sowie
die Test-Einstiegspunkte erhalten. Die Suite konsumiert standardmässig veröffentlichte
Snapshots. Nur der explizite Schalter ersetzt sie durch die benachbarten Checkouts:

```sh
# Im mcp-suite-Repo, Geschwister interlis-mcp und netl-mcp:
./gradlew -PuseLocalModules=true check bootJar
./gradlew -PuseLocalModules=true buildImage
```

`buildImage` baut zuerst das ausführbare JAR und danach das lokale JVM-Image.
`buildJvmImage` bleibt bei INTERLIS als Alias verfügbar. Native-Plugin, Native-Tasks,
Native-Dockerstufen und Native-CI-Publikation sind deaktiviert; Reflection-Metadaten bleiben erhalten.

Module lokal in ein isoliertes Maven-Repository publizieren, zum Beispiel in INTERLIS:

```sh
./gradlew :interlis-mcp-module:publishMavenJavaPublicationToVerificationRepository
python3 tools/verify-module.py --repository build/verification-repository \
  --artifact interlis-mcp-module --version 0.1.0-SNAPSHOT
```

Dieser externe Maven-Consumer verwendet einen leeren Cache, eigene Settings und dieselben
Boot-/Spring-AI-/MCP-BOMs wie die drei Anwendungen. Maven-Hostanwendungen importieren diese
BOMs ebenfalls; Dependency-Management eines Bibliotheks-POMs wird von Maven nicht an
Consumer vererbt. Direkte Bibliotheks-Dependencies werden mit ihren aufgelösten Versionen publiziert.
Er kompiliert gegen die öffentliche Modulkonfiguration, löst POM-Transitives auf,
prüft deren Versionen sowie Java-21-Bytecode und lädt Sources/Javadoc. Er verwendet
weder lokale Gradle-Substitution noch `mavenLocal()`.

## CI und Registries

`.github/workflows/main.yml` führt die Prüfungen aus und baut kanonische Artefakte einmal.
Die Architekturjobs laden dasselbe Anwendungs-JAR, bauen auf nativen amd64-/arm64-Runnern
und prüfen HTTP, STDIO, parallele Antworten und die Übereinstimmung des Image-JARs.
Die getesteten Images werden exportiert und nach Erfolg beider Architekturen unverändert
veröffentlicht. INTERLIS führt zusätzlich den vollständigen STDIO-Katalogtest aus.
NETL verwendet das eigene synthetische Compose-Lab für Schema-, Job-, Rechte-, Sperr- und
Recovery-Tests. Die Suite prüft ihren Katalog gegen beide veröffentlichten Einzelimages.

Pull Requests veröffentlichen nichts. Publikation erfolgt nur bei erfolgreichen
Main-Pushes oder `workflow_dispatch` auf Main. Snapshot-Module der Einzelrepos werden
vor den zugehörigen Images veröffentlicht. Danach werden JAR, POM, Sources/Javadoc und
Transitives erneut mit frischem Cache vom öffentlichen Maven-Repository heruntergeladen.
Beim ersten Rollout zuerst beide Einzelrepos, anschliessend die Suite veröffentlichen.
Suite-Rebuilds werden durch Änderungen im Suite-Repo oder manuell ausgelöst;
repoübergreifende Trigger sind momentan nicht eingerichtet.

| Anwendung | Docker Hub | GHCR | Versionsbasis |
| --- | --- | --- | --- |
| INTERLIS | `sogis/interlis-mcp` | `ghcr.io/edigonzales/interlis-mcp` | `0.0` |
| NETL | `sogis/netl-mcp` | `ghcr.io/edigonzales/netl-mcp` | `0.4` |
| Suite | `sogis/mcp-suite` | `ghcr.io/edigonzales/mcp-suite` | `0.0` |

Tags: `<basis>.<github.run_number>-<github.run_attempt>`, zusätzlich Architektur-Tags
`-amd64` und `-arm64`. Beide Registries werden auf Multiarch-Manifeste geprüft, bevor
`latest` aktualisiert wird. `interlis-mcp-jvm` bleibt ein Alias desselben INTERLIS-JVM-Images.
GHCR verwendet den kleingeschriebenen Repository-Owner; OCI-Labels verknüpfen jedes Image
mit Source-Repository, Version und Commit.

Secrets in beiden Einzelrepos: `MAVEN_USERNAME`, `MAVEN_PASSWORD`, `DOCKER_USERNAME`,
`DOCKER_PASSWORD`. Die Suite benötigt nur die Docker-Secrets. Maven-Credentials können
lokal über die gleichnamigen Umgebungsvariablen oder die Gradle-Properties
`mavenRepoUser`/`mavenRepoPassword` bereitgestellt werden. In CI publiziert Gradle mit
`-PpublishCanonicalArtifacts=true` ausschliesslich die zuvor geprüften Modul-Artefakte.
GHCR verwendet das eingebaute `GITHUB_TOKEN` mit `packages: write`. Neu angelegte
GHCR-Pakete beim ersten Deployment auf **Public** stellen und anonymen Image-Zugriff prüfen.

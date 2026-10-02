# netl-mcp

Deterministische lokale Schema-Werkzeuge für das Themenintegration Lab, mit Java 21 und Spring AI.
CLI und MCP verwenden denselben Anwendungskern. Keine LLM-Anfragen im Server selbst.

## Build und Verwendung

```sh
./gradlew test bootJar
bin/netl --workspace ../themenintegration-lab schema list demo/standorte --json
bin/netl --workspace ../themenintegration-lab schema plan demo/standorte edit --json
bin/netl --workspace ../themenintegration-lab schema create demo/standorte edit --json
bin/netl --workspace ../themenintegration-lab schema inspect demo/standorte edit --json
bin/netl --workspace ../themenintegration-lab mcp
```

JDK 21 für den Build; `JAVA_HOME` wird vom Launcher berücksichtigt. `NETL_WORKSPACE` kann alternativ
zum Startup-Argument verwendet werden. Ein Workspace wird pro Serverprozess festgelegt, nicht je Tool-Aufruf.
Ohne Angabe gilt das aktuelle Verzeichnis. STDOUT des MCP-Prozesses enthält ausschliesslich JSON-RPC.

Eine eigenständige synthetische Compose-Testumgebung liegt unter `tests/compose/`. Das frühere benachbarte Lab ist für Tests nicht erforderlich.
Es gibt keine Laufzeitabhängigkeit von `gretljobs`, `schema-jobs` oder `interlis-mcp`.

## API

| Tool | Parameter | Verhalten |
| --- | --- | --- |
| `schema_list` | `theme` | Konfiguration lesen, offline möglich |
| `schema_plan` | `theme`, `schema`, optional `operation`, `refreshModels` | Effektive Konfiguration, Herkunft der Defaults und lokale Voraussetzungen |
| `schema_create` | `theme`, `schema` | Nur fehlendes Schema erstellen; Wiederholung ist ein No-op bei passendem Stand |
| `schema_inspect` | `theme`, `schema` | Strukturaufnahme und Vergleich mit dem letzten erfolgreichen Lauf |
| `schema_recreate` | `theme`, `schema`, `planToken` | Expliziter Neuaufbau eines verwalteten Schemas |
| `schema_drop_previous` | `theme`, `schema`, `planToken` | Explizites Löschen von Version n-1 mit verwalteten Rollen |
| `config_context` | `theme` | Manifest, Revision, Modellpfade, Profile und Regeln lesen |
| `config_validate` | `theme`, `manifest` | Vollständigen Entwurf offline prüfen |
| `config_save` | `theme`, `manifest`, `expectedRevision` | Validieren und mit Revisionsprüfung atomar speichern |

`theme` hat die Form `amt/thema`; `schema` ist der Manifest-Identifier, kein frei gewählter physischer Name.
Alle Operationen liefern JSON-Objekte. Fachliche und technische Fehler sind über `status`, `code` und `message`
erkennbar; nicht allein anhand des MCP-Protokollstatus entscheiden. CLI und MCP liefern dieselben Objekte.
Die CLI beendet sich für Fehler und blockierte/abweichende Zustände mit 1, für Syntaxfehler mit 2.

## Implementierung

- `Configuration`: strikte Manifest-/Override-Prüfung, lokale Modelldateien und Fingerprints.
- `SchemaService`: Ablauf, Zustandsdateien, Wiederholungsregeln und Fehlerprotokolle.
- `LocalRuntime`: ausschliesslich die dedizierte Docker-Lab-Umgebung; JDBC auf Loopback-Ports im Hostbetrieb bzw. auf edit-db/pub-db im Container-Netzwerk.
- `Database`: normalisierte Katalogaufnahme und PostgreSQL-Advisory-Lock pro Schema.
- `Application` / `SchemaTools`: CLI-/STDIO-Adapter.
- `SerialStdioTransport`: serialisiert ausgehende Antworten als Workaround für einen reproduzierten
  SDK-2.0.1-STDIO-Fehler (`Failed to enqueue message`) bei parallelen Tool-Aufrufen. Die Tool-Ausführung
  bleibt parallel möglich. Unit-Test und paralleler STDIO-Smoke-Test sichern dieses Verhalten ab.
- `module/src/main/resources/runner`: gemeinsame Schema-/Grant-Logik, im NETL-Image ausgeführt und im JAR gehasht.
- `runtime/Dockerfile`: abgeleitetes `netl/gretl:0.4.0`; Herkunft aus schema-jobs und Lizenz unter `runtime/`.
- `profiles.json`, `schemas-v1.schema.json`, `schemas-v2.schema.json`: Lab-Profile und Editor-Schemas.

Manifest: `themes/<amt>/<thema>/schemas.json`, `formatVersion: 2`, `schemas: [...]`.
Einträge enthalten `ident`, `baseName`, `database`, `models`, `modelFiles`, `profile`, optional `schemaVersion`,
`overrides`, `modelRepositories`, `roleSuffix`, `schemaComment`, `sqlFiles` (views/postscript/stdcols/grants).
Physischer Name: baseName_vN, ohne Version baseName. Format 1 bleibt mit vollständig ausgeschriebenem `name` lesbar.
Formatwechsel müssen Ziel und Rollen erhalten; Versionswechsel im Format 2 erlauben parallele Schema-Versionen.
Unbekannte Felder, doppelte Identifier/Ziele, falsche Typen und Workspace-Ausbrüche werden abgewiesen.
Lokale Dateien stehen explizit in `modelFiles`; optionale `modelRepositories` im Format 2 lösen
importierte Abhängigkeiten auf. Hauptmodelle bleiben lokal. Basenames müssen eindeutig sein.
Der eigentliche Schemaimport sucht ausschliesslich im vorbereiteten, eingefrorenen Modellverzeichnis.

Die Profile setzen LV95, Geometrie-/FK-Indizes, FK-/Unique-/Zahlen-/Text-/Datumsprüfungen,
Enum-Tabellen, lesbare Enum-Namen, Metainformationen und `strokeArcs=true`.
`lab-edit-v1` setzt `nameByTopic=true`, `lab-pub-v1` setzt `nameByTopic=false`.
Alle anderen Optionen verwenden die Defaults der gebundenen GRETL-/ili2pg-Version.
`defaultSrsCode` ist eine Zeichenkette, andere explizite Optionen sind boolesch.

Runner: GRETL `3.2.861`, ili2pg `5.5.1`, ili2c `5.6.8`; PostGIS-Image `18-3.6`.
PostGIS und das GRETL-Basisimage sind per Digest gebunden. Das lokal gebaute NETL-Image wird per
Runner-Ressourcenhash gegen das JAR geprüft. Änderungen erfordern Neubau von Image und JAR.
Der Smoke-Test am 17.09.2026 bestätigte PostgreSQL `18.6` / PostGIS `3.6.4`.
Java 21 läuft auf dem Host; das GRETL-Image verwendet seine eigene Java-Laufzeit.

## Prüfungen

```sh
./gradlew test
# Synthetisches Lab starten, siehe tests/compose/README.md:
export NETL_HOST_WORKSPACE="$PWD/tests/compose/workspace"
mkdir -p "$NETL_HOST_WORKSPACE/.netl"
chmod -R a+rwX "$NETL_HOST_WORKSPACE"  # ausschliesslich synthetische Testdaten
docker compose -f tests/compose/compose.yaml up -d --build --wait edit-db pub-db gretl
./gradlew integrationTest
# Alternativer Lab-Pfad:
./gradlew integrationTest -Dnetl.workspace=/absolute/path/to/themenintegration-lab
./gradlew bootJar
python3 scripts/mcp_smoke.py tests/compose/workspace --create --compose-file tests/compose/compose.yaml
```

Die Integrationstests verwenden temporäre Themen unter `themes/tests/` und Schemas mit `netl_it_`-Präfix.
Sie testen tatsächliche Schemaimporte, lokale Modelländerungen, DB-Drift, unbekannte Schemas,
Sperren, Compilerfehler und Timeout. Nur eigene Testschemas werden aufgeräumt; Laufprotokolle bleiben im Lab.

## Bewusste Grenzen

Nur ein dediziertes Compose-Projekt auf einem Rechner; feste lokale Ports und Containerzuordnung.
Kein generischer öffentlicher Docker-, SQL- oder Gradle-Executor. Kein allgemeiner Schema-Drop, keine automatische Reparatur,
Migration, Publikation oder Datenvalidierung. `MATCHING` bestätigt die Übereinstimmung mit einem
aufgezeichneten erfolgreichen Lauf, keine vollständige fachliche Korrektheit.
Eine fehlende/defekte Zustandsdatei führt nicht zur Übernahme oder Überschreibung bestehender Schemas.
Für destruktiven Reset und Startanleitung siehe das Lab-README.


## Konfigurationsvorbereitung und expliziter Neuaufbau

Das Lab verwendet separate Agenten für Konfiguration und Ausführung.
`schema_plan` akzeptiert optional `operation=create|recreate|drop-previous`; Standard bleibt `create`.

Zusätzliche CLI-Aufrufe:
```text
netl config context THEME
netl config validate THEME DRAFT_JSON
netl config save THEME DRAFT_JSON EXPECTED_REVISION
netl schema plan THEME SCHEMA recreate
netl schema recreate THEME SCHEMA PLAN_TOKEN
netl schema plan THEME SCHEMA drop-previous
netl schema drop-previous THEME SCHEMA PLAN_TOKEN
```

Alle Befehle akzeptieren `--workspace PATH` und `--json`.
Konfigurationsvalidierung funktioniert offline und kompiliert keine Modelle. Speichern validiert erneut,
bewahrt bestehende Identifier/Ziele, prüft die Inhaltsrevision (`ABSENT` bei einer neuen Datei), sperrt
konkurrierende Werkzeugschreibzugriffe und ersetzt das Manifest atomar. Auditdaten liegen unter
`.netl/config/`. Modelldateien müssen innerhalb ihres Themenverzeichnisses liegen.

Neuaufbau benötigt einen ausdrücklichen Auftrag und ein einmaliges Plan-Token. Nur verwaltete Ziele
mit passendem Laufnachweis für Workspace, Thema und Identifier sind zulässig. Der Plan führt eine
zurückgerollte, geschützte DROP-Probe aus und schreibt ein Token unter `.netl/plans/`; anders als
der Standardplan ist diese Variante nicht rein lesend. Der tatsächliche DROP verwendet ebenfalls
einen transaktionslokalen Event-Trigger, der Kaskaden ausserhalb des Ziels blockiert. Interne
TOAST-Speicherobjekte des Ziels sind ausgenommen. Das benötigt die privilegierte Lab-Rolle.
Weder Event-Trigger noch Hilfsfunktion bleiben nach Commit oder Rollback bestehen.

Die Advisory Lock umfasst Löschen und Import. Frühere Laufprotokolle bleiben unter `.netl/runs/`.
Ein fehlgeschlagener Import nach Löschung kann die alten Daten nicht wiederherstellen;
Inspection liefert `INCOMPLETE`. Es gibt keine automatische Wiederholung oder Migration.
Die Bedeutung von `schema_create` bleibt unverändert.
Das Lab-README beschreibt Agentenaufträge, Zustände und den vollständigen Akzeptanzablauf.

## Persistenter Runner und Rollen

Das Image wird mit `docker build -f runtime/Dockerfile -t netl/gretl:0.4.0 .` gebaut.
Compose im Lab erledigt dies über `docker compose up -d --build --wait`.
Die gemeinsame Logik läuft per `docker exec`, Benutzer 1001, Daemon und festem JVM-/Gradle-Cache.
Eine Workspace-Dateisperre umfasst den gesamten Auftrag. Eine hinterlassene Aktivitäts- oder
Recovery-Markierung blockiert neue Aufträge nach einem Prozessabbruch; kein blindes Wiederholen.
Timeout oder fehlerhafter Runner-Exit stoppt den dedizierten Container, beendet ausschliesslich
markierte Datenbanksitzungen und startet den Container wieder. Erfolgreiche Läufe behalten den Daemon.

Die Runtime kann kopierte Jobprojekte und das feste Schemaprojekt ausführen. Laufdaten enthalten
die Daemon-Identität. Die Schema-Integrationstests schreiben eine gemessene
Kalt-/Warmlauf-Gegenüberstellung nach `.netl/acceptance/daemon-reuse.json` im Lab.

Schema-Rollen werden atomar mit dem Schema neu angelegt. Gleichnamige bestehende Rollen blockieren.
Standardrechte und danach themenspezifische Grants folgen auf Import und Konfiguration.
`roles.json` protokolliert die erzeugten Rollen-OIDs; `permissions.json` protokolliert ACLs,
Rollenattribute und Mitgliedschaften. Neuaufbau und Vorgängerlöschung prüfen diese OIDs und lassen
PostgreSQL die verbleibenden Rollenabhängigkeiten prüfen. Kein `DROP OWNED`, keine automatische Adoption.
Weitere Spezialrollen bleiben extern verwaltet. SQL-Hooks sind vertrauenswürdiger Code, keine Sandbox.
Legacy-Läufe ohne Rollenbeleg erhalten bei Inspection `roleManagement=LEGACY_UNVERIFIED`.

`drop-previous` entfernt exakt n-1, nie automatisch die letzte existierende Version. v1 und
unversionierte Schemas werden abgewiesen. Der Plan bindet aktuelle Konfiguration, Ziel und dessen
Inspection; die Ausführung verbraucht das Token und protokolliert auch Fehlschläge.

## GRETL-Datenumbaujobs (0.4.0)

CLI: `job context THEME`, `job validate|test|plan|status THEME JOB`,
`job confirm THEME JOB EXPECTATIONS_REVISION`, `job run THEME JOB PLAN_TOKEN`.
Entsprechende MCP-Tools tragen den Prefix `job_`. Zusätzlich gibt es getrennte
`job_write_transform`- und `job_write_test`-Werkzeuge für delegierte Autoren/Prüfer.
Bestätigung setzt eine ausdrückliche Benutzerentscheidung voraus, keine LLM-Selbstfreigabe.

Job-Artefakte liegen im Lab unter `themes/<theme>/jobs/<job>/`; vollständiger Vertrag,
Demo und Agentenablauf stehen in `../themenintegration-lab/docs/jobs.md`.
`JobConfiguration` validiert Dateien und getrennte Transformations-/Erwartungsrevisionen;
`JobService` orchestriert isolierte Fixtures, Db2Db-Wiederholung und tokengebundene lokale Läufe;
`JobChecks` führt generische und fachliche Verletzungsabfragen unabhängig vom Gradle-Prozess aus.
Ein erfolgreicher Prozess ist keine fachliche Abnahme. Ohne Bestätigung: `GENERIC_ONLY`.
Bei nachgelagertem Assert-Fehler: `FAILED`, möglicherweise bereits veränderte Zieldaten,
kein behaupteter Rollback und kein automatischer Retry.

`./gradlew test bootJar` und `./gradlew integrationTest --tests '*JobIntegrationTest'`.
Tests verwenden ausschliesslich eigene lokale synthetische Schemas und temporäre DML-Rollen.
Der neue Runner-/Init-Fingerprint macht alte Schema-Nachweise sichtbar DRIFTED, ohne automatische
Adoption oder Neuerstellung. Sicherheitsgrenze: Gradle-Code im lokalen Lab ist vertrauenswürdig,
nicht sandboxed. Kein Produktionsbetrieb.

## Externe Modellabhängigkeiten (0.4.0)

Manifestformat 2 erlaubt pro Schema `modelRepositories`, eine geordnete Liste von HTTP-/HTTPS-URLs,
zum Beispiel `["https://geo.so.ch/models/"]`. Fehlendes Feld oder `[]` bedeutet rein lokalen Betrieb;
Format 1 erhält keine Repository-Option. `modelFiles` enthält eigene lokale Hauptmodelle und optional
lokale Abhängigkeiten. Die unter `models` gewählten Hauptmodelle müssen dort definiert sein.
Konfigurationsvalidierung bleibt offline; `VALID` beweist keine Auflösung oder Kompilierung.

Vor einem Schemaimport bereitet der gebundene ili2c den transitiven Modellstand vor und kompiliert ihn.
Priorität: lokale Dateien, wiederverwendete Abhängigkeiten, konfigurierte Repositories. ili2c folgt auch
Repository-Verweisen; aufgesuchte URLs werden protokolliert. Jede externe Auflösung hat einen eigenen
Cache, ohne stille Verwendung alter Downloads. Gradles `--offline` betrifft Gradle-Abhängigkeiten,
nicht die ausdrückliche Modellauflösung. Der Import selbst erhält nur die eingefrorenen lokalen Dateien.
Modelle und Nachweise liegen ausserhalb von Git unter `.netl/model-runs/`; die Schema-Läufe archivieren
zusätzlich `db-models.json` aus `t_ili2db_model`. Dateien können mehrere Modelle enthalten.

Ein neues Schema-Ziel löst Abhängigkeiten neu auf. Ein Neuaufbau verwendet dokumentierte Abhängigkeiten
wieder und ergänzt neu benötigte Imports; vorhandene Abhängigkeiten werden nicht still aktualisiert.
Für eine ausdrücklich gewünschte Aktualisierung:

```sh
bin/netl --workspace ../themenintegration-lab schema plan demo/standorte edit recreate --refresh-models --json
```

MCP: `schema_plan(theme, schema, operation="recreate", refreshModels=true)`.
Der Plan kompiliert den gewählten Modellstand vor dem DROP und bindet ihn an das einmalige Token.
`schema_recreate` löst nichts erneut auf. Eine fehlgeschlagene Vorbereitung lässt das bestehende Schema
unverändert. Fehler nennen den Vorbereitungslog; bei fehlgeschlagener erstmaliger Erstellung wird ein
FAILED-Nachweis gespeichert und weiteres gewöhnliches create blockiert. Fehlende Legacy-Kopien erfordern
`refreshModels`; beschädigte Nachweise erfordern Untersuchung. Kein automatischer Neuaufbau oder Retry.

Inspection fragt keine Repositories ab. Sie vergleicht lokale Eingaben, Struktur, Rechte und gespeicherte
Modellinhalte (ohne `importDate`). Repository-Änderungen allein ergeben keinen Drift; geänderte
DB-Modellinhalte ergeben DRIFTED, beschädigte Snapshot-Dateien MODEL_EVIDENCE_INVALID.
MATCHING bleibt ein Vergleich mit dem erfolgreichen Lauf, keine semantische Verifikation.
Jobtests verwenden dokumentierte Modellstände oder bereiten für noch fehlende Ziele eigene vor.
Lokale Jobpläne verlangen exakt die getesteten Quell-/Ziel-Modellstände; Abweichungen erfordern einen neuen
Test. Die fachliche Benutzerbestätigung wird durch die Auflösung niemals ersetzt.

Kontrollierte Integrationstests verwenden ein eigenes HTTP-Repository mit synthetischen Modellen.
Der Runner erreicht den Testserver standardmässig über `host.docker.internal`; bei anderer Docker-
Netzwerkumgebung `-Dnetl.modelTestHost=<vom Container erreichbarer Host>` setzen.

## Module, Docker und HTTP

Das Root-Projekt bleibt die Standalone-Anwendung samt CLI und `build/libs/netl-mcp.jar`.
Fachcode und Ressourcen liegen unter `module/`. Das Modul
`ch.so.agi:netl-mcp-module:0.4.1-SNAPSHOT` wird mit POM, Sources und Javadoc auf
[jars.interlis.guru](https://jars.interlis.guru/snapshots/) veröffentlicht.
Eine Hostanwendung importiert `ch.so.agi.netl.NetlMcpModuleConfiguration`.
Die [MCP-Suite](https://github.com/edigonzales/mcp-suite) importiert zusätzlich INTERLIS
und bietet beide Module in einer JVM an.

```sh
./gradlew buildImage
# Lokaler HTTP-Server; bestehende mcp-Aufrufe bleiben STDIO:
bin/netl --workspace tests/compose/workspace mcp --spring.profiles.active=http
# Image mit offline lesbarem Workspace, HTTP auf Loopback:
docker run --rm -p 127.0.0.1:8080:8080 \
  -v "$PWD/tests/compose/workspace:/workspace:ro" -e NETL_WORKSPACE=/workspace \
  sogis/netl-mcp:latest
# STDIO:
docker run --rm -i -e SPRING_PROFILES_ACTIVE=stdio \
  -v "$PWD/tests/compose/workspace:/workspace:ro" -e NETL_WORKSPACE=/workspace \
  ghcr.io/edigonzales/netl-mcp:latest
```

Schema-/Jobausführung aus einem Container benötigt zusätzlich `NETL_RUNTIME_MODE=container`,
`NETL_HOST_WORKSPACE` (absoluter Hostpfad), einen schreibbaren Workspace, Zugriff auf den
Docker-Socket sowie das gemeinsame Compose-Netzwerk. Der Hostpfad wird für die Prüfung
des Runner-Mounts verwendet; `NETL_WORKSPACE` benennt den internen Pfad. Alle JDBC-Zugriffe,
auch temporäre Jobrollen, verwenden dieselben Laufzeiteinstellungen. Docker-CLI ist im Image
enthalten. Der persistente GRETL-Runner bleibt ein eigener Dienst; Image-/Projekt-/Mount-/Hashprüfungen,
Sperren und Recovery bleiben aktiv. [Vollständiges Container-Beispiel](tests/compose/README.md).

HTTP verwendet WebMVC, SYNC, Streamable HTTP und `/mcp` auf Port 8080; NETLs fachliche
120-Sekunden-Fristen bleiben erhalten. Native-Builds werden nicht ausgeführt.
Die Images für amd64 und arm64 werden aus demselben geprüften JAR gebaut und auf
Docker Hub sowie GHCR veröffentlicht. Main-Pushes und manuelle Main-Läufe veröffentlichen;
PRs prüfen ausschliesslich. [Build und Veröffentlichung](docs/MODULES.md).

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

Die vollständige Compose-Umgebung und der OpenCode-Agent liegen im benachbarten `themenintegration-lab`.
Es gibt keine Laufzeitabhängigkeit von `gretljobs`, `schema-jobs` oder `interlis-mcp`.

## API

| Tool | Parameter | Verhalten |
| --- | --- | --- |
| `schema_list` | `theme` | Konfiguration lesen, offline möglich |
| `schema_plan` | `theme`, `schema`, optional `operation` | Effektive Konfiguration, Herkunft der Defaults und lokale Voraussetzungen |
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
- `LocalRuntime`: ausschliesslich die dedizierte Docker-Lab-Umgebung; JDBC nur auf festen Loopback-Ports.
- `Database`: normalisierte Katalogaufnahme und PostgreSQL-Advisory-Lock pro Schema.
- `Application` / `SchemaTools`: CLI-/STDIO-Adapter.
- `SerialStdioTransport`: serialisiert ausgehende Antworten als Workaround für einen reproduzierten
  SDK-2.0.1-STDIO-Fehler (`Failed to enqueue message`) bei parallelen Tool-Aufrufen. Die Tool-Ausführung
  bleibt parallel möglich. Unit-Test und paralleler STDIO-Smoke-Test sichern dieses Verhalten ab.
- `src/main/resources/runner`: gemeinsame Schema-/Grant-Logik, im NETL-Image ausgeführt und im JAR gehasht.
- `runtime/Dockerfile`: abgeleitetes `netl/gretl:0.2.0`; Herkunft aus schema-jobs und Lizenz unter `runtime/`.
- `profiles.json`, `schemas-v1.schema.json`, `schemas-v2.schema.json`: Lab-Profile und Editor-Schemas.

Manifest: `themes/<amt>/<thema>/schemas.json`, `formatVersion: 2`, `schemas: [...]`.
Einträge enthalten `ident`, `baseName`, `database`, `models`, `modelFiles`, `profile`, optional `schemaVersion`,
`overrides`, `roleSuffix`, `schemaComment`, `sqlFiles` (views/postscript/stdcols/grants).
Physischer Name: baseName_vN, ohne Version baseName. Format 1 bleibt mit vollständig ausgeschriebenem `name` lesbar.
Formatwechsel müssen Ziel und Rollen erhalten; Versionswechsel im Format 2 erlauben parallele Schema-Versionen.
Unbekannte Felder, doppelte Identifier/Ziele, falsche Typen und Workspace-Ausbrüche werden abgewiesen.
Alle Modellabhängigkeiten sind explizit lokal aufzuführen. Basenames müssen eindeutig sein.
Der Compiler sucht nur im kopierten Modellverzeichnis.

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
# Lab vorher starten, siehe dessen README:
./gradlew integrationTest
# Alternativer Lab-Pfad:
./gradlew integrationTest -Dnetl.workspace=/absolute/path/to/themenintegration-lab
./gradlew bootJar
python3 scripts/mcp_smoke.py ../themenintegration-lab --create
```

Die Integrationstests verwenden temporäre Themen unter `themes/tests/` und Schemas mit `netl_it_`-Präfix.
Sie testen tatsächliche Schemaimporte, lokale Modelländerungen, DB-Drift, unbekannte Schemas,
Sperren, Compilerfehler und Timeout. Nur eigene Testschemas werden aufgeräumt; Laufprotokolle bleiben im Lab.

## Bewusste Grenzen

Nur ein dediziertes Compose-Projekt auf einem Rechner; feste lokale Ports und Containerzuordnung.
Kein generischer öffentlicher Docker-, SQL- oder Gradle-Executor. Kein HTTP-Server, kein allgemeiner Schema-Drop, keine automatische Reparatur,
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

Das Image wird mit `docker build -f runtime/Dockerfile -t netl/gretl:0.2.0 .` gebaut.
Compose im Lab erledigt dies über `docker compose up -d --build --wait`.
Die gemeinsame Logik läuft per `docker exec`, Benutzer 1001, Daemon und festem JVM-/Gradle-Cache.
Eine Workspace-Dateisperre umfasst den gesamten Auftrag. Eine hinterlassene Aktivitäts- oder
Recovery-Markierung blockiert neue Aufträge nach einem Prozessabbruch; kein blindes Wiederholen.
Timeout oder fehlerhafter Runner-Exit stoppt den dedizierten Container, beendet ausschliesslich
markierte Datenbanksitzungen und startet den Container wieder. Erfolgreiche Läufe behalten den Daemon.

Die interne Runtime kann weitere Tasknamen ausführen; öffentlich sind weiterhin nur Schema-Operationen
verfügbar. Laufdaten enthalten die Daemon-Identität. Die Integrationstests schreiben eine gemessene
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

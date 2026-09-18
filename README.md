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
| `schema_plan` | `theme`, `schema` | Effektive Konfiguration, Herkunft der Defaults und lokale Voraussetzungen |
| `schema_create` | `theme`, `schema` | Nur fehlendes Schema erstellen; Wiederholung ist ein No-op bei passendem Stand |
| `schema_inspect` | `theme`, `schema` | Strukturaufnahme und Vergleich mit dem letzten erfolgreichen Lauf |

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
- `src/main/resources/runner`: fester GRETL-Job, als Ressource mit dem Server ausgeliefert.
- `profiles.json` und `schemas-v1.schema.json`: versionierte Lab-Profile und Editor-Schema.

Manifest: `themes/<amt>/<thema>/schemas.json`, `formatVersion: 1`, `schemas: [...]`.
Einträge enthalten `ident`, `name`, `database`, `models`, `modelFiles`, `profile`, optional `overrides`.
Unbekannte Felder, doppelte Identifier/Ziele, falsche Typen und Workspace-Ausbrüche werden abgewiesen.
Alle Modellabhängigkeiten sind explizit lokal aufzuführen. Basenames müssen eindeutig sein.
Der Compiler sucht nur im kopierten Modellverzeichnis.

Die Profile setzen LV95, Geometrie-/FK-Indizes, FK-/Unique-/Zahlen-/Text-/Datumsprüfungen,
Enum-Tabellen, lesbare Enum-Namen, Metainformationen und `strokeArcs=true`.
`lab-edit-v1` setzt `nameByTopic=true`, `lab-pub-v1` setzt `nameByTopic=false`.
Alle anderen Optionen verwenden die Defaults der gebundenen GRETL-/ili2pg-Version.
`defaultSrsCode` ist eine Zeichenkette, andere explizite Optionen sind boolesch.

Runner: GRETL `3.2.861`, ili2pg `5.5.1`, ili2c `5.6.8`; PostGIS-Image `18-3.6`.
Die Docker-Images sind zusätzlich per Digest gebunden und werden in den Laufdaten dokumentiert.
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
Kein generischer Docker-, SQL- oder Gradle-Executor. Kein HTTP-Server, kein Schema-Drop, keine Reparatur,
Migration, Publikation oder Datenvalidierung. `MATCHING` bestätigt die Übereinstimmung mit einem
aufgezeichneten erfolgreichen Lauf, keine vollständige fachliche Korrektheit.
Eine fehlende/defekte Zustandsdatei führt nicht zur Übernahme oder Überschreibung bestehender Schemas.
Für destruktiven Reset und Startanleitung siehe das Lab-README.

# Eigenständiges synthetisches NETL-Lab

Dieses Lab enthält ausschliesslich synthetische Modelle, Fixtures und Erwartungen.
Es verwendet das dedizierte Compose-Projekt `themenintegration-lab`, Loopback-Ports
55431/55432 und einen separaten persistenten GRETL-Runner. Ein vorhandenes Projekt
`gretljobs` wird nicht verwendet. Das alte benachbarte Lab ist nicht erforderlich.

Im NETL-Repository:

```sh
export NETL_HOST_WORKSPACE="$PWD/tests/compose/workspace"
mkdir -p "$NETL_HOST_WORKSPACE/.netl"
chmod -R a+rwX "$NETL_HOST_WORKSPACE"  # nur die synthetische Testumgebung
# Datenbanken und Runner starten; das MCP-Image wird separat gebaut:
docker compose -f tests/compose/compose.yaml up -d --build --wait edit-db pub-db gretl
./gradlew test integrationTest bootJar buildImage
python3 scripts/mcp_smoke.py tests/compose/workspace --create --compose-file tests/compose/compose.yaml
python3 tools/netl-container-check.py --image sogis/netl-mcp:latest --workspace tests/compose/workspace
```

Die JVM-Integrationstests verwenden temporäre Themen/Schemas; geprüft werden reale
Schemaimporte, Modellauflösung, Drift, Berechtigungen, Jobs, Sperren, Wiederverwendung
des Daemons und Timeout-Recovery. Der Container-Test ermittelt unter Linux die Docker-Socket-GID automatisch
(`--socket-gid`/`DOCKER_SOCKET_GID` für einen ausdrücklichen Override; Docker Desktop standardmässig 0).
Er prüft zusätzlich die Datenbank-
DNS-Namen, den Unterschied zwischen Host- und Container-Workspace, temporäre JDBC-Rollen
und einen tatsächlichen angenommenen GRETL-Transfer über HTTP.
Die Prüfungen werden nacheinander ausgeführt, da sie denselben Runner-Lock verwenden.

Für den regulären MCP-Container (HTTP auf `http://127.0.0.1:18080/mcp`):

```sh
export MCP_IMAGE=sogis/netl-mcp:latest
# Linux: GID des Docker-Sockets; bei Docker Desktop nötigenfalls Backend-GID wählen.
export DOCKER_SOCKET_GID=$(stat -c '%g' /var/run/docker.sock) # Linux
# macOS Docker Desktop: in der Regel DOCKER_SOCKET_GID=0
export DOCKER_SOCKET_PATH=/var/run/docker.sock
NETL_HOST_WORKSPACE="$PWD/tests/compose/workspace" \
  docker compose -f tests/compose/compose.yaml --profile server up -d mcp
```

`NETL_WORKSPACE=/workspace` bezeichnet den internen Mount. `NETL_HOST_WORKSPACE`
bezeichnet den absoluten Hostpfad zum gleichen Workspace. Der GRETL-Runner mountet
**dessen `.netl`-Unterverzeichnis** nach `/workspace`; NETL vergleicht den Docker-
Mount-Source mit `NETL_HOST_WORKSPACE/.netl`, ohne den Hostpfad im MCP-Container aufzulösen.
`NETL_RUNTIME_MODE=container` aktiviert JDBC über `edit-db:5432` und `pub-db:5432`;
der Hostbetrieb behält `127.0.0.1:55431/55432` als Defaults.
`NETL_DOCKER_PROJECT` legt das streng geprüfte Compose-Projekt fest.

Docker-CLI ist in NETL und Suite enthalten. Der Socket muss unter `/var/run/docker.sock`
mit passenden Gruppenrechten erreichbar sein. Der MCP-Prozess und Runner laufen als
UID 1001; der Workspace und `.netl` müssen für diese UID schreibbar sein. Die offenen
Rechte oben sind nur für die isolierten synthetischen Fixtures vorgesehen. Für eigene
Workspaces die Eigentümer-/Gruppenrechte passend zu UID 1001 einrichten.
Images, laufende Container, Compose-Projekt/-Dienst, Runner-Mount und Ressourcenhashes
werden weiterhin geprüft; ein anderer Workspace oder falsches Image wird abgewiesen.

Für die Suite dasselbe Lab und denselben Mount verwenden:

```sh
export MCP_IMAGE=sogis/mcp-suite:latest
NETL_HOST_WORKSPACE="$PWD/tests/compose/workspace" \
  docker compose -f tests/compose/compose.yaml --profile server up -d --force-recreate mcp
python3 tools/netl-container-check.py --kind suite --image sogis/mcp-suite:latest --workspace tests/compose/workspace
```

Für STDIO mit dem Container dieselben Netzwerk-, Workspace-, Hostpfad- und Socket-
Optionen angeben, zusätzlich `-i -e SPRING_PROFILES_ACTIVE=stdio`; keinen TTY aktivieren.
GRETL bleibt ein eigener Dienst und wird bei erfolgreichen Jobs wiederverwendet.
Bei Timeout bestätigt NETL dessen Stopp, beendet markierte Datenbanksitzungen und
startet ihn wieder; es wiederholt den Job nicht automatisch. Recovery-Marker blockieren
neue Ausführungen, solange eine Beendigung nicht nachgewiesen ist.

Lab beenden:

```sh
NETL_HOST_WORKSPACE="$PWD/tests/compose/workspace" \
  docker compose -f tests/compose/compose.yaml --profile server down --volumes
```

Nur dieses Testprojekt wird beendet; Protokolle und Zustandsdateien unter `.netl`
bleiben zur Diagnose erhalten und sind von Git ausgeschlossen.

# NETL runtime 0.2.0

Derived from the digest-pinned GRETL 3.2.861 image. Build from the netl-mcp root:

    docker build -f runtime/Dockerfile -t netl/gretl:0.2.0 .

Schema workflow and standard grants are adapted from sogis/schema-jobs,
commit c7ab6baa41d8268b46382e753606c54cf40811de, shared/schema,
shared/privileges and shared/development_tasks (MIT; see SCHEMA-JOBS-LICENSE).
No production topics, recipients, credentials or downloads are bundled.

Differences: explicit physical names resolved from manifest v1/v2, exclusively
local copied models, explicit copied SQL hooks, atomic initial schema/role
creation without dropping existing roles, no development-data download, no
implicit grant to a privileged development user. Guarded removal of managed
schemas and role OIDs belongs to NETL's orchestration layer. Standard grants
apply to existing tables and sequences, not future objects. Topic SQL is trusted
code reviewed outside the restricted agents; it must not alter unrelated objects
or replace the two managed schema roles. Extra roles remain externally managed.

All jobs use netl-run in one persistent container and a single workspace lock.
Successful jobs retain their daemon. Timeout kills the dedicated container,
confirms it stopped, and restarts it without retrying the job. A recovery marker
blocks further execution if stopping could not be confirmed.

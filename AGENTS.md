# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Joget DX8/DX9 plugin (OSGi bundle) that provides a single custom Form Binder,
`WorkflowFormBinderWithAuditTrail`, which stores data as audit trail data when a form is saved. It
wraps Joget's built-in `WorkflowFormBinder`: on `store()`, it diffs the previous row (n-1) against
the incoming row (n), builds a JSON + plain-text diff of the changed fields, and writes that diff as
a new row into a separate "audit trail" form/table, foreign-keyed to the parent record. The parent
save then proceeds as normal via `super.store(...)`.

## Build

```bash
mvn clean package
```

Produces `target/form-store-binder-audit-trail-<version>.jar`, an OSGi bundle built with the Felix
`maven-bundle-plugin` (see `Bundle-Activator` / `Export-Package`/`Private-Package` in
[pom.xml](pom.xml)). Deploy by dropping the jar into a running Joget instance's plugin directory
(e.g. `wflow/app_plugins`).

Compiler target is Java 1.7 (`pom.xml`), so avoid newer language features even though the dev JDK
may be newer.

The `wflow-core` dependency (`provided` scope) must be resolvable — it's expected in the local
`~/.m2` repository (built/installed from the main `jw-community` Joget repo) since it isn't on
Maven Central.

## Tests

`mvn test` / `mvn clean package` runs JUnit via surefire (also bound to `integration-test` phase).
There are currently no test sources under `src/test` — add them there following standard Maven
layout if adding tests.

## Architecture

- [Activator.java](src/main/java/org/joget/marketplace/Activator.java) — OSGi `BundleActivator`
  that registers `WorkflowFormBinderWithAuditTrail` as an OSGi service on bundle start. This is the
  plugin registration point; a new plugin class would need to be registered here too.
- [WorkflowFormBinderWithAuditTrail.java](src/main/java/org/joget/marketplace/WorkflowFormBinderWithAuditTrail.java)
  — the entire plugin logic:
  - `load()` delegates straight to `WorkflowFormBinder.load()` (no custom behavior).
  - `store()` is where the audit trail is built:
    1. Reads the "before" row set from `formData.getLoadBinderData(element)` (the n-1 state) and
       compares it field-by-field against the incoming `rows` (n) using `entriesDiffering()` — a
       hand-rolled, null-safe replacement for Guava's `Maps.difference(...).entriesDiffering()`.
       Guava is deliberately **not** used here: although it's pulled in transitively (`provided`
       scope via `wflow-core`), it isn't declared in the OSGi `Import-Package`/embedded deps, so
       calling into it throws `NoClassDefFoundError` at runtime. Don't reintroduce a Guava/Apache
       Commons dependency for diffing without also fixing the bundle's `Import-Package`.
    2. For each changed field, resolves its label and, if the field has select options (via
       `formData.getOptionsBinderData(...)` or the element's own `options` property), maps
       raw values to human-readable labels (`mapValuesToLabels()`, handles multi-value
       semicolon-separated fields).
    3. Accumulates both a `JSONArray` of structured diffs and a plain-text diff string.
    4. If there were changes (or `tracksEverything` is enabled), writes a new row to the configured
       audit trail form's table via `appService.storeFormData(...)`, with a fresh UUID as its ID and
       the parent's primary key stored under the configured foreign-key column.
  - All plugin behavior is driven by properties configured in the form binder's UI (see below);
    there is no other server-side configuration.

- [WorkflowFormBinderWithAuditTrail.json](src/main/resources/properties/form/WorkflowFormBinderWithAuditTrail.json)
  defines the plugin's property editor (shown in the Joget Form Builder when selecting this
  binder). Key properties, all read in `store()` via `getPropertyString(...)`:
  - `formDefId` — the *current* form being audited; informational context for whoever configures
    the binder (not read directly in `store()`).
  - `auditTrailFormId` / `jsonDataField` / `textualDataField` — which form/table receives the audit
    row, and which of its columns hold the JSON diff and text diff respectively.
  - `fieldMappings` — a grid of `from`/`to` field-ID pairs; each row copies that field's current
    value from the parent form verbatim into the named column on the audit trail row (no diffing).
    Applied before the dedicated columns above/below are set, so a mapping can never clobber them.
    This is the deterministic, opt-in replacement for a single hardcoded `from`/`to` field (plus an
    `includeCopiedFieldInDiff` toggle to exclude it from the diff) that this plugin used to have, and
    for a copy-everything-that-matches behavior that briefly existed as an accidental side effect of
    `auditRow.putAll(rows.get(0))` during the file-upload bugfix (see git history) — that call is
    intentionally not used; only explicitly configured fields are ever copied.
  - `foreignKey` — the column on the audit trail form that stores the parent record's primary key.
  - `tracksEverything` — if checked, an audit row is written on every save even when nothing
    changed; if unchecked, audit rows are only written when at least one tracked field differs.
  - Labels/descriptions for these properties live in
    [WorkflowFormBinderWithAuditTrail.properties](src/main/resources/messages/form/WorkflowFormBinderWithAuditTrail.properties)
    (Joget's `@@key@@` message-bundle convention — keep JSON property `label`/`description` keys and
    this properties file in sync when adding/renaming a property).

## Notes

- `APP_DataTrailDemo-*.jwa` at the repo root is an exported Joget app demonstrating this binder —
  useful as a manual-testing reference (import into a Joget instance) but not part of the build.
- This plugin is one of many independent plugins under the `jogetoss` GitHub org that share the same
  single-plugin-per-repo, Felix-bundle Maven layout — don't assume shared build tooling beyond
  `pom.xml` in this repo.

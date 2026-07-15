# cloud-itonami-isic-0899

Open Business Blueprint for **ISIC Rev.4 0899**: Other mining and
quarrying n.e.c. — an ISIC Wave 3 (production/mining) operations-
coordination actor per ADR-2607121000. Back-office and coordination
workflow for quarry-extraction sites, modeled closely on
`cloud-itonami-isic-0893`'s (Extraction of salt) governed-actor
discipline.

**Maturity: `:implemented`** — QuarryOpsAdvisor ⊣ QuarrySiteGovernor as
a langgraph-clj StateGraph (`intake → advise → govern → decide →
commit/hold`, human-approval interrupt). All source `.cljc` (portable
to JVM / ClojureScript / GraalVM), no JVM-only interop.

## Scope: a residual (n.e.c.) mineral-extraction category

ISIC 0899 "Other mining and quarrying n.e.c." is a **residual**
category covering mineral-extraction activity not classified elsewhere
in division 08 — distinct from sibling categories already implemented
in this fleet (0892 peat, 0893 salt, 0721 uranium and thorium ores).
Typical n.e.c. product lines include gemstones, quartz, feldspar, and
industrial abrasives.

This actor's **chosen concrete illustration** of the n.e.c. category is
**quartz/feldspar quarrying for industrial abrasives** — grinding and
blasting media, ceramics, and glass-batch feedstock. The coordination
layer (extraction-record logging, extraction/blasting scheduling
proposals, environmental-concern flagging, shipment coordination)
generalizes to any single-commodity quarry site in this residual
category; quartz/feldspar is documented here as the worked example.

## CRITICAL: Scope Exclusions

This actor **DOES NOT** and **NEVER WILL**:

- **Direct extraction-equipment control** — blast/drill-pattern
  sequencing, excavator/loader operation, crusher/conveyor control,
  haul-truck dispatch control, bench sequencing.
- **Environmental-permit-issuing-authority decisions** — permit
  issuance/suspension, license suspension, or compliance enforcement.

This actor is a **quarry-extraction SITE OPERATIONS COORDINATION**
actor. It is **NOT** a direct extraction-equipment control authority,
and it is **NOT** an environmental-permit-issuing authority. It
**only** coordinates back-office operations: extraction-record logging
(volume/quality-grade), extraction/blasting **scheduling proposals**
(never blast execution itself), environmental-concern flagging (dust,
blast vibration, land-reclamation delay — always routed to a human),
and outbound-shipment coordination. Every proposal the advisor drafts
carries `:effect :propose` — never a direct actuation — and
`quarryops.governor` independently re-scans every proposal's content
for the excluded scope areas above, regardless of op or confidence.

## Operations

Closed proposal-op allowlist (`quarryops.governor/allowed-ops`), all
`:effect :propose`:

- `:log-extraction-record` — extraction-volume/quality-grade data logging
- `:schedule-extraction-operation` — extraction/blasting scheduling proposal
- `:flag-environmental-concern` — surface a dust/blast-vibration/land-
  reclamation concern — **ALWAYS escalates**
- `:coordinate-shipment` — outbound material shipment coordination

**HARD invariants** (always `:hold`, never human-overridable):

1. **Site/permit unverified** — the target site record must exist AND
   be independently `:registered?`/`:verified?` in the store before
   any proposal for it may commit or even escalate.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/value touches
   extraction-equipment-control or an environmental-permit-issuing-
   authority decision, is a permanent, un-overridable block. Evaluated
   unconditionally on every proposal.

**ESCALATE** (always human sign-off, when the governor is otherwise clean):

- `:flag-environmental-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

## Rollout phases (`quarryops.phase`)

Phase 0 (read-only) → 1 (extraction-record logging, approval-gated) →
2 (adds extraction-scheduling + shipment coordination, approval-gated)
→ 3 (supervised auto: extraction-record/schedule-extraction-operation/
coordinate-shipment may auto-commit when governor-clean and
confident). `:flag-environmental-concern` is deliberately absent from
every phase's `:auto` set — a permanent structural fact, not a rollout
milestone still to come — matching `quarryops.governor`'s own
`always-escalate-ops` independently.

## Development

```bash
clojure -M:test   # run the full suite
clojure -M:run    # walk the demo scenarios (quarryops.sim)
clojure -M:lint    # clj-kondo
```

AGPL-3.0-or-later, forkable by any qualified operator. Part of cloud-itonami.

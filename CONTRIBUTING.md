# Contributing to cloud-itonami-isic-0899

Contributions should preserve the actor's scope: quarry-extraction site
operations coordination only, with CRITICAL exclusions of direct
extraction-equipment control and environmental-permit-issuing-authority
decisions (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Direct extraction-equipment control — blast/drill-pattern sequencing,
  excavator/loader operation, crusher/conveyor control, haul-truck
  dispatch control, bench sequencing.
- Environmental-permit-issuing-authority decisions (permit issuance/
  suspension, license suspension, compliance enforcement).

Contributions that cross these boundaries will be rejected.

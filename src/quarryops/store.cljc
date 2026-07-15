(ns quarryops.store
  "SSoT for the ISIC-0899 'Other mining and quarrying n.e.c.' SITE
  OPERATIONS-COORDINATION actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  ISIC 0899 is a residual/n.e.c. mineral-extraction category (distinct
  from sibling categories like 0892 peat, 0893 salt, 0721 uranium-
  thorium): gemstones, quartz, feldspar, abrasives, and other minerals
  not classified elsewhere. This actor's concrete illustration is
  quartz/feldspar quarrying for industrial abrasives (grinding/blasting
  media, ceramics, glass-batch feedstock) -- a representative n.e.c.
  product line, documented in README.md.

  This actor coordinates the BACK OFFICE of a quarry-extraction site. It
  never touches extraction-equipment control (blast/drill-pattern
  sequencing, excavator/loader operation, crusher/conveyor control, haul-
  truck dispatch control) or any environmental-permit-issuing-authority
  decision (permit issuance/suspension, license suspension, compliance
  enforcement) -- see `quarryops.governor`'s `scope-exclusion-
  violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `sites` directory keyed by `:site-id` STRING (never a
  keyword -- every lookup uses the string `:site-id` off the proposal,
  keying consistently on the string from the start avoids a known prior
  scaffold failure mode in a sibling actor).

  A registered/verified site-and-permit record must exist before ANY
  proposal for that site may ever commit or escalate --
  `quarryops.governor`'s `site-unverified-violations` re-derives this
  from the site's own `:registered?`/`:verified?` fields, never from
  proposal self-report, the SAME 'ground truth, not self-report'
  discipline every sibling actor's own governor uses.

  The ledger stays append-only: which site a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (site [s site-id] "Registered quarry-extraction site/permit record, or nil.
    Site map: {:site-id .. :name .. :mineral .. :registered? bool :verified? bool}.")
  (all-sites [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-sites [s sites] "replace/seed the site directory (map site-id->site)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained site directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline.
  Illustrates the chosen concrete n.e.c. product line: quartz/feldspar
  quarrying for industrial abrasives, ceramics and glass-batch
  feedstock."
  []
  {:sites
   {"quarry-site-1" {:site-id "quarry-site-1" :name "Ridgeline Quartz Quarry"
                      :mineral :quartz :registered? true :verified? true}
    "quarry-site-2" {:site-id "quarry-site-2" :name "Feldspar Hollow Quarry"
                      :mineral :feldspar :registered? true :verified? true}
    "quarry-site-3" {:site-id "quarry-site-3" :name "Southridge Abrasive-Grade Quartz Quarry (permit lapsed)"
                      :mineral :quartz :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ site-id] (get-in @a [:sites site-id]))
  (all-sites [_] (sort-by :site-id (vals (:sites @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn seed-db
  "A MemStore seeded with the demo site directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `sites` map (site-id string ->
  site map) -- the primary test/dev entry point. `sites` may be empty
  (an unregistered-everywhere store)."
  [sites]
  (->MemStore (atom {:sites (or sites {}) :ledger [] :coordination-log []})))

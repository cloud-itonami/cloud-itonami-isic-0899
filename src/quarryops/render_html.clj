(ns quarryops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had a
  hand-written `docs/index.html` product face but NO operator console and
  no generator at all.

  EVERY id, number, disposition, violation rule and violation detail on
  the generated page is REAL output of this repo's own actor stack --
  `quarryops.advisor` (proposal) -> `quarryops.governor` (independent
  censor) -> `quarryops.phase` (rollout gate) -> `quarryops.store`
  (append-only ledger + committed coordination log), driven through the
  langgraph-clj StateGraph compiled by `quarryops.operation/build` via
  `langgraph.graph/run*`. Nothing on the page is typed by hand:

    * the site table comes from `store/all-sites` (the seeded
      `store/demo-data` directory -- `quarry-site-1`, `quarry-site-2`,
      `quarry-site-3`);
    * the committed rows come from `store/coordination-log`, including
      the `:approved-by` stamp the `:request-approval` node writes when a
      human resumes an escalated run;
    * the audit rows and every HARD-hold rule/detail string come from
      `store/ledger` (the governor's own `hold-fact` output);
    * even the action-gate table is DERIVED from the live constants
      `governor/allowed-ops`, `governor/always-escalate-ops` and
      `phase/phases` rather than described in prose, so it cannot drift
      away from the code it documents.

  The scenario in `run-demo!` was checked against `store/demo-data`
  before being written -- `quarryops.sim`'s ids happen to match the
  store, and the one id that does not (`quarry-site-9`) is deliberately
  absent so the unregistered-site HARD hold fires.

  DETERMINISTIC: no timestamps, no randomness, no set-iteration order in
  the page. Two consecutive runs produce byte-identical output (verified
  by diffing them).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [quarryops.advisor :as advisor]
            [quarryops.governor :as governor]
            [quarryops.operation :as op]
            [quarryops.phase :as phase]
            [quarryops.store :as store]))

;; ----------------------------- scenario -----------------------------

(def ^:private supervisor-phase-1
  {:actor-id "op-1" :actor-role :site-supervisor :phase 1})

(def ^:private supervisor-phase-3
  {:actor-id "op-1" :actor-role :site-supervisor :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "site-supervisor-1"}}
          {:thread-id tid :resume? true}))

(defn- direct-actuation-advisor
  "A deliberately compromised advisor that stamps `:effect :commit` onto
  an otherwise-normal proposal -- i.e. claims to actuate directly rather
  than propose. Exists solely so the governor's `:effect-not-propose`
  HARD block is exercised by a real run instead of asserted in prose
  (the same failure-mode hook `quarryops.sim` uses)."
  []
  (reify advisor/Advisor
    (-advise [_ st req] (assoc (advisor/infer st req) :effect :commit))))

(defn run-demo!
  "Seeds a fresh `store/seed-db`, builds the REAL OperationActor and runs
  a scenario that reaches every disposition this actor can produce.

  Clean full lifecycle on `quarry-site-1` (Ridgeline Quartz Quarry):
  an extraction record logged at phase 1 (assisted-extraction -- escalates
  to a human, who approves, then it commits), the same op re-run at phase 3
  (supervised-auto, governor-clean and high-confidence -> auto-commits), an
  extraction/blasting SCHEDULING proposal (auto-commits), an
  environmental-concern flag (ALWAYS escalates at every phase, by two
  independent layers -- `governor/always-escalate-ops` and its absence from
  every `phase/phases` `:auto` set -- approved by a human, then commits) and
  an outbound shipment coordination (auto-commits). `quarry-site-2`
  (Feldspar Hollow Quarry) runs a shorter clean pair.

  Four HARD holds, one per distinct governor rule -- none of these ever
  reaches a human, they are refused before the approval node exists:
    * `:site-unverified`   `quarry-site-9` is not in the site directory at all;
    * `:site-unverified`   `quarry-site-3` IS registered but its permit is
                            not `:verified?` (Southridge, permit lapsed) --
                            re-derived from the store record, never from the
                            proposal's own claim;
    * `:effect-not-propose` a compromised advisor stamps `:effect :commit`;
    * `:scope-excluded`     a proposal drifts into crusher-control /
                            blast-pattern / drill-pattern territory, which is
                            permanently outside this actor's charter;
    * `:op-not-allowed`     an op outside the closed four-op allowlist
                            (`:suspend-environmental-permit` -- an
                            environmental-permit-issuing-authority decision).

  Returns the store. Every field `render` reads off it is real governor /
  store output."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        compromised (op/build db {:advisor (direct-actuation-advisor)})]

    ;; --- quarry-site-1: full clean lifecycle ---
    (exec! actor "s1-log-p1"
           {:op :log-extraction-record :site-id "quarry-site-1"
            :patch {:tonnage 480 :grade "abrasive-A" :shift "day"}}
           supervisor-phase-1)
    (approve! actor "s1-log-p1")

    (exec! actor "s1-log-p3"
           {:op :log-extraction-record :site-id "quarry-site-1"
            :patch {:tonnage 510 :grade "abrasive-A" :shift "night"}}
           supervisor-phase-3)

    (exec! actor "s1-schedule"
           {:op :schedule-extraction-operation :site-id "quarry-site-1"
            :patch {:window "2026-08-18" :bench "bench-4"}}
           supervisor-phase-3)

    (exec! actor "s1-environment"
           {:op :flag-environmental-concern :site-id "quarry-site-1"
            :patch {:concern "residential-boundary dust plume after the bench-4 round"
                    :confidence 0.95}}
           supervisor-phase-3)
    (approve! actor "s1-environment")

    (exec! actor "s1-shipment"
           {:op :coordinate-shipment :site-id "quarry-site-1"
            :patch {:carrier "rail-co-1" :tonnage 990 :consignee "abrasives-plant-2"}}
           supervisor-phase-3)

    ;; --- quarry-site-2: clean pair ---
    (exec! actor "s2-log"
           {:op :log-extraction-record :site-id "quarry-site-2"
            :patch {:tonnage 320 :grade "ceramic-B" :shift "day"}}
           supervisor-phase-3)

    (exec! actor "s2-shipment"
           {:op :coordinate-shipment :site-id "quarry-site-2"
            :patch {:carrier "truck-co-3" :tonnage 320 :consignee "glass-batch-plant-1"}}
           supervisor-phase-3)

    ;; --- HARD holds: refused, never escalated to a human ---
    (exec! actor "h-unregistered"
           {:op :log-extraction-record :site-id "quarry-site-9"
            :patch {:tonnage 50}}
           supervisor-phase-3)

    (exec! actor "h-unverified"
           {:op :log-extraction-record :site-id "quarry-site-3"
            :patch {:tonnage 50}}
           supervisor-phase-3)

    (exec! compromised "h-direct-actuation"
           {:op :coordinate-shipment :site-id "quarry-site-1"
            :patch {:carrier "rail-co-1"}}
           supervisor-phase-3)

    (exec! actor "h-scope-drift"
           {:op :schedule-extraction-operation :site-id "quarry-site-1"
            :out-of-scope? true :patch {}}
           supervisor-phase-3)

    (exec! actor "h-op-not-allowed"
           {:op :suspend-environmental-permit :site-id "quarry-site-2"
            :patch {}}
           supervisor-phase-3)
    db))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-str
  "Print a keyword/value stably (namespaced keywords keep their namespace)."
  [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- sorted-pairs
  "Map -> `k=v` string with keys in a stable (name-sorted) order, so a
  hash-map's iteration order can never change the rendered bytes."
  [m]
  (->> (sort-by (comp kw-str key) m)
       (map (fn [[k v]] (str (kw-str k) "=" (if (string? v) v (pr-str v)))))
       (str/join ", ")))

(defn- ledger-for [ledger site-id]
  (filter #(= site-id (:site-id %)) ledger))

(defn- site-status-cell [ledger site-id]
  (let [facts (ledger-for ledger site-id)
        holds (filter #(= :governor-hold (:t %)) facts)
        commits (filter #(= :committed (:t %)) facts)]
    (cond
      (and (seq holds) (seq commits))
      (format "<span class=\"num\">%d</span> committed &middot; <strong>%d HARD hold</strong>"
              (count commits) (count holds))

      (seq holds)
      (format "<strong>%d HARD hold &middot; never reached a human</strong>" (count holds))

      (seq commits)
      (format "<span class=\"num\">%d</span> committed" (count commits))

      :else "no activity this run")))

;; ----------------------------- table rows -----------------------------

(defn- site-row [ledger {:keys [site-id name mineral registered? verified?]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc site-id) (esc name) (esc (kw-str mineral))
          (if registered? "registered" "<strong>not registered</strong>")
          (if verified? "verified" "<strong>NOT verified</strong>")
          (site-status-cell ledger site-id)))

(defn- gate-row
  "One row of the action-gate table, DERIVED from the live constants in
  `quarryops.governor` / `quarryops.phase` -- not a prose description."
  [op]
  (let [phase-ids (sort (keys phase/phases))
        first-write (first (filter #(contains? (:writes (get phase/phases %)) op) phase-ids))
        auto-at (first (filter #(contains? (:auto (get phase/phases %)) op) phase-ids))
        always? (contains? governor/always-escalate-ops op)]
    (format "        <tr><td><code>:%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc (kw-str op))
            (if first-write (str "phase " first-write) "never")
            (cond always? "<strong>never &middot; at any phase</strong>"
                  auto-at (str "phase " auto-at " (governor-clean &amp; confidence &ge; "
                               governor/confidence-floor ")")
                  :else "never")
            (if always?
              "ALWAYS human approval &mdash; governor <code>always-escalate-ops</code> and absence from every phase <code>:auto</code> set agree independently"
              "human approval until its auto phase; HARD governor violations are never approvable"))))

(defn- committed-row [{:keys [op site-id payload]}]
  (format "        <tr><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str op)) (esc site-id)
          (esc (sorted-pairs (dissoc payload :site-id :approved-by)))
          (if-let [by (:approved-by payload)]
            (str "<strong>" (esc by) "</strong>")
            "auto-commit")))

(defn- ledger-row [{:keys [t op site-id disposition basis violations confidence]}]
  (format "        <tr><td>%s</td><td><code>:%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td class=\"num\">%s</td></tr>"
          (if (= :governor-hold t)
            "<strong>governor-hold</strong>"
            (esc (kw-str t)))
          (esc (kw-str op)) (esc site-id)
          (esc (kw-str (or disposition "")))
          (esc (if (seq violations)
                 (str/join "; " (map #(str (kw-str (:rule %)) " — " (:detail %)) violations))
                 (str/join ", " (map kw-str basis))))
          ;; commit facts carry no confidence -- say so rather than
          ;; back-filling a number the store never recorded.
          (if (some? confidence) (esc confidence) "&mdash;")))

(defn- hard-rule-row
  "One distinct HARD rule the run actually exercised, with EVERY distinct
  governor detail string it produced and the sites/ops it fired on -- all
  read back out of the ledger, never restated. A rule that fired on more
  than one site (`:site-unverified` fires both on a site absent from the
  directory and on one present but not `:verified?`) shows both details,
  so the table cannot quietly speak for a case it did not exercise."
  [[rule facts]]
  (format "        <tr><td><code>:%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str rule))
          (esc (str/join ", " (distinct (map :site-id facts))))
          (esc (str/join ", " (distinct (map #(str ":" (kw-str (:op %))) facts))))
          (str/join "<br>"
                    (map esc (->> facts
                                  (mapcat :violations)
                                  (filter #(= rule (:rule %)))
                                  (map :detail)
                                  distinct)))))

(defn- hard-rule-groups
  "ledger -> [[rule [hold-fact ..]] ..] in first-occurrence order (stable
  -- never a hash-map iteration order)."
  [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)
        rules (distinct (mapcat #(map :rule (:violations %)) holds))]
    (for [r rules]
      [r (filter (fn [f] (some #(= r (:rule %)) (:violations f))) holds)])))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator-console document from a store `db` that has
  already been driven by `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        coordination (vec (store/coordination-log db))
        sites (store/all-sites db)
        holds (filter #(= :governor-hold (:t %)) ledger)
        approved (filter #(get-in % [:payload :approved-by]) coordination)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-0899 &middot; quarry site operations coordination</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Other mining and quarrying n.e.c. (ISIC 0899) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; equipment control and permit-authority decisions permanently out of scope</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run at a glance</h2>\n"
     "    <p class=\"subtitle\">Build-time generated from the real actor stack by <code>quarryops.render-html</code> (<code>clojure -M:dev:render-html</code>). Every id, number, disposition and violation string below is output of <code>quarryops.advisor</code> &rarr; <code>quarryops.governor</code> &rarr; <code>quarryops.phase</code> &rarr; <code>quarryops.store</code>, executed through the <code>langgraph</code> StateGraph in <code>quarryops.operation</code>. Nothing here is hand-typed.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Measure</th><th>Value</th></tr></thead>\n"
     "      <tbody>\n"
     (format "        <tr><td>Sites in the verified directory</td><td class=\"num\">%d</td></tr>\n" (count sites))
     (format "        <tr><td>Audit facts written to the append-only ledger</td><td class=\"num\">%d</td></tr>\n" (count ledger))
     (format "        <tr><td>Proposals committed to the coordination log</td><td class=\"num\">%d</td></tr>\n" (count coordination))
     (format "        <tr><td>&mdash; of which a human approved before commit</td><td class=\"num\">%d</td></tr>\n" (count approved))
     (format "        <tr><td><strong>HARD governor holds &mdash; refused, never escalated to a human</strong></td><td class=\"num\"><strong>%d</strong></td></tr>\n" (count holds))
     (format "        <tr><td>Distinct HARD rules exercised</td><td class=\"num\">%d</td></tr>\n" (count (hard-rule-groups ledger)))
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Quarry sites &amp; permits</h2>\n"
     "    <p class=\"subtitle\">A site record must exist AND be independently <code>:registered?</code> and <code>:verified?</code> in the store before any proposal for it may commit <em>or even escalate</em>. The governor re-derives this from the site record itself &mdash; never from the proposal's own claim.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Site</th><th>Name</th><th>Mineral</th><th>Registration</th><th>Permit</th><th>This run</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial site-row ledger) sites)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (QuarrySiteGovernor &times; rollout phase)</h2>\n"
     "    <p class=\"subtitle\">Derived from <code>governor/allowed-ops</code>, <code>governor/always-escalate-ops</code> and <code>phase/phases</code> at build time, so this table cannot drift from the code it documents. The op allowlist is closed: anything outside it is a HARD <code>:op-not-allowed</code> violation by construction.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>First writable</th><th>Auto-commit</th><th>Otherwise</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map gate-row (sort-by kw-str governor/allowed-ops))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds this run</h2>\n"
     "    <p class=\"subtitle\">A HARD violation is permanent and un-overridable: the run terminates at <code>:hold</code> and the approval node is never reached, so no human is ever offered the chance to wave it through. Rule names and detail strings below are the governor's own <code>hold-fact</code> output.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Sites</th><th>Ops</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hard-rule-row (hard-rule-groups ledger))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Committed coordination log</h2>\n"
     "    <p class=\"subtitle\">The SSoT writes this run produced. <code>Approved by</code> is the <code>:approved-by</code> stamp the approval node adds when a human resumed an escalated run; <code>auto-commit</code> means the governor was clean, confidence cleared the floor and the op was auto-eligible at that phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Site</th><th>Payload</th><th>Approved by</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map committed-row coordination)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (append-only)</h2>\n"
     "    <p class=\"subtitle\">Every decision fact this scenario wrote, in order. Which site a proposal targeted, which op, on what basis and whether it committed or was held is always a query over this immutable log.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Site</th><th>Disposition</th><th>Basis / violation</th><th>Confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>Regenerate with <code>clojure -M:dev:render-html</code>. Deterministic &mdash; no timestamps or randomness in this page, so re-running against the same seed produces byte-identical output.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        ledger (store/ledger db)
        holds (filter #(= :governor-hold (:t %)) ledger)]
    (.mkdirs (.getParentFile (java.io.File. ^String out)))
    (spit out html)
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count (store/coordination-log db)) " committed proposals, "
                  (count holds) " HARD holds across "
                  (count (hard-rule-groups (vec ledger))) " distinct rules)"))))

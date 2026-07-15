(ns quarryops.governor
  "QuarrySiteGovernor -- the independent compliance layer that earns the
  QuarryOpsAdvisor the right to commit. The advisor has no notion of
  whether a site/permit record is actually registered and verified,
  whether its own proposed `:effect` secretly claims a direct actuation
  instead of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- SITE OPERATIONS
  COORDINATION only (extraction-record logging, extraction/blasting
  scheduling PROPOSALS, environmental-concern flagging, outbound-
  shipment coordination) for ISIC-0899 'Other mining and quarrying
  n.e.c.' sites (illustrated here by quartz/feldspar quarrying for
  industrial abrasives). It NEVER performs or authorizes:
    - direct extraction-equipment control (blast/drill-pattern
      sequencing, excavator/loader operation, crusher/conveyor control,
      haul-truck dispatch control)
    - environmental-permit-issuing-authority decisions (permit
      issuance/suspension, license suspension, compliance enforcement)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Site/permit unverified   -- the target site record must exist AND
                                    be independently confirmed
                                    `:registered?`/`:verified?` in the
                                    store before ANY proposal for it may
                                    commit or even escalate. Never trusts
                                    a proposal's own claim about the
                                    site -- re-derived from the site's
                                    own store record, the same 'ground
                                    truth, not self-report' discipline
                                    every sibling actor's governor uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST be
                                    `:propose`. Any other effect value
                                    is, by construction, a claim to
                                    directly actuate/commit outside
                                    governance -- HARD block, not merely
                                    low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op) whose
                                    op, rationale, summary, citations or
                                    draft value touches extraction-
                                    equipment-control or environmental-
                                    permit-issuing-authority decision
                                    territory is a HARD, PERMANENT block
                                    -- this actor's charter excludes that
                                    territory structurally, not as a
                                    rollout milestone. Evaluated
                                    UNCONDITIONALLY on every proposal,
                                    the same 'exercise the failure mode
                                    directly' discipline every sibling
                                    actor's own unconditional-evaluation
                                    checks establish. An op outside the
                                    closed four-op allowlist is the SAME
                                    failure mode (an advisor proposing
                                    something it was never authorized to
                                    propose) and is folded into this same
                                    check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op is
  `:flag-environmental-concern` -- ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is. `quarryops.phase` independently agrees:
  `:flag-environmental-concern` is never a member of any phase's `:auto`
  set either -- two layers, not one."
  (:require [clojure.string :as str]
            [quarryops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-extraction-record :schedule-extraction-operation
    :flag-environmental-concern :coordinate-shipment})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-environmental-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- direct extraction-equipment
  control or environmental-permit-issuing-authority decisions. Scanned
  across the proposal's op/summary/rationale/cites/value, never trusting
  the advisor's own framing of its intent. NOTE: bare 'blast'/'発破' are
  deliberately NOT terms here -- `:schedule-extraction-operation`'s own
  legitimate scope is 'extraction/blasting scheduling', so only
  specific execution/control PHRASES (blast pattern, drill pattern,
  bench sequencing, ...) mark the excluded direct-actuation territory,
  never the bare word 'blast'/'発破' by itself."
  ["blast pattern" "blast-pattern" "発破パターン"
   "blast sequencing" "blast-sequencing" "発破順序"
   "blast timing control" "blast-timing control" "発破タイミング制御"
   "drill pattern" "drill-pattern" "ドリルパターン"
   "drill-and-blast" "ドリルアンドブラスト"
   "excavator operation" "excavator-operation" "掘削機操作"
   "loader operation" "loader-operation" "積込機操作"
   "crusher control" "crusher-control" "破砕機制御"
   "conveyor control" "conveyor-control" "コンベア制御"
   "haul truck dispatch control" "haul-truck dispatch control" "ダンプトラック配車制御"
   "bench sequencing" "bench-sequencing" "ベンチ採掘順序"
   "permit issuance" "permit-issuance" "許可発行"
   "permit suspension" "permit-suspension" "許可停止"
   "license suspension" "license-suspension" "免許停止"
   "compliance enforcement" "compliance-enforcement" "コンプライアンス執行"
   "environmental permit decision" "environmental-permit decision" "環境許可決定"])

;; ----------------------------- checks -----------------------------

(defn- site-unverified-violations
  "The target site/permit record must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:site-id` claim without a store lookup."
  [{:keys [site-id]} st]
  (let [s (store/site st site-id)]
    (when-not (and s (:registered? s) (:verified? s))
      [{:rule :site-unverified
        :detail (str site-id " は未登録または未検証のsite/permitレコード -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches extraction-equipment-control or
  environmental-permit-issuing-authority territory, regardless of
  confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "採掘設備制御(発破/ドリルパターン/掘削機・破砕機・コンベア制御等)または環境許可発行機関の判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a QuarryOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [site-id (or (:site-id proposal) (:site-id request))
        hard (into []
                   (concat (site-unverified-violations {:site-id site-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :site-id    (:site-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})

(ns quarryops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean extraction-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-extraction, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then an extraction-scheduling request and
  a shipment-coordination request (also auto-commit clean at phase 3),
  then an environmental-concern flag (ALWAYS escalates, at any phase --
  approve, then commit), then HARD-hold scenarios: an unregistered
  site, a site registered but not yet verified, a proposal whose own
  `:effect` is not `:propose`, and a proposal that has drifted into the
  permanently-excluded extraction-equipment-control/environmental-
  permit-issuing-authority scope."
  (:require [langgraph.graph :as g]
            [quarryops.advisor :as advisor]
            [quarryops.store :as store]
            [quarryops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "site-supervisor-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        operator-phase-1 {:actor-id "op-1" :actor-role :site-supervisor :phase 1}
        operator-phase-3 {:actor-id "op-1" :actor-role :site-supervisor :phase 3}
        actor (op/build db)]

    (println "== log-extraction-record quarry-site-1 (phase 1, escalates -- human approves) ==")
    (println (exec-op actor "t1" {:op :log-extraction-record :site-id "quarry-site-1"
                                  :patch {:tonnage 480 :grade "abrasive-A" :shift "day"}} operator-phase-1))
    (println (approve! actor "t1"))

    (println "== log-extraction-record quarry-site-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-extraction-record :site-id "quarry-site-1"
                                  :patch {:tonnage 510 :grade "abrasive-A" :shift "night"}} operator-phase-3))

    (println "== schedule-extraction-operation quarry-site-2 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-extraction-operation :site-id "quarry-site-2"
                                  :patch {:window "2026-07-22" :bench "bench-4"}} operator-phase-3))

    (println "== coordinate-shipment quarry-site-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-shipment :site-id "quarry-site-1"
                                  :patch {:carrier "rail-co-1" :tonnage 480}} operator-phase-3))

    (println "== flag-environmental-concern quarry-site-2 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-environmental-concern :site-id "quarry-site-2"
                                 :patch {:concern "elevated dust plume near residential boundary" :confidence 0.95}} operator-phase-3)]
      (println r)
      (println "-- human site supervisor reviews & approves --")
      (println (approve! actor "t5")))

    (println "== log-extraction-record quarry-site-9 (unregistered site -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-extraction-record :site-id "quarry-site-9"
                                  :patch {:tonnage 50}} operator-phase-3))

    (println "== log-extraction-record quarry-site-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-extraction-record :site-id "quarry-site-3"
                                  :patch {:tonnage 50}} operator-phase-3))

    (println "== coordinate-shipment quarry-site-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer db req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :coordinate-shipment :site-id "quarry-site-1"
                                           :patch {:carrier "rail-co-1"}} operator-phase-3)))

    (println "== schedule-extraction-operation quarry-site-1, advisor drifts into crusher/blast-pattern scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :schedule-extraction-operation :site-id "quarry-site-1"
                                  :out-of-scope? true
                                  :patch {}} operator-phase-3))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))

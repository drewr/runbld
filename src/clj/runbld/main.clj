(ns runbld.main
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [environ.core :as environ]
            [runbld.build :as build]
            [runbld.notifications.email :as email]
            [runbld.notifications.slack :as slack]
            [runbld.java :as java]
            [runbld.opts :as opts]
            [runbld.process :as proc]
            [runbld.scheduler.middleware :as scheduler]
            [runbld.store :as store]
            [runbld.system :as system]
            [runbld.tests :as tests]
            [runbld.io :as io]
            [runbld.util.date :as date]
            [runbld.vcs.git :refer [checkout-commit]]
            [runbld.vcs.middleware :as vcs]
            [runbld.version :as version]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn really-die
  ([code]
   (really-die nil))
  ([code strmsg]
   (when strmsg
     (println strmsg))
   (shutdown-agents)
   ;; Give stdout a chance to finish.  Jenkins can wait.
   (Thread/sleep 5000)
   (System/exit code)))

(defn die
  ([code]
   (die code nil))
  ([code msg]
   (let [msg* (if msg
                (.trim (with-out-str (println msg))))]
     (really-die code msg*)
     ;; for tests when #'really-die is redefed
     msg*)))

(def make-opts
  (-> #'identity
      vcs/wrap-vcs-info
      build/wrap-build-meta
      scheduler/wrap-scheduler
      java/wrap-java
      system/wrap-system))

;; -main :: IO ()
(defn -main [& args]
  (try+
   (let [opts (make-opts
               (assoc
                (opts/parse-args args)
                :logger io/log))
         _ (io/log (version/string))
         _ (when (-> opts :build :last-success :checked-out)
             (let [b (build/find-build opts (-> opts :build :last-success :id))]
               (io/log "using last successful commit"
                       (-> b :vcs :commit-short)
                       "from"
                       (-> b :build :job-name) (-> b :id)
                       (-> b :process :time-start)
                       (date/human-duration
                        (date/iso-diff-secs
                         (date/from-iso
                          ;; notify on time-end because it makes more
                          ;; logical sense to report on the last
                          ;; completed build's end time, I think
                          (-> b :process :time-end))
                         (date/now)))
                       "ago")))
         _ (io/log ">>>>>>>>>>>> SCRIPT EXECUTION BEGIN >>>>>>>>>>>>")
         {:keys [opts process-result]} (proc/run opts)
         _ (io/log "<<<<<<<<<<<< SCRIPT EXECUTION END <<<<<<<<<<<<")
         {:keys [took status exit-code out-bytes err-bytes]} process-result
         _ (io/log (format "DURATION: %sms" took))
         _ (io/log (format "STDOUT: %d bytes" out-bytes))
         _ (io/log (format "STDERR: %d bytes" err-bytes))
         _ (io/log (format "WRAPPED PROCESS: %s (%d)" status exit-code))
         test-report (tests/report (-> opts :process :cwd))
         store-result (store/save! opts process-result test-report)
         slack-result (io/try-log (slack/maybe-send! opts (:addr store-result)))
         email-result (io/try-log (email/maybe-send! opts (:addr store-result)))
         ]

     (if (environ/env :dev)
       (assoc process-result
              :store-result store-result
              :email-result email-result
              :slack-result slack-result)
       (if (-> opts :process :inherit-exit-code)
         (die (-> store-result :build-doc :process :exit-code))
         (die 0))))

   (catch [:error :runbld.main/errors] {:keys [errors msg]}
     (die 3 msg))

   (catch [:error :runbld.opts/parse-error] {:keys [msg]}
     (die 2 msg))

   (catch [:error :runbld.opts/file-not-found] {:keys [msg]}
     (die 2 msg))

   (catch [:help :runbld.opts/version] {:keys [msg]}
     (die 0 msg))

   (catch [:help :runbld.opts/usage] {:keys [msg]}
     (die 0 msg))

   (catch [:help :runbld.opts/system] {:keys [msg]}
     (die 0 (with-out-str
              (clojure.pprint/pprint
               (into (sorted-map)
                     (system/inspect-system "."))))))

   (catch [:error :runbld.vcs.middleware/unknown-repo] {:keys [msg opts]}
     (io/log msg)
     (when (environ/env :dev)
       (io/log "DUMPING CONFIG for DEV=true")
       (io/log
        (with-out-str
          (clojure.pprint/pprint opts))))
     (die 1))

   (catch Exception e
     (die 1 e))))

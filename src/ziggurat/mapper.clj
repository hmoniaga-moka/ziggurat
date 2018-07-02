(ns ziggurat.mapper
  (:require [lambda-common.metrics :as metrics]
            [sentry.core :as sentry]
            [ziggurat.new-relic :as nr]
            [ziggurat.messaging.producer :as producer]
            [ziggurat.sentry :refer [sentry-reporter]])
  (:import (java.time Instant)))

(defn mapper-func [mapper-fn]
  (fn [message topic-entity]
    (let [new-relic-transaction-name (str topic-entity ".handler-fn")
          metric-namespace           (str topic-entity ".message-processing")]
      (nr/with-tracing "job" new-relic-transaction-name
        (try
          (let [start-time       (.toEpochMilli (Instant/now))
                return-code      (mapper-fn message)
                end-time         (.toEpochMilli (Instant/now))]
            (metrics/report-time (str topic-entity ".handler-fn-execution-time") (- end-time start-time))
            (case return-code
              :success (metrics/increment-count metric-namespace "success")
              :retry (do (metrics/increment-count metric-namespace "retry")
                         (producer/retry message topic-entity))
              :skip (metrics/increment-count metric-namespace "skip")
              :block 'TODO
              (throw (ex-info "Invalid mapper return code" {:code return-code}))))
          (catch Throwable e
            (producer/retry message topic-entity)
            (sentry/report-error sentry-reporter e (str "Actor execution failed for " topic-entity))
            (metrics/increment-count metric-namespace "failure")))))))

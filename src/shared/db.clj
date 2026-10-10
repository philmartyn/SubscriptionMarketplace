(ns shared.db
  "Small helpers shared by the module data-access namespaces.

  Every function takes the connectable first, like its call sites, so it works
  the same on a datasource and on a transaction handle.

  The result builder is applied on every call rather than configured once,
  because a transaction handle is a raw java.sql.Connection and does not inherit
  the datasource's builder-fn. next.jdbc would otherwise namespace result keys
  after their table - an inserted account comes back as #:accounts{:id 1} - and
  (:id row) would be nil. That failure is silent: the row is written, it is just
  keyed wrongly."
  (:require
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(def ^:private row-options
  "Unqualified lower-case keys, matching components.db.core's datasource."
  {:builder-fn rs/as-unqualified-lower-maps})

(defn one
  "Single row, or nil."
  [db query & [opts]]
  (jdbc/execute-one! db query (merge row-options opts)))

(defn many
  "All rows."
  [db query & [opts]]
  (jdbc/execute! db query (merge row-options opts)))

(defn affected
  "Rows changed by an UPDATE or DELETE.

  execute-one! with :return-keys returns the updated row for an UPDATE, which
  says nothing about whether anything matched, and returns nil both when nothing
  matched and when it did. The update count is what callers need in order to
  distinguish a first use from a replay."
  [db query]
  (:next.jdbc/update-count (first (many db query))))

;;; coercion
;;
;; Timestamps are passed around as java.time.Instant because that is what
;; clojure.core/time understands, and converted at the boundary because the
;; Postgres driver is happiest with java.sql.Timestamp.

(defn ->ts
  "A java.sql.Timestamp for an Instant."
  [instant]
  (java.sql.Timestamp/from instant))

(defn now-ts []
  (->ts (java.time.Instant/now)))

(defn expiry
  "An Instant n days from now.

  Instant/now takes a Clock, not a Duration, so the duration is added to the
  instant rather than passed to now/."
  [n]
  (.plus (java.time.Instant/now) (java.time.Duration/ofDays n)))

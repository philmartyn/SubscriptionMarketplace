(ns shared.telemetry
  "Thin wrapper over the OpenTelemetry API.

  The Java agent (the :otel alias) installs the SDK globally; without it,
  GlobalOpenTelemetry/getOrNoop returns a no-op implementation. That is why
  application code goes through here instead of touching the API directly: the
  same code is a no-op in the test suite, in any run with no collector, and
  never throws because telemetry is unavailable.

  Keep this surface small. Spans and counters cover what the current domain
  flows need; anything more should be justified before it is added."
  (:import
   (io.opentelemetry.api GlobalOpenTelemetry)
   (io.opentelemetry.api.common Attributes)
   (io.opentelemetry.api.metrics LongCounter Meter)
   (io.opentelemetry.api.trace Span StatusCode Tracer)))

(def ^:private instrumentation-name "submarket")

(defn open-telemetry
  "The global OpenTelemetry instance, or a no-op when no agent is attached."
  []
  (GlobalOpenTelemetry/getOrNoop))

(defn tracer
  ^Tracer []
  (.getTracer (open-telemetry) instrumentation-name))

(defn meter
  ^Meter []
  (.getMeter (open-telemetry) instrumentation-name))

(defn attributes
  "Build OTel Attributes from a flat map of keyword/string -> value.

  Values are stringified, which is all the callers need today. Public because
  with-span expands in the caller's namespace and so cannot reach a private
  var."
  ^Attributes [m]
  (if (seq m)
    (let [builder (Attributes/builder)]
      (doseq [[k v] m]
        (.put builder (name k) (str v)))
      (.build builder))
    (Attributes/empty)))

(defn start-span
  "Start a span. Public so the with-span macro can reach it."
  ^Span [span-name attributes-map]
  (-> (tracer)
      (.spanBuilder (str span-name))
      (.setAllAttributes (attributes attributes-map))
      (.startSpan)))

(defn end-span!
  "End a span, recording and marking an exception when there was one.

  Returns result so the macro can use it as the try body's value. StatusCode
  has no error(message) factory - it is the enum value ERROR plus a separate
  description - so the two-argument setStatus is the one to call."
  [^Span span result throwable]
  (if throwable
    (do
      (.recordException span throwable)
      (.setStatus span StatusCode/ERROR (str (.getMessage throwable)))
      (.end span))
    (do
      (.setStatus span StatusCode/OK)
      (.end span)))
  result)

(defmacro with-span
  "Run body inside a span named span-name.

  The span is made current for the body, so automatic spans opened below it
  (JDBC queries, outbound HTTP) nest under it rather than floating. An
  exception marks the span failed and is rethrown; a no-op span swallows
  nothing, so behaviour is identical with or without an agent.

  attributes-map is a flat map or nil."
  [span-name attributes-map & body]
  `(let [^Span span# (start-span ~span-name ~attributes-map)
         ^io.opentelemetry.context.Scope scope# (.makeCurrent span#)]
     (try
       (end-span! span# (do ~@body) nil)
       (catch Throwable e#
         (end-span! span# nil e#)
         (throw e#))
       (finally
         (.close scope#)))))

(defonce ^:private counters (atom {}))

(defn counter
  "The LongCounter for counter-name, built once per name and cached."
  ^LongCounter [counter-name]
  (let [k (str counter-name)]
    (or (get @counters k)
        (let [built (-> (meter)
                        (.counterBuilder k)
                        (.setUnit "1")
                        (.build))]
          (get (swap! counters #(if (contains? % k) % (assoc % k built))) k)))))

(defn incr!
  "Add amount (default 1) to the counter named counter-name.

  The two-argument arity takes attributes-map, because every caller that passes
  a second argument passes attributes; incrementing by more than one uses the
  three-argument arity.

  attributes-map is a flat map or nil. Counter names here become Prometheus
  series with a _total suffix, so auth.signup is scraped as auth_signup_total."
  ([counter-name] (incr! counter-name 1 nil))
  ([counter-name attributes-map] (incr! counter-name 1 attributes-map))
  ([counter-name amount attributes-map]
   (.add (counter counter-name) (long amount) (attributes attributes-map))))

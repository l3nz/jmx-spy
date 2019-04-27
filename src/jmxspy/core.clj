(ns jmxspy.core
  (:require [clojure.java.jmx :as jmx]
            [clojure.string :as str]
            [cli-matic.core :refer [run-cmd]]
            [clojure.spec.alpha :as s]
            [jmxspy.specs :as S]
            [orchestra.spec.test :as st]
            [cheshire.core :as json]
            [say-cheez.core :refer [capture-build-env-to]])
  (:gen-class))

;(capture-build-env-to BUILD)
(def BUILD {})

(def ^:dynamic *ERRORLOG* "")

(defn now []
  (.format
   (new java.text.SimpleDateFormat "yyyy-MM-dd HH:mm:ss")
   (java.util.Date.)))


(defn timerFn []
  (let [t0 (System/currentTimeMillis)]
    (fn []
      (- (System/currentTimeMillis) t0))))

(defn logErr
  "As we use STDOUT for JSON, we need to push notifications to a file."
  [& msg]

  (if (not (empty? *ERRORLOG*))

    (let [myMsg (apply str msg)
          fullMsg (str (now) ": " myMsg "\n")]
      (spit *ERRORLOG* fullMsg :append true))))

(defn string->attr
  "Transforms a string attribute that may include a
   sub.-attribute into a vector of [:attr :subattr] "
  [attributeName]

  (let [[_ attr subattr] (re-matches #"(.+)\.(.+)" attributeName)]
    (if attr ;; successful match
      [(keyword attr) (keyword subattr)]
      [(keyword attributeName) nil])))

(defn read-bean
  "Examples:

  With sub-attribute
  (read-bean \"java.lang:type=Memory\" \"HeapMemoryUsage.max\")

  " [bean attributeName]
  (let [[attr subattr] (string->attr attributeName)]
    (cond
      (nil? subattr)
      (jmx/read bean attr)

      :else
      (get (jmx/read bean attr) subattr))))

(defn read-bean-attr
  "Reads a bean by receiving our input structures:

  - a defaults map
  - the  bean itself

  Returns a map of the bean values merged with the defaults.
  If reading goes bad, the :value attribute is set to :ERR

  "
  [exraAttrs {:keys [bean attribute as]}]
  (let [v (try
            (read-bean bean attribute)
            (catch Exception e
              (do
                (logErr "Bean: '" bean "'  Attr: '" attribute "' Error: " e)
                :ERROR)))]

    (into exraAttrs {:metrics as :value v})))

(s/fdef
  read-bean-attr
  :args (s/cat :defaults map?
               :bean ::S/a-bean))


(defn mk-jmx-error-status
  "Builds the fake entry 'jmx_error_status' that ia set to 0
  if everything okay and 1 if anything went south.

  "
  [isOk? extraAttrs]

  (into extraAttrs
        {:metrics "jmx_error_status"
                    :value (if isOk? 0 1)}))

(defn beans-for
  "Turns a list of presets into a list of beans"
  [cfg presets-to-use]
  (set (flatten (map
                 (fn [x]
                   (get-in cfg [:presets x]))
                 presets-to-use))))

(s/fdef
 beans-for
 :args (s/cat :cfg ::S/CFG
              :presets (s/coll-of keyword?)))

(defn dump-all-beans
  "Dumps all attributes for a server into a list.
  It does not add the jmx-error-status but leaves :ERROR
  entries in for further removal."

  [cfg {:keys [extra-attrs polling]}]
  (let [allbeans (beans-for cfg polling)]
    (mapv (partial read-bean-attr extra-attrs) allbeans)))

(defn dump-server-maybe-remote
  "Dumps all benas on a server that is possibly remote.
  After each server, we remove all :ERROR entries and add
  a jmx_error_status entry.

  If connection is impossible, we just log the event
  and  add a jmx_error_status entry.

  "
  [cfg {:keys [creds extra-attrs] :as all}]

  (try
    (let [beans (if (empty? creds)
                  (dump-all-beans cfg all)

                  (jmx/with-connection creds
                                       (dump-all-beans cfg all)))

          have-errors? (some? (some #{:ERROR} (map :value beans)))
          error-free (filter #(not= :ERROR (:value %)) beans)]

      (conj error-free
            (mk-jmx-error-status (not have-errors?) extra-attrs)))

    (catch Exception e
      (do
        (logErr "Connection issue " creds ":" e)
        [(mk-jmx-error-status false extra-attrs)]))))

(defn dump-all-servers
  [{:keys [cfg errorlog]}]

  (binding [*ERRORLOG* errorlog]
    (let [_ (logErr "======= Starting ========")
          timer (timerFn)
          servers (:servers cfg)
          data (reduce into []
                       (map (partial dump-server-maybe-remote cfg) servers))
          sorted-data (sort-by :metrics data)
          _ (logErr "======= Ending: "
                    " took: " (timer) " ms -"
                    " # beans: " (count sorted-data)
                    " ==========")]

      (println
       (json/generate-string sorted-data {:pretty true})))))

(defn test-connection
  [{:keys [credentials bean attribute]}]

  (let [val

        (if (empty? credentials)
          (read-bean bean attribute)

          (jmx/with-connection credentials
            (read-bean bean attribute)))]

    (println "Bean: " bean " " attribute "=" val)))

(def CONFIGURATION
  {:app         {:command     "jmx-spy"
                 :description "Spy"
                 :version     (str
                               (get-in BUILD [:project :version])
                               " - built at "
                               (get-in BUILD [:project :built-at]))}
   :global-opts []
   :commands    [{:command     "json"
                  :short "j"
                  :description ["Prints JMX beans as JSON"]
                  :opts        [{:option "cfg"
                                 :as "EDN configuration"
                                 :type :ednfile}
                                {:option "errorlog"
                                 :as "EDN configuration"
                                 :type :string
                                 :default ""}]
                  :runs        dump-all-servers}

                 {:command     "test"
                  :short "t"
                  :description ["Tests a JMX connection"]
                  :opts        [{:option "credentials"
                                 :as "EDN credentials, es '{:host \"127.0.0.1\", :port 7666}'"
                                 :default '{} ':type :edn}
                                {:option "bean"
                                 :as "A bean"
                                 :default "java.lang:type=Memory"
                                 :type :string}
                                {:option "attribute"
                                 :as "The attribute to read"
                                 :default "HeapMemoryUsage.used"
                                 :type :string}]
                  :runs        test-connection}]})

(defn -main
  "This is our entry point.
  Just pass parameters and configuration.
  Commands (functions) will be invoked as appropriate."
  [& args]
  (run-cmd args CONFIGURATION))

(st/instrument)


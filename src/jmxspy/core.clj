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

(capture-build-env-to BUILD)

(def ^:dynamic *ERRORLOG* "")

(defn now []
  (.format
   (new java.text.SimpleDateFormat "yyyy-MM-dd HH:mm:ss")
   (java.util.Date.)))

(defn logErr
  [& msg]

  (if (not (empty? *ERRORLOG*))

    (let [myMsg (apply str msg)
          fullMsg (str (now) ": " myMsg "\n")]
      (spit *ERRORLOG* fullMsg :append true))))

(defn string->attr
  [attribute]

  (let [[_ a sa] (re-matches #"(.+)\.(.+)" attribute)]
    (if a ;; successful match
      [(keyword a) (keyword sa)]
      [(keyword attribute) nil])))

(defn read-bean
  "Examples:

  With sub-attribute
  (read-bean \"java.lang:type=Memory\" \"HeapMemoryUsage.max\")

  " [bn attribute]
  (let [[attr subattr] (string->attr attribute)]
    (cond
      (nil? subattr)
      (jmx/read bn attr)

      :else
      (get (jmx/read bn attr) subattr))))

(defn read-bean-attr [defaultMap {:keys [bean attribute as]}]
  (let [v (try
            (read-bean bean attribute)
            (catch Exception e
              (do
                (logErr "Bean: '" bean "'  Attr: '" attribute "' Error: " e)
                :ERROR)))]

    (into defaultMap {:metrics as :value v})))

(defn mkStatus
  [isOk? extraAttrs]

  (into extraAttrs {:metrics "jmx_error_status"
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

(defn dump-server
  [cfg {:keys [creds extra-attrs polling]}]
  (let [allbeans (beans-for cfg polling)]
    (mapv (partial read-bean-attr extra-attrs) allbeans)))

(defn dump-server-maybe-remote
  [cfg {:keys [creds extra-attrs] :as all}]

  (try
    (let [beans (if (empty? creds)
                  (dump-server cfg all)

                  (jmx/with-connection creds
                    (dump-server cfg all)))

          have-errors? (some? (some #{:ERROR} (map :value beans)))
          error-free (filter #(not= :ERROR (:value %)) beans)]

      (conj error-free (mkStatus (not have-errors?) extra-attrs)))

    (catch Exception e
      (do
        (logErr "Connection issue " creds ":" e)
        [(mkStatus false extra-attrs)]))))

(defn dump-all-servers
  [{:keys [cfg errorlog]}]

  (binding [*ERRORLOG* errorlog]
    (let [_ (logErr "======= Starting ========")
          servers (:servers cfg)
          data (reduce into []
                       (map (partial dump-server-maybe-remote cfg) servers))
          sorted-data (sort-by :metrics data)
          _ (logErr "======= Ending ==========")]

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


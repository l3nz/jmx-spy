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

;  The  log file to write to
(def ^:dynamic *ERRORLOG* "")

;  used when  running  in test mode.
(def ^:dynamic *PRINT-BEANS-ON-ACCESS* false)

(defn now []
  (.format
   (new java.text.SimpleDateFormat "yyyy-MM-dd HH:mm:ss")
   (java.util.Date.)))

(defn timerFn
  "This is a plain timer function.
  Every time you call the returned function,
  it gives back the number of ms since it was created."
  []
  (let [t0 (System/currentTimeMillis)]
    (fn []
      (- (System/currentTimeMillis) t0))))

(defn logErr
  "As we use STDOUT for JSON, we need to push notifications to a file.

  If we are printing beans on access,  we also want
   logging of any weirdness to STDOUT."
  [& msg]

  (if (true? *PRINT-BEANS-ON-ACCESS*)
    (println "ERR: " (apply str msg)))

  (if (not (empty? *ERRORLOG*))
    (let [myMsg (apply str msg)
          fullMsg (str (now) ": " myMsg "\n")]
      (spit *ERRORLOG* fullMsg :append true))))

(defn string->attr
  "Transforms a string attribute that may include a
   sub-attribute into a vector of [:attr :subattr] "
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
  (let [[attr subattr] (string->attr attributeName)
        _  (if (true? *PRINT-BEANS-ON-ACCESS*)
             (println "Accessing bean: " bean))]; 

    (cond
      (= "?" attributeName)
      (jmx/mbean bean)

      (nil? subattr)
      (jmx/read bean attr)

      :else
      (get (jmx/read bean attr) subattr))))

(defn read-bean-no-exceptions
  "Reads a bean.
   If there is an exception, returns :ERROR
   If the attribute to read is '?', returns 1

  "
  [bean attribute]
  (try
    (read-bean bean attribute)
    (catch Exception e
      (do
        (logErr "Bean: '" bean "'  Attr: '" attribute "' Error: " e)
        :ERROR))))

(defn isSearchableBean?
  "Is the bean passed by name a real name or a search pattern?"
  [beanName]
  (cond
    (str/includes? beanName "*") true
    :else false))

(defn sourcebean->bean
  " A source bean might be single or searchable, and it may or may not
  have an assigned name. "

  [{:keys [bean attribute as group-by] :as sb}]

  (-> sb
      (into {:searchable? (isSearchableBean? bean)})
      (into {:group-by (if (nil? group-by)
                         :first
                         group-by)})

      (into {:as (if (empty? as)
                   (str bean " " attribute)
                   as)})))

(s/fdef
 sourcebean->bean
 :args (s/cat :sb ::S/a-source-bean)
 :ret ::S/a-full-bean)

(defn find-all-beans
  "Given a pattern, returns all beans that match it.
  On errors, logs the error and returns no beans.
  Beans are returned in sorted order.
  "
  [pattern] (try

              (let [beans (sort
                           (map str (jmx/mbean-names pattern)))]

                (if (empty? beans)
                  (logErr "Search for " pattern " returned no matches"))

                beans)

              (catch Exception e
                (do
                  (logErr "Search for " pattern " raised error: " e)
                  []))))

(defn grouper
  "Groups "
  [group-by values]

  (try

    (condp = group-by

      ;  Count
      :count  (count values)

      ; Sum
      :sum   (reduce + 0 values)

      ; First
      :first (if (empty? values)
               :ERROR
               (first values)))

    (catch Exception e
      (do
        (logErr "Error grouping " group-by " values " values ": " e)
        :ERROR))))

(s/fdef
 grouper
 :args (s/cat :gb  ::S/group-by
              :vals sequential?))

(defn read-bean-attr
  "Reads a bean by receiving our input structures:

  - a defaults map
  - the  bean itself

  Returns a map of the bean values merged with the defaults.
  If reading goes bad, the :value attribute is set to :ERR

  "
  [exraAttrs {:keys [bean attribute as searchable? group-by]}]
  (let [beans (if searchable?
                (find-all-beans bean)
                [bean])
        vals (mapv #(read-bean-no-exceptions % attribute) beans)
        v (grouper group-by vals)]

    (into exraAttrs {:metrics as :value v})))

(s/fdef
 read-bean-attr
 :args (s/cat :defaults map?
              :bean ::S/a-full-bean))

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
  (let [source-beans (flatten (map
                               (fn [x]
                                 (get-in cfg [:presets x]))
                               presets-to-use))]

    (set (map sourcebean->bean source-beans))))

(s/fdef
 beans-for
 :args (s/cat :cfg ::S/CFG
              :presets (s/coll-of keyword?))
 :ret (s/coll-of ::S/a-full-bean))

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
    (let [_ (logErr "Server: " extra-attrs)
          beans (if (empty? creds)
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
  [{:keys [credentials bean attribute group-by]}]

  (binding [*PRINT-BEANS-ON-ACCESS* true]

    (let [fullBean (sourcebean->bean
                    {:bean bean
                     :attribute attribute
                     :group-by group-by})

          val          (if (empty? credentials)
                         (read-bean-attr {} fullBean)

                         (jmx/with-connection credentials
                           (read-bean-attr {} fullBean)))]

      (println "Bean: " bean " " attribute "=" val))))

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
                                 :as "A bean or a bean search expression"
                                 :default "java.lang:type=Memory"
                                 :type :string}
                                {:option "attribute"
                                 :as "The attribute to read ex. HeapMemoryUsage.used - use '?' if unsure"
                                 :default "?"
                                 :type :string}
                                {:option "group-by"
                                 :as "The grouping function"
                                 :default :first
                                 :type S/ALLOWED_GROUPING}]
                  :runs        test-connection}]})

(defn -main
  "This is our entry point.
  Just pass parameters and configuration.
  Commands (functions) will be invoked as appropriate."
  [& args]
  (run-cmd args CONFIGURATION))

(st/instrument)


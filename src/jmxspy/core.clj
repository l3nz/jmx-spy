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
            (catch Exception e "0"))]

    (into defaultMap {:metrics as :value v})))

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

(defn dump-server [cfg {:keys [creds extra-attrs polling]}]
  (let [allbeans (beans-for cfg polling)]
    (mapv (partial read-bean-attr extra-attrs) allbeans)))

(defn dump-server-maybe-remote [cfg {:keys [creds extra-attrs polling] :as all}]
  (if (empty? creds)
    (dump-server cfg all)

    (jmx/with-connection creds
      (dump-server cfg all))))

(defn dump-all-servers
  [{:keys [cfg]}]

  (let [servers (:servers cfg)
        data (reduce into []
                     (map (partial dump-server-maybe-remote cfg) servers))]

    (println (json/generate-string data))))

(def CONFIGURATION
  {:app         {:command     "jmx-spy"
                 :description "Spy"
                 :version     (str
                               (get-in BUILD [:project :version])
                               " - built at "
                               (get-in BUILD [:project :built-at]))}
   :global-opts []
   :commands    [{:command     "json" :short "j"
                  :description ["Prints JMX beans as JSON"]
                  :opts        [{:option "cfg"
                                 :as "EDN configuration"
                                 :type :ednfile}]
                  :runs        dump-all-servers}]})

(defn -main
  "This is our entry point.
  Just pass parameters and configuration.
  Commands (functions) will be invoked as appropriate."
  [& args]
  (run-cmd args CONFIGURATION))

(st/instrument)


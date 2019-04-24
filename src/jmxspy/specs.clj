(ns jmxspy.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::a-bean
  (s/keys :req-un [::bean ::attribute ::as]))

(s/def ::presets
  (s/map-of keyword? (s/coll-of ::a-bean)))

(s/def ::creds
  (s/or
   :remote (s/keys :req-un [::host ::port])
   :local (s/and map? empty?)))

(s/def ::extra-attrs (s/map-of keyword? any?))

(s/def ::polling (s/coll-of keyword?))

(s/def ::a-server (s/keys :req-un [::creds ::extra-attrs ::polling]))

(s/def ::servers (s/coll-of ::a-server))

(s/def ::CFG (s/keys :req-un [::presets ::servers]))


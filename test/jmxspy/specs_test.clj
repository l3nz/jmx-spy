(ns jmxspy.specs-test
  (:require [clojure.test :refer :all])
  (:require [jmxspy.specs :refer :all]
            [clojure.spec.alpha :as s]))

(def CFG
  {:presets {:memory
             [{:bean "x" :attribute "y" :as "q"}]}

   :servers [{:creds {:host  "x"  :port 1234}
              :extra-attrs {:a 1 :b 2}
              :polling [:memory]}]})

(deftest test-configuration-spec
  (is (s/valid? :jmxspy.specs/CFG CFG)))


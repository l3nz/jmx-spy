(ns jmxspy.core-test
  (:require [clojure.test :refer :all]
            [jmxspy.core :refer :all]))

(deftest test-attribute-name
  (are [attr  a sa]
       (= [a  sa] (string->attr  attr))

    "a" :a nil
    "abc.dE" :abc :dE))

(ns jmxspy.core-test
  (:require [clojure.test :refer :all]
            [jmxspy.core :refer :all]))

(deftest test-attribute-name
  (are [attr  a sa]
       (= [a  sa] (string->attr  attr))

    "a" :a nil
    "abc.dE" :abc :dE))

(deftest test-searchable-bean
  (are [bean searchable?]
       (= searchable? (isSearchableBean?  bean))

    "a" false
    "a:b*" true))

(deftest test-sourcebean->bean
  (are [sb b]
       (= (sourcebean->bean sb) b)

    ; Plain non-searchable case
    {:bean "abc" :attribute "def" :as "x"}
    {:bean "abc" :attribute "def" :as "x" :group-by :first :searchable? false}

    ; No :as
    {:bean "abc" :attribute "def"}
    {:bean "abc" :attribute "def" :as "abc def" :group-by :first :searchable? false}

    ; Empty :as
    {:bean "abc" :attribute "def" :as ""}
    {:bean "abc" :attribute "def" :as "abc def" :group-by :first :searchable? false}

    ; Plain searchable case with no group-by
    {:bean "abc*" :attribute "def" :as "x"}
    {:bean "abc*" :attribute "def" :as "x" :group-by :first :searchable? true}

    ; Plain searchable case with group-by
    {:bean "abc*" :attribute "def" :as "x" :group-by :count}
    {:bean "abc*" :attribute "def" :as "x" :group-by :count :searchable? true}))

(deftest test-grouper
  (are [grpby vals res]
       (= (grouper grpby vals) res)

    ; Count
    :count [] 0
    :count [7 9 3 6] 4

    ; Sum
    :sum [] 0
    :sum [1 2 3] 6
    :sum [1 "x" :q] :ERROR

       ; First
    :first [] :ERROR
    :first [7 2 5] 7))


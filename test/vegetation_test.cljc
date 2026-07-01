(ns vegetation-test
  (:require [clojure.test :refer [deftest is testing]]
            [vegetation]))
(deftest namespace-loads
  (testing "the restored CLJC namespace loads"
    (is (some? vegetation))))

(ns filament.smoke-test
  (:require [clojure.test :refer [deftest is]]))

(deftest require-core-returns-nil
  (is (nil? (require 'filament.core))))

(ns filament.smoke-test
  (:require [clojure.test :refer [deftest is]]))

(deftest require-deferred-returns-nil
  (is (nil? (require 'filament.deferred))))

(deftest require-time-returns-nil
  (is (nil? (require 'filament.time))))

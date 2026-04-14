(ns filament.pinning-test
  (:require [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is]]))

(deftest ^:pinning no-pinning-under-hot-path
  ;; Spawn a sub-JVM so we can enable -Djdk.tracePinnedThreads=full
  ;; cleanly and capture stderr in isolation. The JDK prints "Thread is
  ;; pinned" (among others) to stderr when a virtual thread pins its
  ;; carrier; any such marker is a regression.
  (let [{:keys [err out exit]}
        (sh/sh "clojure"
               "-J--enable-preview"
               "-J-Djdk.tracePinnedThreads=full"
               "-Sdeps" "{:aliases {:pin-run {:extra-paths [\"bench\" \"test\"]}}}"
               "-M:pin-run"
               "-e" "(require 'filament.bench.hot-path) (filament.bench.hot-path/run!) (println \"ok\")")]
    (is (zero? exit)
        (str "hot-path sub-JVM failed: exit=" exit
             "\nstdout:\n" out
             "\nstderr:\n" err))
    (is (not (re-find #"Thread is pinned" (str err)))
        (str "pinning detected in stderr:\n" err))
    (is (not (re-find #"Thread is pinned" (str out)))
        (str "pinning detected in stdout:\n" out))))

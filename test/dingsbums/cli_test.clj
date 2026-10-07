(ns dingsbums.cli-test
  (:require [clojure.test :refer [deftest is]]
            [dingsbums.cli :as cli]))

(deftest parse-args
  (is (= {:port 8080} (cli/parse-args [])))
  (is (= {:port 9000} (cli/parse-args ["9000"])))
  (is (= {:port 0} (cli/parse-args ["0"])) "0: any free port")
  (is (= {:version true} (cli/parse-args ["--version"])))
  (is (= {:version true} (cli/parse-args ["-v"])))
  (is (= {:help true} (cli/parse-args ["--help"])))
  (is (= {:help true} (cli/parse-args ["-h"])))
  (is (:error (cli/parse-args ["abc"])))
  (is (:error (cli/parse-args ["70000"])))
  (is (:error (cli/parse-args ["-1"])))
  (is (:error (cli/parse-args ["8080" "9090"]))))

(deftest version
  (is (= "dev" (cli/version)) "a checkout has no VERSION"))
